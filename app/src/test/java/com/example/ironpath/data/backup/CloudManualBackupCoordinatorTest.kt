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
    fun failedFirstBackupCanBeExplicitlyRetriedAfterLocalEditsAndKeepsChangedRevision() = runTest {
        val f = Fixture()
        val preview = (f.subject.previewBackup() as BackupPreviewResult.Ready).preview
        f.remote.loseReceipt = true
        assertEquals(
            BackupActionResult.Failed(BackupFailureReason.Offline),
            f.subject.confirmBackup(preview.id)
        )
        f.local.edit()
        val restarted = f.newSubject()
        val retry = (restarted.previewBackup() as BackupPreviewResult.Ready).preview
        assertEquals(BackupActionResult.Completed, restarted.confirmBackup(retry.id))
        assertEquals(2, f.remote.publishes)
        assertEquals(2L, f.local.value.metadata.lastCompleteLocalRevision)
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
                AccountSessionOperationGate(),
                Dispatchers.Unconfined
            )
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
            return RemoteBackupRead.Absent()
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
        ) = error("not allowed")

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
