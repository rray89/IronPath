package com.example.ironpath.data.backup

import com.example.ironpath.data.account.AccountSessionOperationGate
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.domain.account.*
import com.example.ironpath.domain.backup.*
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.ui.screens.accountbackup.AccountBackupViewModel
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudManualBackupCoordinatorTest {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun syncCancelAndProcessRecreationNeverMutateEitherSideOrClaimUnownedData() = runTest {
        val f = Fixture()
        f.remote.current = f.remote.artifact(BackupSnapshotCodec().encode(f.local.value.bundle))
        val before = f.local.value
        val remote = f.remote.current
        val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
        f.subject.discardPreview(preview.id)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            f.subject.confirmSync(preview.id)
        )
        val fresh = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            f.newSubject().confirmSync(fresh.id)
        )
        assertEquals(before, f.local.value)
        assertEquals(remote, f.remote.current)
        assertEquals(0, f.remote.publishes)
    }

    @Test
    fun oneSidedChangesMergeInBothExplicitConflictOutcomes() = runTest {
        for (choice in SyncConflictResolution.entries) {
            val f = Fixture()
            f.seedBaseline()
            val base = f.local.value.bundle
            val shared = base.personalRecords.single()
            f.local.value =
                f.local.value.copy(
                    metadata = f.local.value.metadata.copy(localChangeRevision = 2),
                    bundle =
                        base.copy(
                            localChangeRevision = 2,
                            personalRecords =
                                listOf(
                                    shared.copy(weightKg = 60.0),
                                    shared.copy(
                                        id = "local-only",
                                        exerciseName = "Press",
                                        normalizedExerciseName = "press"
                                    )
                                )
                        )
                )
            f.remote.current =
                f.remote
                    .artifact(
                        BackupSnapshotCodec()
                            .encode(
                                base.copy(
                                    personalRecords =
                                        listOf(
                                            shared.copy(weightKg = 70.0),
                                            shared.copy(
                                                id = "cloud-only",
                                                exerciseName = "Row",
                                                normalizedExerciseName = "row"
                                            )
                                        )
                                )
                            )
                    )
                    .copy(generation = 2)
            val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
            assertEquals(1, preview.conflicts["PersonalRecord"])
            assertEquals(BackupActionResult.Completed, f.subject.confirmSync(preview.id, choice))
            assertEquals(
                setOf("record", "local-only", "cloud-only"),
                f.local.value.bundle.personalRecords.map { it.id }.toSet()
            )
            assertEquals(
                if (choice == SyncConflictResolution.KeepLocal) 60.0 else 70.0,
                f.local.value.bundle.personalRecords.first { it.id == "record" }.weightKg,
                0.0
            )
            assertEquals(
                BackupSnapshotCodec().encode(f.local.value.bundle).contentDigest,
                f.remote.current!!.snapshot.contentDigest
            )
        }
    }

    @Test
    fun localCloudProfileAndEpochChangesInvalidateSyncWithoutPublication() = runTest {
        for (change in 0..4) {
            val f = Fixture()
            f.seedBaseline()
            val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
            when (change) {
                0 -> f.local.edit()
                1 -> f.remote.current = f.remote.current!!.copy(generation = 2)
                2 ->
                    f.local.value =
                        f.local.value.copy(
                            metadata = f.local.value.metadata.copy(profileGeneration = 1)
                        )
                3 -> f.local.value = f.local.value.copy(activeSessionId = "active")
                4 ->
                    f.gate.withSessionMutation { _, _ ->
                        AccountSessionOperationGate.MutationResult(Unit, true)
                    }
            }
            val before = f.local.value
            assertEquals(
                BackupActionResult.Failed(BackupFailureReason.StalePreview),
                f.subject.confirmSync(preview.id)
            )
            assertEquals(before, f.local.value)
            assertEquals(1, f.remote.publishes)
        }
    }

    @Test
    fun accountSwitchDuringPayloadReadOrPublishCannotApplyOldAccountData() = runTest {
        for (duringPublish in listOf(false, true)) {
            val f = Fixture()
            f.seedBaseline()
            f.local.edit()
            val switch: suspend () -> Unit = {
                f.session.profile = AccountProfile(AccountId("other"), "Other", "")
            }
            if (!duringPublish) {
                f.remote.onPayload = switch
                assertEquals(
                    SyncPreviewResult.Failed(BackupFailureReason.ReauthenticationRequired),
                    f.subject.previewSync()
                )
            } else {
                val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
                val before = f.local.value
                f.remote.onPublished = switch
                assertEquals(
                    BackupActionResult.Failed(BackupFailureReason.ReauthenticationRequired),
                    f.subject.confirmSync(preview.id)
                )
                assertEquals(before, f.local.value)
            }
            assertNull(f.subject.latestSummary.value)
            assertEquals(
                SyncPreviewResult.Failed(BackupFailureReason.OwnershipMismatch),
                f.subject.previewSync()
            )
        }
    }

    @Test
    fun failedLocalApplyPreservesOldBaselineAndRequiresReviewAfterRestart() = runTest {
        for (throwFailure in listOf(false, true)) {
            val f = Fixture()
            f.seedBaseline()
            f.local.edit()
            val old = f.local.value
            val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
            f.local.applyAllowed = false
            f.local.applyThrows = throwFailure
            val expected =
                if (throwFailure) BackupFailureReason.ServiceUnavailable
                else BackupFailureReason.StalePreview
            assertEquals(BackupActionResult.Failed(expected), f.subject.confirmSync(preview.id))
            assertEquals(old, f.local.value)
            assertEquals(2L, f.remote.current!!.generation)
            f.local.applyAllowed = true
            f.local.applyThrows = false
            val restarted = f.newSubject()
            val retry = (restarted.previewSync() as SyncPreviewResult.Ready).preview
            assertEquals(BackupActionResult.Completed, restarted.confirmSync(retry.id))
            assertEquals(2, f.remote.publishes)
            assertTrue(restarted.status.value is BackupStatus.UpToDate)
        }
    }

    @Test
    fun lostSyncReceiptKeepsOfflineEditsAndFreshReviewRecoversWithoutBlindReplay() = runTest {
        val f = Fixture()
        f.seedBaseline()
        f.local.edit()
        val old = f.local.value
        val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
        f.remote.loseReceipt = true
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.Offline),
            f.subject.confirmSync(preview.id)
        )
        assertEquals(old, f.local.value)
        f.subject.refreshStatus()
        assertEquals(BackupStatus.OfflinePending, f.subject.status.value)
        val restarted = f.newSubject()
        val retry = (restarted.previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, restarted.confirmSync(retry.id))
        assertEquals(2, f.remote.publishes)
    }

    @Test
    fun localWriteDuringCloudCommitSurvivesAndNextPreviewShowsTheConflict() = runTest {
        val f = Fixture()
        f.seedBaseline()
        f.local.edit()
        val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
        val baseline = f.local.value.baseline
        f.remote.onPublished = {
            f.local.value =
                f.local.value.copy(
                    metadata = f.local.value.metadata.copy(localChangeRevision = 3),
                    bundle =
                        f.local.value.bundle.copy(
                            localChangeRevision = 3,
                            personalRecords = f.local.records.map { it.copy(weightKg = 80.0) }
                        )
                )
        }
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            f.subject.confirmSync(preview.id)
        )
        assertEquals(80.0, f.local.value.bundle.personalRecords.single().weightKg, 0.0)
        assertEquals(baseline, f.local.value.baseline)
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.newSubject().previewBackup()
        )
        val retry = (f.newSubject().previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(1, retry.conflicts["PersonalRecord"])
    }

    @Test
    fun cancellationAfterSyncCommitAndDuplicateConfirmationNeverPartiallyApply() = runTest {
        val f = Fixture()
        f.seedBaseline()
        f.local.edit()
        val before = f.local.value
        val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
        val committed = CompletableDeferred<Unit>()
        val receipt = CompletableDeferred<Unit>()
        f.remote.onPublished = {
            committed.complete(Unit)
            receipt.await()
        }
        val confirmation = async { f.subject.confirmSync(preview.id) }
        committed.await()
        assertEquals(BackupActionResult.Unavailable, f.subject.confirmSync(preview.id))
        confirmation.cancel()
        confirmation.join()
        assertEquals(before, f.local.value)
        assertEquals(2, f.remote.publishes)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            f.subject.confirmSync(preview.id)
        )
        val retry = (f.newSubject().previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(0, retry.conflicts.values.sum())
    }

    @Test
    fun activeWorkoutAndForeignOwnerBlockPayloadReadsAndLocalChangesDuringInspectionBlockPublish() =
        runTest {
            val f = Fixture()
            f.local.value = f.local.value.copy(activeSessionId = "active")
            assertEquals(
                SyncPreviewResult.Failed(BackupFailureReason.ActiveSessionPresent),
                f.subject.previewSync()
            )
            f.local.value =
                f.local.value.copy(
                    activeSessionId = null,
                    metadata = f.local.value.metadata.copy(ownerUid = "other")
                )
            assertEquals(
                SyncPreviewResult.Failed(BackupFailureReason.OwnershipMismatch),
                f.subject.previewSync()
            )
            assertEquals(0, f.remote.payloadReads)
            val valid = Fixture()
            valid.seedBaseline()
            val preview = (valid.subject.previewSync() as SyncPreviewResult.Ready).preview
            valid.remote.onInspection = { valid.local.edit() }
            assertEquals(
                BackupActionResult.Failed(BackupFailureReason.StalePreview),
                valid.subject.confirmSync(preview.id)
            )
            assertEquals(1, valid.remote.publishes)
        }

    @Test
    fun realSyncPreviewsConflictsAndAppliesOnlyTheExplicitChoice() = runTest {
        val f = Fixture()
        val first = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.subject.confirmBackup(first.id)
        f.local.edit()
        val cloud =
            f.local.value.bundle.copy(
                personalRecords = f.local.records.map { it.copy(weightKg = 70.0) }
            )
        f.remote.current =
            f.remote.artifact(BackupSnapshotCodec().encode(cloud)).copy(generation = 2)
        val before = f.local.value
        val preview = (f.subject.previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(1, preview.conflicts["PersonalRecord"])
        assertEquals(before, f.local.value)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.ConflictChoiceRequired),
            f.subject.confirmSync(preview.id)
        )
        assertEquals(
            BackupActionResult.Completed,
            f.subject.confirmSync(preview.id, SyncConflictResolution.KeepCloud)
        )
        assertEquals(70.0, f.local.value.bundle.personalRecords.single().weightKg, 0.0)
        assertEquals(1, f.remote.publishes)
        assertTrue(f.subject.status.value is BackupStatus.UpToDate)
    }

    @Test
    fun unknownNewerSameInstallationMergeCannotBeOverwrittenByBackup() = runTest {
        val f = Fixture()
        val first = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.subject.confirmBackup(first.id)
        val merged =
            f.local.value.bundle.copy(
                personalRecords = f.local.records.map { it.copy(weightKg = 70.0) }
            )
        f.remote.current =
            f.remote.artifact(BackupSnapshotCodec().encode(merged)).copy(generation = 2)
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.subject.previewBackup()
        )
        assertEquals(1, f.remote.publishes)
    }

    @Test
    fun ownerInvalidationAndPageReentryPreserveUnknownReceiptUntilExplicitRefresh() = runTest {
        val f = Fixture()
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val gateway = mockk<AccountGateway>()
        every { gateway.state } returns
            MutableStateFlow<AccountState>(AccountState.SignedIn(AccountId("owner")))
        coEvery { gateway.refreshLocal() } returns AccountActionResult.Completed
        coEvery { gateway.refresh() } returns AccountActionResult.Completed
        val reader =
            object : AccountContextReader {
                override val changes = changes

                override suspend fun read(): LocalAccountContext = error("Gateway owns local reads")
            }
        fun viewModel() =
            AccountBackupViewModel(
                gateway,
                reader,
                f.subject,
                AccountExperienceCapabilities.AuthPreview.copy(canUseBackup = true)
            )
        val first = viewModel()
        advanceUntilIdle()
        f.local.onAssociation = { changes.emit(Unit) }
        f.remote.loseReceipt = true
        first.previewBackup()
        advanceUntilIdle()
        first.confirm()
        advanceUntilIdle()
        assertEquals("owner", f.local.value.metadata.ownerUid)
        assertEquals(BackupStatus.OfflinePending, first.manual.value.status)
        assertEquals(2, f.remote.reads)
        var left = 0
        first.leave { left++ }
        advanceUntilIdle()
        assertEquals(1, left)
        assertEquals(BackupStatus.OfflinePending, first.manual.value.status)
        val reopened = viewModel()
        advanceUntilIdle()
        assertEquals(BackupStatus.OfflinePending, reopened.manual.value.status)
        reopened.refresh()
        advanceUntilIdle()
        assertEquals(3, f.remote.reads)
        assertEquals(BackupStatus.ReviewRequired, reopened.manual.value.status)
        assertNotNull(reopened.manual.value.latest)
    }

    @Test
    fun cancelledConfirmationAfterCloudCommitKeepsUnknownReceiptUntilExplicitRecovery() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        val committed = CompletableDeferred<RemoteBackupArtifact>()
        val receipt = CompletableDeferred<Unit>()
        f.remote.onPublished = {
            committed.complete(f.remote.current!!)
            receipt.await()
        }
        val confirmation = async { f.subject.confirmBackup(preview.id) }
        val complete = committed.await()

        confirmation.cancel()
        confirmation.join()

        assertTrue(confirmation.isCancelled)
        assertEquals("owner", f.local.value.metadata.ownerUid)
        assertNull(f.local.value.baseline)
        val unknown = BackupStatus.NeedsAttention(BackupFailureReason.ServiceUnavailable)
        assertEquals(unknown, f.subject.status.value)
        f.subject.refreshStatus()
        assertEquals(unknown, f.subject.status.value)
        assertEquals(2, f.remote.reads)
        assertEquals(complete, f.remote.current)

        assertEquals(
            BackupLookupResult.Complete(complete.summary),
            f.subject.latestCompleteBackup(),
        )
        assertEquals(BackupStatus.ReviewRequired, f.subject.status.value)
        val retry = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, f.subject.confirmBackup(retry.id))
        assertEquals(1, f.remote.publishes)
        assertEquals(complete, f.remote.current)
        assertEquals(complete, f.local.value.baseline)
        assertTrue(f.subject.status.value is BackupStatus.UpToDate)
    }

    @Test
    fun accountChangeDuringStatusInspectionClearsOldSummaryAndCannotClaimItsData() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, f.subject.confirmBackup(preview.id))
        val localBefore = f.local.value
        val remoteBefore = f.remote.current
        assertNotNull(f.subject.latestSummary.value)
        f.remote.onInspection = {
            f.session.profile = AccountProfile(AccountId("other"), "Other", "")
        }

        assertEquals(
            BackupLookupResult.Failed(BackupFailureReason.ReauthenticationRequired),
            f.subject.latestCompleteBackup(),
        )
        assertEquals(BackupStatus.NeedsSignIn, f.subject.status.value)
        assertNull(f.subject.latestSummary.value)
        assertEquals(localBefore, f.local.value)
        assertEquals(remoteBefore, f.remote.current)
        assertEquals(1, f.remote.publishes)
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.OwnershipMismatch),
            f.subject.previewBackup(),
        )
        assertEquals(localBefore, f.local.value)
        assertEquals(remoteBefore, f.remote.current)
        assertEquals(1, f.remote.publishes)
    }

    @Test
    fun localRefreshNeverReadsOrPublishesCloud() = runTest {
        val f = Fixture()
        f.subject.refreshStatus()
        assertEquals(BackupStatus.SignedInNoBackup, f.subject.status.value)
        assertEquals(0, f.remote.reads)
        assertEquals(0, f.remote.publishes)
        assertEquals(SyncPreviewResult.Unavailable, f.subject.previewSync())
        assertEquals(RestorePreviewResult.Unavailable, f.subject.previewRestore())
        assertEquals(BackupActionResult.Unavailable, f.subject.deleteAllRemoteData())
    }

    @Test
    fun previewAndCancellationDoNotAssociateOrWrite() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(1, preview.entityCounts["PersonalRecord"])
        assertNull(f.local.value.metadata.ownerUid)
        assertEquals(0, f.remote.publishes)
        f.subject.discardPreview(preview.id)
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            f.subject.confirmBackup(preview.id)
        )
    }

    @Test
    fun confirmedBackupDurablyAssociatesBeforeUploadAndTracksRevision() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, f.subject.confirmBackup(preview.id))
        assertEquals("owner", f.local.value.metadata.ownerUid)
        assertTrue(f.subject.status.value is BackupStatus.UpToDate)
        assertEquals(1, f.remote.publishes)
        val next = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, f.subject.confirmBackup(next.id))
        assertEquals(1, f.remote.publishes)
        assertEquals(0, f.remote.payloadReads)
    }

    @Test
    fun emptyUnclaimedProfileAssociatesWithoutEmptyCloudSnapshot() = runTest {
        val f = Fixture(empty = true)
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        assertTrue(preview.associationOnly)
        assertEquals(BackupActionResult.Completed, f.subject.confirmBackup(preview.id))
        assertEquals("owner", f.local.value.metadata.ownerUid)
        assertEquals(0, f.remote.publishes)
        assertEquals(BackupStatus.SignedInNoBackup, f.subject.status.value)
    }

    @Test
    fun staleLocalPreviewAndForeignOwnerFailClosed() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.local.value =
            f.local.value.copy(metadata = f.local.value.metadata.copy(localChangeRevision = 2))
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            f.subject.confirmBackup(preview.id)
        )
        f.local.value =
            f.local.value.copy(metadata = f.local.value.metadata.copy(ownerUid = "other"))
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.OwnershipMismatch),
            f.subject.previewBackup()
        )
        assertEquals(0, f.remote.publishes)
    }

    @Test
    fun existingCloudNeverLetsUnclaimedDataOverwriteIt() = runTest {
        val f = Fixture()
        f.remote.current = f.remote.artifact(BackupSnapshotCodec().encode(f.local.value.bundle))
        assertTrue(f.subject.latestCompleteBackup() is BackupLookupResult.Complete)
        assertEquals(BackupStatus.ReviewRequired, f.subject.status.value)
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.subject.previewBackup()
        )
        assertEquals(0, f.remote.publishes)
        assertNull(f.local.value.metadata.ownerUid)
    }

    @Test
    fun failedPublicationPreservesLocalOwnershipAndMapsQuota() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.remote.failure = BackupFailureReason.QuotaOrRateLimited
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.QuotaOrRateLimited),
            f.subject.confirmBackup(preview.id)
        )
        assertEquals("owner", f.local.value.metadata.ownerUid)
        assertEquals(BackupStatus.QuotaPaused, f.subject.status.value)
    }

    @Test
    fun lostFirstCompletionReceiptCanBeExplicitlyAcknowledgedAfterCoordinatorRestart() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.remote.loseReceipt = true
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.Offline),
            f.subject.confirmBackup(preview.id)
        )
        assertEquals("owner", f.local.value.metadata.ownerUid)
        assertNull(f.local.value.baseline)
        f.subject.refreshStatus()
        assertEquals(BackupStatus.OfflinePending, f.subject.status.value)
        assertEquals(2, f.remote.reads)
        val restarted = f.newSubject()
        assertTrue(restarted.latestCompleteBackup() is BackupLookupResult.Complete)
        assertEquals(BackupStatus.ReviewRequired, restarted.status.value)
        val retry = (restarted.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, restarted.confirmBackup(retry.id))
        assertEquals(1, f.remote.publishes)
        assertTrue(restarted.status.value is BackupStatus.UpToDate)
    }

    @Test
    fun failedFirstBackupWithLaterLocalEditsRequiresFreshExplicitSyncAfterRestart() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.remote.loseReceipt = true
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.Offline),
            f.subject.confirmBackup(preview.id)
        )
        f.local.edit()
        val restarted = f.newSubject()
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            restarted.previewBackup()
        )
        val retry = (restarted.previewSync() as SyncPreviewResult.Ready).preview
        assertEquals(
            BackupActionResult.Completed,
            restarted.confirmSync(retry.id, SyncConflictResolution.KeepLocal)
        )
        assertEquals(2, f.remote.publishes)
        assertEquals(3L, f.local.value.metadata.lastCompleteLocalRevision)
        assertEquals(2L, f.local.value.metadata.lastObservedRemoteGeneration)
    }

    @Test
    fun clearProfileAndAnotherAccountCannotRecoverThePreviousProfilesReceipt() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.remote.loseReceipt = true
        f.subject.confirmBackup(preview.id)
        f.session.profile = AccountProfile(AccountId("other"), "Other", "")
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.OwnershipMismatch),
            f.newSubject().previewBackup()
        )
        f.session.profile = AccountProfile(AccountId("owner"), "Owner", "")
        f.local.value =
            f.local.value.copy(
                metadata =
                    AccountBackupMetadata(
                        installationId = "new-installation",
                        profileGeneration = 1,
                        localChangeRevision = 1
                    ),
                baseline = null
            )
        assertEquals(
            BackupPreviewResult.Failed(BackupFailureReason.ConcurrentRemoteChange),
            f.newSubject().previewBackup()
        )
        assertEquals(1, f.remote.publishes)
    }

    @Test
    fun cloudChangeAfterPreviewAndAssociationRaceNeverUpload() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.remote.current = f.remote.artifact(BackupSnapshotCodec().encode(f.local.value.bundle))
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            f.subject.confirmBackup(preview.id)
        )
        assertNull(f.local.value.metadata.ownerUid)
        assertEquals(0, f.remote.publishes)
        val fresh = Fixture()
        val second = (fresh.subject.previewBackup() as BackupPreviewResult.Ready).preview
        fresh.local.associateAllowed = false
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.StalePreview),
            fresh.subject.confirmBackup(second.id)
        )
        assertEquals(0, fresh.remote.publishes)
    }

    @Test
    fun unchangedConfirmedBackupRetriesRetentionWithoutCreatingAnotherGeneration() = runTest {
        val f = Fixture()
        val first = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.subject.confirmBackup(first.id)
        val retry = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.remote.retentionFailure = BackupFailureReason.Offline
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.Offline),
            f.subject.confirmBackup(retry.id)
        )
        f.subject.refreshStatus()
        assertEquals(BackupStatus.OfflinePending, f.subject.status.value)
        assertEquals(1, f.remote.publishes)
        f.remote.retentionFailure = null
        val next = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, f.subject.confirmBackup(next.id))
        assertEquals(2, f.remote.retentions)
        assertEquals(1, f.remote.publishes)
        assertEquals(1L, f.local.value.metadata.lastObservedRemoteGeneration)
    }

    private class Fixture(empty: Boolean = false) {
        val local = Local(empty)
        val remote = Remote()
        val session = Session()
        val gate = AccountSessionOperationGate()
        val subject = newSubject()

        fun newSubject() =
            CloudManualBackupCoordinator(
                local,
                remote,
                session,
                object : InstallationGuard {
                    override suspend fun validate() = InstallationValidationResult.Validated
                },
                IdProvider { "preview" },
                gate,
                Dispatchers.Unconfined
            )

        suspend fun seedBaseline() {
            val preview = (subject.previewBackup() as BackupPreviewResult.Ready).preview
            assertEquals(BackupActionResult.Completed, subject.confirmBackup(preview.id))
        }
    }

    private class Session : AccountSessionAdapter {
        var profile: AccountProfile? = AccountProfile(AccountId("owner"), "Owner", "")

        override suspend fun readSession() = profile

        override suspend fun requestGoogleCredential(requestId: Long) = CredentialResult.Cancelled

        override suspend fun commitGoogleCredential(candidate: PendingGoogleCredential) =
            CredentialCommitResult.Failed(AccountFailureReason.Unknown)

        override suspend fun clearSession(): Boolean {
            profile = null
            return true
        }

        override suspend fun remoteSnapshot(accountId: AccountId) = RemoteSnapshotPresence.Absent
    }

    private class Remote : RemoteBackupStore {
        var current: RemoteBackupArtifact? = null
        var reads = 0
        var payloadReads = 0
        var publishes = 0
        var failure: BackupFailureReason? = null
        var loseReceipt = false
        var retentions = 0
        var retentionFailure: BackupFailureReason? = null
        var onPublished: suspend () -> Unit = {}
        var onInspection: suspend () -> Unit = {}
        var onPayload: suspend () -> Unit = {}

        override suspend fun retryRetention(
            accountId: AccountId,
            expectedGeneration: Long,
            latestBackupId: String
        ): RemoteBackupRetention {
            retentions++
            return retentionFailure?.let { RemoteBackupRetention.Failed(it) }
                ?: RemoteBackupRetention.Completed
        }

        fun artifact(snapshot: EncodedBackupSnapshot) =
            RemoteBackupArtifact(
                RemoteBackupSummary("backup", 1000, "installation", snapshot.entityCounts),
                1,
                snapshot
            )

        override suspend fun inspect(accountId: AccountId): RemoteBackupInspection {
            reads++
            onInspection()
            return current?.let { RemoteBackupInspection.Complete(RemoteBackupMetadata.from(it)) }
                ?: RemoteBackupInspection.Absent()
        }

        override suspend fun latest(accountId: AccountId): RemoteBackupRead {
            payloadReads++
            onPayload()
            return current?.let { RemoteBackupRead.Complete(it) } ?: RemoteBackupRead.Absent()
        }

        override suspend fun publish(
            accountId: AccountId,
            expectedGeneration: Long,
            sourceInstallationId: String,
            snapshot: EncodedBackupSnapshot
        ): RemoteBackupPublish {
            publishes++
            failure?.let {
                return RemoteBackupPublish.Failed(it)
            }
            current = artifact(snapshot).copy(generation = expectedGeneration + 1)
            onPublished()
            if (loseReceipt) {
                loseReceipt = false
                return RemoteBackupPublish.Failed(BackupFailureReason.Offline)
            }
            return RemoteBackupPublish.Completed(current!!)
        }
    }

    private class Local(empty: Boolean) : ManualBackupLocalStore {
        var applyAllowed = true
        var applyThrows = false
        var associateAllowed = true
        var onAssociation: suspend () -> Unit = {}

        fun edit() {
            value =
                value.copy(
                    metadata = value.metadata.copy(localChangeRevision = 2),
                    bundle =
                        value.bundle.copy(
                            localChangeRevision = 2,
                            personalRecords =
                                value.bundle.personalRecords.map { it.copy(weightKg = 60.0) }
                        )
                )
        }

        val records =
            if (empty) emptyList()
            else
                listOf(
                    PersonalRecord("record", "Squat", "squat", 50.0, "2026-09-20", createdAt = 1)
                )
        var value =
            ManualBackupCapture(
                AccountBackupMetadata(installationId = "installation", localChangeRevision = 1),
                BackupBundle(
                    1,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    records
                ),
                null,
                null
            )

        override suspend fun capture() = value

        override suspend fun associateEmpty(
            captured: ManualBackupCapture,
            accountId: AccountId
        ): Boolean {
            value = value.copy(metadata = value.metadata.copy(ownerUid = accountId.opaqueValue))
            return true
        }

        override suspend fun associateForBackup(
            captured: ManualBackupCapture,
            accountId: AccountId
        ): Boolean {
            if (!associateAllowed || value != captured) return false
            value = value.copy(metadata = value.metadata.copy(ownerUid = accountId.opaqueValue))
            onAssociation()
            return true
        }

        override suspend fun recordBackup(
            captured: ManualBackupCapture,
            accountId: AccountId,
            backup: RemoteBackupArtifact
        ): Boolean {
            value =
                value.copy(
                    metadata =
                        value.metadata.copy(
                            ownerUid = accountId.opaqueValue,
                            lastCompleteLocalRevision = captured.metadata.localChangeRevision,
                            lastObservedRemoteGeneration = backup.generation
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
            if (applyThrows) throw IllegalStateException("injected local transaction failure")
            if (!applyAllowed) return false
            if (captured != value || value.activeSessionId != null) return false
            val revision = value.metadata.localChangeRevision + 1
            value =
                value.copy(
                    metadata =
                        value.metadata.copy(
                            ownerUid = accountId.opaqueValue,
                            localChangeRevision = revision,
                            lastCompleteLocalRevision = revision,
                            lastObservedRemoteGeneration = backup.generation
                        ),
                    bundle =
                        BackupSnapshotCodec()
                            .decode(backup.snapshot)
                            .copy(localChangeRevision = revision),
                    baseline = backup
                )
            return true
        }

        override suspend fun restore(
            captured: ManualBackupCapture,
            accountId: AccountId,
            artifact: ValidatedRestoreArtifact,
            discardActiveSessionId: String?
        ) = error("not allowed")

        override suspend fun captureUndo(
            accountId: AccountId,
            installationId: String
        ): ManualBackupUndoCapture? = null

        override suspend fun undo(
            captured: ManualBackupCapture,
            accountId: AccountId,
            undo: ManualBackupUndoCapture
        ) = error("not allowed")
    }
}
