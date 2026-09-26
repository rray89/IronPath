package com.example.ironpath.data.backup

import com.example.ironpath.data.account.AccountSessionOperationGate
import com.example.ironpath.data.account.PersistedAccountGateway
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.domain.account.*
import com.example.ironpath.domain.backup.*
import com.example.ironpath.domain.identity.IdProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualBackupCoordinatorTest {
    @Test
    fun signOutWaitsForInFlightBackupAndRejectsQueuedManualWork() = runTest {
        val fixture = Fixture()
        var captures = 0
        fixture.local.onCapture = { captures++ }
        val gateway = signedInGateway(fixture)
        val account = gateway.state.value as AccountState.SignedIn
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        val publishStarted = CompletableDeferred<Unit>()
        val finishPublish = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        fixture.cloud.onPublish = {
            events += "publish-started"
            publishStarted.complete(Unit)
            finishPublish.await()
            events += "publish-finished"
        }
        fixture.session.onClear = { events += "session-cleared" }

        val confirmation =
            async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.confirmBackup(preview.id)
            }
        publishStarted.await()
        val captureCountBeforeQueuedRefresh = captures
        val queuedRefresh =
            async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.refreshStatus() }
        val signOut =
            async(start = CoroutineStart.UNDISPATCHED) {
                gateway.signOut(
                    SignOutRequest(
                        account.accountId,
                        account.sessionEpoch,
                        SignOutDataChoice.KeepData,
                    )
                )
            }
        runCurrent()

        assertEquals(AccountState.SigningOut, gateway.state.value)
        assertEquals(0, fixture.session.clearCalls)
        assertEquals(captureCountBeforeQueuedRefresh, captures)
        assertEquals(BackupPreviewResult.Unavailable, fixture.coordinator.previewBackup())

        finishPublish.complete(Unit)
        assertEquals(BackupActionResult.Completed, confirmation.await())
        queuedRefresh.await()
        assertEquals(AccountActionResult.Completed, signOut.await())

        assertEquals(listOf("publish-started", "publish-finished", "session-cleared"), events)
        assertEquals(1, fixture.session.clearCalls)
        assertEquals(3, captures) // Preview, confirmation revalidation, and backup result refresh.
        assertNull(fixture.session.profile)
        assertEquals("owner", fixture.local.value.metadata.ownerUid)
    }

    @Test
    fun sameAccountSignInAfterSignOutCannotReuseAnOldBackupPreview() = runTest {
        val fixture = Fixture()
        val gateway = signedInGateway(fixture)
        val account = gateway.state.value as AccountState.SignedIn
        val stalePreview =
            (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview

        assertEquals(
            AccountActionResult.Completed,
            gateway.signOut(
                SignOutRequest(
                    account.accountId,
                    account.sessionEpoch,
                    SignOutDataChoice.KeepData,
                )
            ),
        )
        assertEquals(AccountActionResult.Completed, gateway.startGoogleSignIn())
        assertTrue(gateway.state.value is AccountState.SignedIn)

        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmBackup(stalePreview.id),
        )
        assertEquals(0, fixture.cloud.writes)
        assertEquals("owner", fixture.local.value.metadata.ownerUid)
    }

    @Test
    fun cancellationQueuedBehindRefreshRevokesThePreviewToken() = runTest {
        val fixture = Fixture()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        val captureReached = CompletableDeferred<Unit>()
        val finishCapture = CompletableDeferred<Unit>()
        var pauseCapture = true
        fixture.local.onCapture = {
            if (pauseCapture) {
                pauseCapture = false
                captureReached.complete(Unit)
                finishCapture.await()
            }
        }
        val refresh = async { fixture.coordinator.refreshStatus() }
        captureReached.await()
        val discard =
            async(start = CoroutineStart.UNDISPATCHED) {
                fixture.coordinator.discardPreview(preview.id)
            }
        val cancellationSkippedTheRefresh = discard.isCompleted
        finishCapture.complete(Unit)
        refresh.await()
        discard.await()
        assertFalse(cancellationSkippedTheRefresh)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmBackup(preview.id)
        )
        assertEquals(0, fixture.cloud.writes)
    }

    @Test
    fun danglingLocalProvenanceDoesNotTurnCloudOnlyEditsIntoConflicts() = runTest {
        listOf(SyncConflictResolution.KeepLocal, SyncConflictResolution.KeepCloud).forEach {
            resolution ->
            val fixture = Fixture()
            val log = WorkoutLog("log", "Original workout", "deleted-workout", 1, 2, 1, 0)
            val record = record("record").copy(sourceWorkoutLogId = "deleted-log")
            fixture.local.value =
                fixture.local.value.copy(
                    bundle = bundle(listOf(record)).copy(workoutLogs = listOf(log))
                )
            fixture.firstBackup()
            val cloudBase = BackupSnapshotCodec().decode(fixture.cloud.artifact!!.snapshot)
            fixture.cloud.change(
                cloudBase.copy(
                    workoutLogs =
                        cloudBase.workoutLogs.map { it.copy(title = "Cloud workout edit") },
                    personalRecords =
                        cloudBase.personalRecords.map { it.copy(note = "Cloud record edit") },
                )
            )
            val preview = (fixture.coordinator.previewSync() as SyncPreviewResult.Ready).preview
            assertEquals(0, preview.localChanges.values.sum())
            assertEquals(0, preview.conflicts.values.sum())
            assertEquals(1, preview.cloudChanges["WorkoutLog"])
            assertEquals(1, preview.cloudChanges["PersonalRecord"])
            assertEquals(
                BackupActionResult.Completed,
                fixture.coordinator.confirmSync(preview.id, resolution)
            )
            val merged = fixture.local.value.bundle
            assertEquals("Cloud workout edit", merged.workoutLogs.single().title)
            assertEquals("Cloud record edit", merged.personalRecords.single().note)
            assertNull(merged.workoutLogs.single().sourcePlannedWorkoutId)
            assertNull(merged.personalRecords.single().sourceWorkoutLogId)
        }
    }

    @Test
    fun localRefreshQueuedDuringFinalBackupCaptureCannotLoseTheOnlyRevisionUpdate() = runTest {
        val fixture = Fixture()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        val finalCaptureReached = CompletableDeferred<Unit>()
        val finishFinalCapture = CompletableDeferred<Unit>()
        var pauseOwnedCapture = true
        fixture.local.onCapture = { captured ->
            if (captured.metadata.ownerUid != null && pauseOwnedCapture) {
                pauseOwnedCapture = false
                finalCaptureReached.complete(Unit)
                finishFinalCapture.await()
            }
        }
        val confirmation = async { fixture.coordinator.confirmBackup(preview.id) }
        finalCaptureReached.await()
        // Commands still reject duplicate work; only the observed-state refresh waits.
        assertEquals(BackupPreviewResult.Unavailable, fixture.coordinator.previewBackup())
        fixture.local.edit()
        val refresh =
            async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.refreshStatus() }
        finishFinalCapture.complete(Unit)
        assertEquals(BackupActionResult.Completed, confirmation.await())
        refresh.await()
        assertEquals(BackupStatus.LocalChanges, fixture.coordinator.status.value)
        assertEquals(1, fixture.cloud.writes)
        assertEquals(2, fixture.cloud.reads)
    }

    @Test
    fun discardingAFailedPreviewPreservesItsTypedFailureStatus() = runTest {
        val fixture = Fixture()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        fixture.cloud.failure = BackupFailureReason.QuotaOrRateLimited
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.QuotaOrRateLimited),
            fixture.coordinator.confirmBackup(preview.id)
        )
        fixture.coordinator.discardPreview(preview.id)
        assertEquals(BackupStatus.QuotaPaused, fixture.coordinator.status.value)
    }

    @Test
    fun refreshAndPreviewNeverClaimOrWriteData() = runTest {
        val fixture = Fixture()
        fixture.coordinator.refreshStatus()
        assertEquals(BackupStatus.SignedInNoBackup, fixture.coordinator.status.value)
        assertEquals(0, fixture.cloud.reads)
        assertTrue(fixture.coordinator.previewBackup() is BackupPreviewResult.Ready)
        assertEquals(0, fixture.local.writes)
        assertEquals(0, fixture.cloud.writes)
    }

    @Test
    fun latestCompleteLookupDisplaysUnownedBackupWithoutClaimingOrPublishingLocalData() = runTest {
        val fixture = Fixture()
        fixture.cloud.change(bundle(listOf(record("remote"))))
        val originalLocal = fixture.local.value
        assertNull(originalLocal.metadata.ownerUid)
        assertNull(originalLocal.baseline)

        assertEquals(
            BackupLookupResult.Complete(fixture.cloud.artifact!!.summary),
            fixture.coordinator.latestCompleteBackup(),
        )

        assertEquals(fixture.cloud.artifact!!.summary, fixture.coordinator.latestSummary.value)
        assertEquals(originalLocal, fixture.local.value)
        assertEquals(0, fixture.local.writes)
        assertEquals(0, fixture.cloud.writes)
    }

    @Test
    fun restoreAndUndoPreserveOldSharedBaseAndRequireAnExplicitConflictChoice() = runTest {
        val fixture = Fixture()
        fixture.firstBackup()
        val originalBase = fixture.local.value.baseline
        val revision = fixture.local.value.metadata.localChangeRevision + 1
        fixture.local.value =
            fixture.local.value.copy(
                metadata = fixture.local.value.metadata.copy(localChangeRevision = revision),
                bundle =
                    fixture.local.value.bundle.copy(
                        localChangeRevision = revision,
                        personalRecords = listOf(record("first").copy(weightKg = 60.0)),
                    ),
            )
        fixture.cloud.change(bundle(listOf(record("first").copy(weightKg = 70.0))))

        val preview = (fixture.coordinator.previewRestore() as RestorePreviewResult.Ready).preview
        assertEquals(1, preview.impact.getValue("PersonalRecord").updated)
        assertEquals("Another device", preview.sourceDescription)
        assertEquals(BackupActionResult.Completed, fixture.coordinator.confirmRestore(preview.id))
        assertTrue(fixture.coordinator.undoAvailable.value)
        val undo = (fixture.coordinator.previewUndo() as UndoPreviewResult.Ready).preview
        assertEquals(1, undo.impact.getValue("PersonalRecord").updated)
        assertEquals(BackupActionResult.Completed, fixture.coordinator.confirmUndo(undo.id))

        assertEquals(60.0, fixture.local.value.bundle.personalRecords.single().weightKg, 0.0)
        assertEquals(originalBase, fixture.local.value.baseline)
        assertEquals(1L, fixture.local.value.metadata.lastObservedRemoteGeneration)
        assertEquals(BackupStatus.ReviewRequired, fixture.coordinator.status.value)
        assertFalse(fixture.coordinator.undoAvailable.value)
        assertEquals(1, fixture.cloud.writes)

        val sync = (fixture.coordinator.previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(1, sync.conflicts.getValue("PersonalRecord"))
        assertEquals(1, fixture.cloud.writes)
    }

    @Test
    fun activeWorkoutRequiresUncheckedThenBoundDiscardAcknowledgement() = runTest {
        val fixture = Fixture()
        fixture.cloud.change(bundle(listOf(record("remote"))))
        fixture.local.value =
            fixture.local.value.copy(
                activeSessionId = "session-current",
                activeSessionTitle = "Evening strength",
            )

        val preview = (fixture.coordinator.previewRestore() as RestorePreviewResult.Ready).preview
        assertTrue(preview.activeWorkoutDiscardRequired)
        assertEquals("Evening strength", preview.activeWorkoutTitle)
        assertEquals(
            BackupActionResult.ActiveSessionRequiresConfirmation("session-current"),
            fixture.coordinator.confirmRestore(preview.id),
        )
        assertEquals(0, fixture.local.writes)
        assertEquals(0, fixture.cloud.writes)

        fixture.local.value = fixture.local.value.copy(activeSessionId = "session-replacement")
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmRestore(preview.id, activeWorkoutDiscardConfirmed = true),
        )
        assertEquals(0, fixture.local.writes)
        assertEquals(0, fixture.cloud.writes)
    }

    @Test
    fun accountLossWhileRestoreRevalidationWaitsOnRemoteCannotMutateRoomOrUndoSlot() = runTest {
        val fixture = Fixture()
        fixture.cloud.change(bundle(listOf(record("remote"))))
        val preview = (fixture.coordinator.previewRestore() as RestorePreviewResult.Ready).preview
        val readStarted = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        fixture.cloud.onRead = {
            fixture.cloud.onRead = {}
            readStarted.complete(Unit)
            finishRead.await()
        }

        val confirmation = async { fixture.coordinator.confirmRestore(preview.id) }
        readStarted.await()
        fixture.session.profile = null
        finishRead.complete(Unit)

        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ReauthenticationRequired),
            confirmation.await(),
        )
        assertEquals(0, fixture.local.writes)
        assertNull(fixture.local.captureUndo(AccountId("owner"), "installation"))
        assertEquals(0, fixture.cloud.writes)
    }

    @Test
    fun accountSwitchWhileRestorePreviewWaitsOnRemoteDoesNotPublishOtherAccountsBackup() = runTest {
        val fixture = Fixture()
        fixture.cloud.change(bundle(listOf(record("account-a-backup"))))
        assertTrue(fixture.coordinator.latestCompleteBackup() is BackupLookupResult.Complete)
        assertEquals(fixture.cloud.artifact!!.summary, fixture.coordinator.latestSummary.value)

        val readStarted = CompletableDeferred<Unit>()
        val finishRead = CompletableDeferred<Unit>()
        fixture.cloud.onRead = {
            fixture.cloud.onRead = {}
            readStarted.complete(Unit)
            finishRead.await()
        }

        val preview = async { fixture.coordinator.previewRestore() }
        readStarted.await()
        fixture.session.profile = AccountProfile(AccountId("account-b"), "B", "b@example.invalid")
        finishRead.complete(Unit)

        assertEquals(
            RestorePreviewResult.Failed(BackupFailureReason.ReauthenticationRequired),
            preview.await(),
        )
        assertNull(fixture.coordinator.latestSummary.value)
        assertEquals(BackupStatus.NeedsSignIn, fixture.coordinator.status.value)
        assertEquals(0, fixture.local.writes)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmRestore("id-1"),
        )
    }

    @Test
    fun accountLossAfterUndoRevalidationCannotConsumeTheUndoSlot() = runTest {
        val fixture = Fixture()
        fixture.cloud.change(bundle(listOf(record("remote"))))
        val restore = (fixture.coordinator.previewRestore() as RestorePreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, fixture.coordinator.confirmRestore(restore.id))
        val installationId = fixture.local.value.metadata.installationId
        val slotBefore = checkNotNull(fixture.local.captureUndo(AccountId("owner"), installationId))
        val undo = (fixture.coordinator.previewUndo() as UndoPreviewResult.Ready).preview
        val writesBefore = fixture.local.writes

        fixture.local.onCapture = { fixture.session.profile = null }

        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ReauthenticationRequired),
            fixture.coordinator.confirmUndo(undo.id),
        )
        assertEquals(writesBefore, fixture.local.writes)
        assertEquals(
            slotBefore,
            fixture.local.captureUndo(AccountId("owner"), installationId),
        )
    }

    @Test
    fun confirmedFirstBackupClaimsDataAndReplayCannotWriteAgain() = runTest {
        val fixture = Fixture()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, fixture.coordinator.confirmBackup(preview.id))
        assertEquals("owner", fixture.local.value.metadata.ownerUid)
        assertEquals(1, fixture.cloud.writes)
        assertTrue(fixture.coordinator.confirmBackup(preview.id) is BackupActionResult.Failed)
        assertEquals(1, fixture.cloud.writes)
    }

    @Test
    fun emptyConfirmationAssociatesWithoutCreatingSnapshot() = runTest {
        val fixture = Fixture(empty = true)
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        assertTrue(preview.associationOnly)
        assertEquals(BackupActionResult.Completed, fixture.coordinator.confirmBackup(preview.id))
        assertEquals("owner", fixture.local.value.metadata.ownerUid)
        assertEquals(0, fixture.cloud.writes)
    }

    @Test
    fun keepDeviceEmptyAssociatesOnlyOwnerAndPreservesCompleteRemoteAndLocalLineage() = runTest {
        val fixture = Fixture(empty = true)
        fixture.cloud.change(bundle(listOf(record("remote-record"))))
        val original = fixture.local.value
        val remoteBefore = fixture.cloud.artifact
        assertTrue(fixture.coordinator.latestCompleteBackup() is BackupLookupResult.Complete)

        assertEquals(
            BackupActionResult.Completed,
            fixture.coordinator.associateEmptyProfile(
                AccountId("owner"),
                fixture.gate.sessionEpoch,
                fixture.local.value.metadata.installationId,
                fixture.local.value.metadata.localChangeRevision,
                snapshotChoice(checkNotNull(remoteBefore)),
            ),
        )

        val associated = fixture.local.value
        assertEquals(original.metadata.copy(ownerUid = "owner"), associated.metadata)
        assertEquals(original.bundle, associated.bundle)
        assertEquals(original.baseline, associated.baseline)
        assertEquals(remoteBefore, fixture.cloud.artifact)
        assertEquals(0, fixture.cloud.writes)
        assertEquals(BackupStatus.ReviewRequired, fixture.coordinator.status.value)
    }

    @Test
    fun keepDeviceEmptyRejectsActiveWorkoutAndForeignOwnerWithoutMutatingAnything() = runTest {
        val active = Fixture(empty = true)
        active.cloud.change(bundle(emptyList()))
        active.local.value = active.local.value.copy(activeSessionId = "active-workout")
        val activeBefore = active.local.value
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ActiveSessionPresent),
            active.coordinator.associateEmptyProfile(
                AccountId("owner"),
                active.gate.sessionEpoch,
                active.local.value.metadata.installationId,
                active.local.value.metadata.localChangeRevision,
                snapshotChoice(checkNotNull(active.cloud.artifact)),
            ),
        )
        assertEquals(activeBefore, active.local.value)
        assertEquals(0, active.local.writes)

        val foreign = Fixture(empty = true)
        foreign.cloud.change(bundle(emptyList()))
        foreign.local.value =
            foreign.local.value.copy(
                metadata = foreign.local.value.metadata.copy(ownerUid = "another-account")
            )
        val foreignBefore = foreign.local.value
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.OwnershipMismatch),
            foreign.coordinator.associateEmptyProfile(
                AccountId("owner"),
                foreign.gate.sessionEpoch,
                foreign.local.value.metadata.installationId,
                foreign.local.value.metadata.localChangeRevision,
                snapshotChoice(checkNotNull(foreign.cloud.artifact)),
            ),
        )
        assertEquals(foreignBefore, foreign.local.value)
        assertEquals(0, foreign.local.writes)
    }

    @Test
    fun keepDeviceEmptyRejectsChangedOrMissingRemoteAndStaleSessionEpoch() = runTest {
        val changed = Fixture(empty = true)
        changed.cloud.change(bundle(listOf(record("first"))))
        val reviewed = snapshotChoice(checkNotNull(changed.cloud.artifact))
        assertTrue(changed.coordinator.latestCompleteBackup() is BackupLookupResult.Complete)
        val localBeforeChange = changed.local.value
        changed.cloud.change(bundle(listOf(record("second"))))
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            changed.coordinator.associateEmptyProfile(
                AccountId("owner"),
                changed.gate.sessionEpoch,
                changed.local.value.metadata.installationId,
                changed.local.value.metadata.localChangeRevision,
                reviewed,
            ),
        )
        assertEquals(localBeforeChange, changed.local.value)
        assertEquals(0, changed.local.writes)

        val missing = Fixture(empty = true)
        missing.cloud.change(bundle(listOf(record("first"))))
        val present = snapshotChoice(checkNotNull(missing.cloud.artifact))
        assertTrue(missing.coordinator.latestCompleteBackup() is BackupLookupResult.Complete)
        val localBeforeDelete = missing.local.value
        missing.cloud.artifact = null
        missing.cloud.generation++
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            missing.coordinator.associateEmptyProfile(
                AccountId("owner"),
                missing.gate.sessionEpoch,
                missing.local.value.metadata.installationId,
                missing.local.value.metadata.localChangeRevision,
                present,
            ),
        )
        assertEquals(localBeforeDelete, missing.local.value)
        assertEquals(0, missing.local.writes)

        val staleSession = Fixture(empty = true)
        staleSession.cloud.change(bundle(listOf(record("first"))))
        val expected = snapshotChoice(checkNotNull(staleSession.cloud.artifact))
        val reviewedEpoch = staleSession.gate.sessionEpoch
        staleSession.gate.withSessionMutation { _, _ ->
            AccountSessionOperationGate.MutationResult(Unit, reopenAdmission = true)
        }
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ReauthenticationRequired),
            staleSession.coordinator.associateEmptyProfile(
                AccountId("owner"),
                reviewedEpoch,
                staleSession.local.value.metadata.installationId,
                staleSession.local.value.metadata.localChangeRevision,
                expected,
            ),
        )
        assertEquals(0, staleSession.cloud.reads)
        assertEquals(0, staleSession.local.writes)
    }

    @Test
    fun keepDeviceEmptyRevalidatesEachChoiceTokenFieldIndependently() = runTest {
        suspend fun reviewedFixture(): Pair<Fixture, RemoteSnapshotPresence.Complete> {
            val fixture = Fixture(empty = true)
            fixture.cloud.change(bundle(listOf(record("remote-record"))))
            assertTrue(fixture.coordinator.latestCompleteBackup() is BackupLookupResult.Complete)
            return fixture to snapshotChoice(checkNotNull(fixture.cloud.artifact))
        }

        suspend fun assertRejectedWithoutMutation(
            fixture: Fixture,
            reviewed: RemoteSnapshotPresence.Complete,
            expectedFailure: BackupFailureReason,
            expectedInstallationId: String,
            expectedLocalChangeRevision: Long,
        ) {
            val localBefore = fixture.local.value
            val remoteBefore = fixture.cloud.artifact

            assertEquals(
                BackupActionResult.Failed(expectedFailure),
                fixture.coordinator.associateEmptyProfile(
                    AccountId("owner"),
                    fixture.gate.sessionEpoch,
                    expectedInstallationId,
                    expectedLocalChangeRevision,
                    reviewed,
                ),
            )
            assertEquals(localBefore, fixture.local.value)
            assertEquals(remoteBefore, fixture.cloud.artifact)
            assertEquals(0, fixture.local.writes)
            assertEquals(0, fixture.cloud.writes)
        }

        val (staleRevision, revisionChoice) = reviewedFixture()
        assertRejectedWithoutMutation(
            staleRevision,
            revisionChoice,
            BackupFailureReason.StalePreview,
            staleRevision.local.value.metadata.installationId,
            staleRevision.local.value.metadata.localChangeRevision + 1,
        )

        val (staleInstallation, installationChoice) = reviewedFixture()
        assertRejectedWithoutMutation(
            staleInstallation,
            installationChoice,
            BackupFailureReason.StalePreview,
            "replacement-installation",
            staleInstallation.local.value.metadata.localChangeRevision,
        )

        val (changedGeneration, generationChoice) = reviewedFixture()
        val generationArtifact = checkNotNull(changedGeneration.cloud.artifact)
        changedGeneration.cloud.artifact =
            generationArtifact.copy(generation = generationArtifact.generation + 1)
        assertRejectedWithoutMutation(
            changedGeneration,
            generationChoice,
            BackupFailureReason.ConcurrentRemoteChange,
            changedGeneration.local.value.metadata.installationId,
            changedGeneration.local.value.metadata.localChangeRevision,
        )

        val (changedSource, sourceChoice) = reviewedFixture()
        val sourceArtifact = checkNotNull(changedSource.cloud.artifact)
        changedSource.cloud.artifact =
            sourceArtifact.copy(
                summary = sourceArtifact.summary.copy(sourceInstallationId = "new-source")
            )
        assertRejectedWithoutMutation(
            changedSource,
            sourceChoice,
            BackupFailureReason.ConcurrentRemoteChange,
            changedSource.local.value.metadata.installationId,
            changedSource.local.value.metadata.localChangeRevision,
        )

        val (changedDigest, digestChoice) = reviewedFixture()
        val digestArtifact = checkNotNull(changedDigest.cloud.artifact)
        val changedSnapshot =
            BackupSnapshotCodec().encode(bundle(listOf(record("different-remote-record"))))
        changedDigest.cloud.artifact = digestArtifact.copy(snapshot = changedSnapshot)
        assertRejectedWithoutMutation(
            changedDigest,
            digestChoice,
            BackupFailureReason.ConcurrentRemoteChange,
            changedDigest.local.value.metadata.installationId,
            changedDigest.local.value.metadata.localChangeRevision,
        )
    }

    @Test
    fun keepDeviceEmptyRejectsInstallationTransferDetectedAtAdmission() = runTest {
        val fixture = Fixture(empty = true)
        fixture.cloud.change(bundle(listOf(record("remote-record"))))
        val reviewedRemote = snapshotChoice(checkNotNull(fixture.cloud.artifact))
        val reviewedMetadata = fixture.local.value.metadata
        assertTrue(fixture.coordinator.latestCompleteBackup() is BackupLookupResult.Complete)
        fixture.transferOnNextValidation = true

        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.associateEmptyProfile(
                AccountId("owner"),
                fixture.gate.sessionEpoch,
                reviewedMetadata.installationId,
                reviewedMetadata.localChangeRevision,
                reviewedRemote,
            ),
        )

        val transferred = fixture.local.value
        assertEquals("transferred-installation", transferred.metadata.installationId)
        assertNull(transferred.metadata.ownerUid)
        assertNull(transferred.metadata.lastObservedRemoteBackupId)
        assertEquals(0, transferred.metadata.lastObservedRemoteGeneration)
        assertNull(transferred.baseline)
        assertEquals(0, fixture.local.writes)
        assertEquals(1, fixture.cloud.reads)
    }

    @Test
    fun staleLocalOrRemotePreviewCannotMutateEitherSide() = runTest {
        val fixture = Fixture()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        fixture.local.edit()
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmBackup(preview.id)
        )
        assertEquals(0, fixture.cloud.writes)
        val second = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        fixture.cloud.generation++
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmBackup(second.id)
        )
        assertEquals(0, fixture.local.writes)
    }

    @Test
    fun remoteFailurePreservesDataOwnershipAndBaseline() = runTest {
        val fixture = Fixture()
        val original = fixture.local.value
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        fixture.cloud.failure = BackupFailureReason.Offline
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.Offline),
            fixture.coordinator.confirmBackup(preview.id)
        )
        assertEquals(original, fixture.local.value)
    }

    @Test
    fun localMutationDuringBackupLeavesDirtyStateWithoutLosingTheChange() = runTest {
        val fixture = Fixture()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        fixture.cloud.onPublish = { fixture.local.edit() }
        assertEquals(BackupActionResult.Completed, fixture.coordinator.confirmBackup(preview.id))
        assertEquals(BackupStatus.LocalChanges, fixture.coordinator.status.value)
        assertEquals(2, fixture.local.value.bundle.personalRecords.size)
    }

    @Test
    fun ownershipAndInstallationFailuresBlockEvenEmptyProfiles() = runTest {
        val fixture = Fixture(empty = true)
        fixture.local.value =
            fixture.local.value.copy(
                metadata = fixture.local.value.metadata.copy(ownerUid = "other")
            )
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.OwnershipMismatch),
            fixture.coordinator.previewBackup()
        )
        assertEquals(0, fixture.cloud.writes)
    }

    @Test
    fun cancelledPreviewCannotBeConfirmed() = runTest {
        val fixture = Fixture()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        fixture.coordinator.discardPreview(preview.id)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmBackup(preview.id)
        )
    }

    @Test
    fun syncRequiresConflictChoiceAndCloudChoiceKeepsLocalOnlyAdditions() = runTest {
        val fixture = Fixture()
        fixture.firstBackup()
        fixture.local.edit()
        fixture.local.value =
            fixture.local.value.copy(
                bundle =
                    fixture.local.value.bundle.copy(
                        personalRecords =
                            fixture.local.value.bundle.personalRecords.map {
                                if (it.id == "first") it.copy(weightKg = 70.0) else it
                            }
                    )
            )
        fixture.cloud.change(bundle(listOf(record("first").copy(weightKg = 60.0), record("cloud"))))
        val preview = (fixture.coordinator.previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(1, preview.conflicts["PersonalRecord"])
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ConflictChoiceRequired),
            fixture.coordinator.confirmSync(preview.id)
        )
        assertEquals(
            BackupActionResult.Completed,
            fixture.coordinator.confirmSync(preview.id, SyncConflictResolution.KeepCloud)
        )
        assertEquals(
            setOf("first", "second", "cloud"),
            fixture.local.value.bundle.personalRecords.map { it.id }.toSet()
        )
        assertEquals(
            60.0,
            fixture.local.value.bundle.personalRecords.first { it.id == "first" }.weightKg,
            0.0
        )
    }

    @Test
    fun syncLocalRaceAfterPublicationRetainsOriginalBaselineAndLocalWork() = runTest {
        val fixture = Fixture()
        fixture.firstBackup()
        val oldBaseline = fixture.local.value.baseline
        fixture.cloud.change(bundle(listOf(record("first"), record("cloud"))))
        fixture.local.edit()
        val preview = (fixture.coordinator.previewSync() as SyncPreviewResult.Ready).preview
        fixture.cloud.onPublish = {
            fixture.local.value =
                fixture.local.value.copy(
                    metadata = fixture.local.value.metadata.copy(localChangeRevision = 3),
                    bundle =
                        fixture.local.value.bundle.copy(
                            localChangeRevision = 3,
                            personalRecords =
                                fixture.local.value.bundle.personalRecords + record("racing")
                        )
                )
        }
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmSync(preview.id)
        )
        assertEquals(oldBaseline, fixture.local.value.baseline)
        assertTrue(fixture.local.value.bundle.personalRecords.any { it.id == "racing" })
        assertFalse(fixture.local.value.bundle.personalRecords.any { it.id == "cloud" })
        // The published merge is from this installation, but it has never been applied to Room.
        // A plain backup must not replace its cloud-only additions with the untouched local graph.
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            fixture.coordinator.previewBackup()
        )
        fixture.cloud.onPublish = {}
        val recovery = (fixture.coordinator.previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, fixture.coordinator.confirmSync(recovery.id))
        assertEquals(
            setOf("first", "second", "cloud", "racing"),
            fixture.local.value.bundle.personalRecords.map { it.id }.toSet()
        )
    }

    @Test
    fun activeSessionBlocksSyncButNotBackupAndBackupCannotCrossUnobservedCloud() = runTest {
        val fixture = Fixture()
        fixture.local.value = fixture.local.value.copy(activeSessionId = "workout")
        fixture.firstBackup()
        assertEquals(
            SyncPreviewResult.Failed(BackupFailureReason.ActiveSessionPresent),
            fixture.coordinator.previewSync()
        )
        fixture.cloud.change(bundle(listOf(record("first"), record("cloud"))))
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            fixture.coordinator.previewBackup()
        )
    }

    @Test
    fun unchangedBackupUsesNoPayloadWriteAndDestructiveDropNeedsExplicitConsent() = runTest {
        val fixture = Fixture()
        fixture.firstBackup()
        fixture.firstBackup()
        assertEquals(1, fixture.cloud.writes)
        fixture.local.value =
            fixture.local.value.copy(
                metadata = fixture.local.value.metadata.copy(localChangeRevision = 2),
                bundle = bundle(emptyList()).copy(localChangeRevision = 2)
            )
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        assertTrue(preview.requiresDestructiveConfirmation)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.DestructiveLocalChange),
            fixture.coordinator.confirmBackup(preview.id)
        )
        assertEquals(1, fixture.cloud.writes)
        assertEquals(
            BackupActionResult.Completed,
            fixture.coordinator.confirmBackup(preview.id, true)
        )
        assertEquals(2, fixture.cloud.writes)
    }

    @Test
    fun localRefreshRetainsExplicitlyObservedRemoteReviewWithoutReadingAgain() = runTest {
        val fixture = Fixture()
        fixture.firstBackup()
        fixture.cloud.change(bundle(listOf(record("first"), record("cloud"))))
        assertTrue(fixture.coordinator.latestCompleteBackup() is BackupLookupResult.Complete)
        val reads = fixture.cloud.reads
        fixture.coordinator.refreshStatus()
        assertEquals(BackupStatus.ReviewRequired, fixture.coordinator.status.value)
        assertEquals(reads, fixture.cloud.reads)
    }

    @Test
    fun noIdentityAndInstallationFailureBlockAndLegacyOperationsStayInert() = runTest {
        val fixture = Fixture()
        fixture.session.profile = null
        fixture.coordinator.refreshStatus()
        assertEquals(BackupStatus.LocalOnly, fixture.coordinator.status.value)
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ReauthenticationRequired),
            fixture.coordinator.previewBackup()
        )
        assertEquals(BackupActionResult.Unavailable, fixture.coordinator.backUpNow())
        assertEquals(
            RestorePreviewResult.Failed(BackupFailureReason.ReauthenticationRequired),
            fixture.coordinator.previewRestore()
        )
        assertEquals(BackupActionResult.Unavailable, fixture.coordinator.deleteAllRemoteData())
        val failed =
            Fixture(
                guard =
                    object : InstallationGuard {
                        override suspend fun validate() = InstallationValidationResult.Failed
                    }
            )
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ServiceUnavailable),
            failed.coordinator.previewBackup()
        )
        assertEquals(0, failed.cloud.reads)
    }

    @Test
    fun absentAndFailedRemoteLookupAreExplicitAndFailuresAreSanitized() = runTest {
        val fixture = Fixture()
        assertEquals(BackupLookupResult.Absent, fixture.coordinator.latestCompleteBackup())
        assertEquals(SyncPreviewResult.Unavailable, fixture.coordinator.previewSync())
        fixture.cloud.readFailure = BackupFailureReason.QuotaOrRateLimited
        assertEquals(
            BackupLookupResult.Failed(BackupFailureReason.QuotaOrRateLimited),
            fixture.coordinator.latestCompleteBackup()
        )
        assertEquals(BackupStatus.QuotaPaused, fixture.coordinator.status.value)
        fixture.cloud.throwOnRead = true
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ServiceUnavailable),
            fixture.coordinator.previewBackup()
        )
    }

    @Test
    fun identityLossAfterAuthorizedPublishCannotClaimOrChangeRoom() = runTest {
        val fixture = Fixture()
        val original = fixture.local.value
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        fixture.cloud.onPublish = { fixture.session.profile = null }
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ReauthenticationRequired),
            fixture.coordinator.confirmBackup(preview.id)
        )
        assertEquals(original, fixture.local.value)
        assertNull(fixture.coordinator.latestSummary.value)
    }

    @Test
    fun mutatedPreviewCountsCannotBypassRequiredConflictChoice() = runTest {
        val fixture = Fixture()
        fixture.firstBackup()
        fixture.local.edit()
        fixture.local.value =
            fixture.local.value.copy(
                bundle =
                    fixture.local.value.bundle.copy(
                        personalRecords = listOf(record("first").copy(weightKg = 70.0))
                    )
            )
        fixture.cloud.change(bundle(listOf(record("first").copy(weightKg = 60.0))))
        val preview = (fixture.coordinator.previewSync() as SyncPreviewResult.Ready).preview
        (preview.conflicts as MutableMap<String, Int>).clear()
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ConflictChoiceRequired),
            fixture.coordinator.confirmSync(preview.id)
        )
    }

    @Test
    fun remotePayloadChangingWithoutGenerationStillInvalidatesThePreview() = runTest {
        val fixture = Fixture()
        fixture.firstBackup()
        val preview = (fixture.coordinator.previewBackup() as BackupPreviewResult.Ready).preview
        val snapshot = BackupSnapshotCodec().encode(bundle(listOf(record("different"))))
        fixture.cloud.artifact =
            fixture.cloud.artifact!!.copy(
                snapshot = snapshot,
                summary =
                    fixture.cloud.artifact!!.summary.copy(entityCounts = snapshot.entityCounts)
            )
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fixture.coordinator.confirmBackup(preview.id)
        )
    }

    private class Fixture(
        empty: Boolean = false,
        guard: InstallationGuard? = null,
        val gate: AccountSessionOperationGate = AccountSessionOperationGate(),
    ) {
        val local = Local(empty)
        val cloud = Cloud()
        val session = Session()
        var transferOnNextValidation = false
        private val defaultGuard =
            object : InstallationGuard {
                override suspend fun validate(): InstallationValidationResult {
                    if (!transferOnNextValidation) return InstallationValidationResult.Validated
                    transferOnNextValidation = false
                    val current = local.value
                    local.value =
                        current.copy(
                            metadata =
                                current.metadata.copy(
                                    ownerUid = null,
                                    installationId = "transferred-installation",
                                    lastCompleteLocalRevision = 0,
                                    lastObservedRemoteBackupId = null,
                                    lastObservedRemoteGeneration = 0,
                                    lastObservedRemoteDigest = null,
                                    lastObservedSourceInstallationId = null,
                                    lastObservedRemoteCompletedAt = null,
                                ),
                            baseline = null,
                        )
                    return InstallationValidationResult.Transferred
                }
            }
        val coordinator =
            ManualBackupCoordinator(
                local,
                cloud,
                session,
                guard ?: defaultGuard,
                object : IdProvider {
                    private var id = 0

                    override fun newId() = "id-${++id}"
                },
                gate,
                Dispatchers.Unconfined
            )

        suspend fun firstBackup() {
            val preview = (coordinator.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(BackupActionResult.Completed, coordinator.confirmBackup(preview.id))
        }
    }

    private class Local(empty: Boolean) : ManualBackupLocalStore {
        var value =
            ManualBackupCapture(
                AccountBackupMetadata(installationId = "installation", localChangeRevision = 1),
                bundle(if (empty) emptyList() else listOf(record("first"))),
                null,
                null
            )
        var writes = 0
        var onCapture: suspend (ManualBackupCapture) -> Unit = {}

        override suspend fun capture(): ManualBackupCapture {
            val captured = value
            onCapture(captured)
            return captured
        }

        fun edit() {
            value =
                value.copy(
                    metadata =
                        value.metadata.copy(
                            localChangeRevision = value.metadata.localChangeRevision + 1
                        ),
                    bundle =
                        value.bundle.copy(
                            localChangeRevision = value.bundle.localChangeRevision + 1,
                            personalRecords = value.bundle.personalRecords + record("second")
                        )
                )
        }

        override suspend fun associateEmpty(
            captured: ManualBackupCapture,
            accountId: AccountId
        ): Boolean {
            writes++
            value = value.copy(metadata = value.metadata.copy(ownerUid = accountId.opaqueValue))
            return true
        }

        override suspend fun recordBackup(
            captured: ManualBackupCapture,
            accountId: AccountId,
            backup: RemoteBackupArtifact
        ): Boolean {
            writes++
            value =
                value.copy(
                    metadata =
                        value.metadata.copy(
                            ownerUid = accountId.opaqueValue,
                            lastCompleteLocalRevision = captured.metadata.localChangeRevision,
                            lastObservedRemoteGeneration = backup.generation,
                            lastObservedRemoteBackupId = backup.summary.backupId
                        ),
                    baseline = backup
                )
            return true
        }

        override suspend fun applySync(
            captured: ManualBackupCapture,
            accountId: AccountId,
            backup: RemoteBackupArtifact
        ): Boolean {
            if (captured.metadata.localChangeRevision != value.metadata.localChangeRevision)
                return false
            recordBackup(captured, accountId, backup)
            value = value.copy(bundle = BackupSnapshotCodec().decode(backup.snapshot))
            return true
        }

        private var undoSlot: ManualBackupUndoCapture? = null

        override suspend fun restore(
            captured: ManualBackupCapture,
            accountId: AccountId,
            artifact: ValidatedRestoreArtifact,
            discardActiveSessionId: String?,
        ): Boolean {
            if (
                captured.metadata != value.metadata ||
                    captured.activeSessionId != value.activeSessionId ||
                    (value.activeSessionId != null &&
                        discardActiveSessionId != value.activeSessionId)
            )
                return false
            undoSlot =
                ManualBackupUndoCapture(
                    "undo-slot",
                    accountId.opaqueValue,
                    captured.metadata.installationId,
                    captured.metadata,
                    captured.bundle,
                    captured.baseline,
                )
            val revision = Math.addExact(value.metadata.localChangeRevision, 1)
            val snapshot = artifact.remoteSnapshot ?: BackupSnapshotCodec().encode(artifact.bundle)
            val remoteArtifact =
                RemoteBackupArtifact(
                    artifact.lineage.toSummary(snapshot.entityCounts),
                    artifact.lineage.remoteGeneration,
                    snapshot,
                )
            value =
                ManualBackupCapture(
                    value.metadata.copy(
                        ownerUid = accountId.opaqueValue,
                        localChangeRevision = revision,
                        lastCompleteLocalRevision = revision,
                        lastObservedRemoteBackupId = artifact.lineage.remoteBackupId,
                        lastObservedRemoteGeneration = artifact.lineage.remoteGeneration,
                        lastObservedRemoteDigest = artifact.lineage.remoteDigest,
                        lastObservedSourceInstallationId = artifact.lineage.sourceInstallationId,
                        lastObservedRemoteCompletedAt = artifact.lineage.completedAt,
                    ),
                    artifact.bundle.copy(localChangeRevision = revision),
                    remoteArtifact,
                    null,
                )
            writes++
            return true
        }

        override suspend fun captureUndo(
            accountId: AccountId,
            installationId: String,
        ): ManualBackupUndoCapture? =
            undoSlot?.takeIf {
                it.restoringOwnerUid == accountId.opaqueValue &&
                    it.restoringInstallationId == installationId
            }

        override suspend fun undo(
            captured: ManualBackupCapture,
            accountId: AccountId,
            undo: ManualBackupUndoCapture,
        ): Boolean {
            if (
                undoSlot != undo ||
                    captured.metadata != value.metadata ||
                    captured.activeSessionId != null ||
                    value.activeSessionId != null
            )
                return false
            val revision = Math.addExact(value.metadata.localChangeRevision, 1)
            val prior = undo.previousMetadata
            value =
                ManualBackupCapture(
                    prior.copy(
                        installationId = captured.metadata.installationId,
                        localChangeRevision = revision,
                        lastObservedRemoteBackupId = prior.lastObservedRemoteBackupId,
                        lastObservedRemoteGeneration = prior.lastObservedRemoteGeneration,
                        lastObservedRemoteDigest = prior.lastObservedRemoteDigest,
                        lastObservedSourceInstallationId = prior.lastObservedSourceInstallationId,
                        lastObservedRemoteCompletedAt = prior.lastObservedRemoteCompletedAt,
                        requiresLineageReviewAfterUndo = true,
                    ),
                    undo.bundle.copy(localChangeRevision = revision),
                    undo.baseline,
                    null,
                )
            undoSlot = null
            writes++
            return true
        }

        private fun RestoreLineage.toSummary(counts: Map<String, Int>) =
            RemoteBackupSummary(remoteBackupId, completedAt, sourceInstallationId, counts)
    }

    private class Cloud : RemoteBackupStore {
        var artifact: RemoteBackupArtifact? = null
        var generation = 0L
        var writes = 0
        var reads = 0
        var failure: BackupFailureReason? = null
        var readFailure: BackupFailureReason? = null
        var throwOnRead = false
        var onPublish: suspend () -> Unit = {}
        var onRead: suspend () -> Unit = {}

        fun change(bundle: BackupBundle) {
            generation++
            val snapshot = BackupSnapshotCodec().encode(bundle)
            artifact =
                RemoteBackupArtifact(
                    RemoteBackupSummary(
                        "remote-$generation",
                        200,
                        "other-installation",
                        snapshot.entityCounts
                    ),
                    generation,
                    snapshot
                )
        }

        override suspend fun latest(accountId: AccountId): RemoteBackupRead {
            reads++
            onRead()
            if (throwOnRead) error("private diagnostic must not escape")
            readFailure?.let {
                return RemoteBackupRead.Failed(it)
            }
            return artifact?.let { RemoteBackupRead.Complete(it) }
                ?: RemoteBackupRead.Absent(generation)
        }

        override suspend fun publish(
            accountId: AccountId,
            expectedGeneration: Long,
            sourceInstallationId: String,
            snapshot: EncodedBackupSnapshot
        ): RemoteBackupPublish {
            failure?.let {
                return RemoteBackupPublish.Failed(it)
            }
            if (expectedGeneration != generation)
                return RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange)
            writes++
            generation++
            onPublish()
            return RemoteBackupPublish.Completed(
                RemoteBackupArtifact(
                        RemoteBackupSummary(
                            "backup-$generation",
                            100,
                            sourceInstallationId,
                            snapshot.entityCounts
                        ),
                        generation,
                        snapshot
                    )
                    .also { artifact = it }
            )
        }
    }

    private class Session : AccountSessionAdapter {
        private val defaultProfile =
            AccountProfile(AccountId("owner"), "Demo", "demo@example.invalid")
        var profile: AccountProfile? = defaultProfile
        var credentialProfile: AccountProfile = defaultProfile
        var clearCalls = 0
        var onClear: () -> Unit = {}

        override suspend fun readSession() = profile

        override suspend fun requestGoogleCredential() =
            CredentialResult.Selected(credentialProfile)

        override suspend fun saveSession(profile: AccountProfile): Boolean {
            this.profile = profile
            return true
        }

        override suspend fun clearSession(): Boolean {
            clearCalls++
            onClear()
            profile = null
            return true
        }

        override suspend fun remoteSnapshot(accountId: AccountId) = RemoteSnapshotPresence.Absent
    }

    private suspend fun signedInGateway(fixture: Fixture): PersistedAccountGateway {
        val profile = fixture.session.credentialProfile
        fixture.local.value =
            fixture.local.value.copy(
                metadata = fixture.local.value.metadata.copy(ownerUid = profile.id.opaqueValue)
            )
        val reader =
            object : AccountContextReader {
                override val changes = emptyFlow<Unit>()

                override suspend fun read(): LocalAccountContext {
                    val metadata = fixture.local.value.metadata
                    return LocalAccountContext(
                        ownerUid = metadata.ownerUid,
                        localDataIsEmpty = false,
                        conflict =
                            PersistedConflictContext(
                                lastObservedRemoteBackupId = metadata.lastObservedRemoteBackupId,
                                lastObservedRemoteGeneration =
                                    metadata.lastObservedRemoteGeneration,
                                lastObservedRemoteDigest = metadata.lastObservedRemoteDigest,
                                lastObservedSourceInstallationId =
                                    metadata.lastObservedSourceInstallationId,
                                currentInstallationId = metadata.installationId,
                                localChangeRevision = metadata.localChangeRevision,
                                lastCompleteLocalRevision = metadata.lastCompleteLocalRevision,
                            ),
                    )
                }
            }
        val gateway =
            PersistedAccountGateway(
                fixture.session,
                reader,
                fixtureGuard(),
                object : LocalProfileResetter {
                    override suspend fun resetLocalProfile(
                        pendingSignOutUid: String?
                    ): LocalProfileResetResult =
                        error("Keep-data sign-out must not reset local data")

                    override suspend fun clearPendingSignOut(uid: String) = false
                },
                fixture.gate,
            )
        assertEquals(AccountActionResult.Completed, gateway.refreshLocal())
        return gateway
    }

    private fun fixtureGuard() =
        object : InstallationGuard {
            override suspend fun validate() = InstallationValidationResult.Validated
        }

    companion object {
        private fun snapshotChoice(artifact: RemoteBackupArtifact) =
            RemoteSnapshotPresence.Complete(
                artifact.summary.backupId,
                artifact.generation,
                artifact.summary.sourceInstallationId,
                artifact.snapshot.contentDigest,
            )

        private fun record(id: String) =
            PersonalRecord(id, id, id, 50.0, "2026-09-18", createdAt = 1)

        private fun bundle(records: List<PersonalRecord>) =
            BackupBundle(
                1,
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                records
            )
    }
}
