package com.example.ironpath.ui.screens.accountbackup

import com.example.ironpath.domain.account.*
import com.example.ironpath.domain.backup.*
import com.example.ironpath.util.MainDispatcherRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualBackupViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `failed explicit lookup retains the typed failure instead of cached up to date`() =
        runTest {
            val f = Fixture()
            advanceUntilIdle()
            f.backup.refreshStatusValue = BackupStatus.UpToDate(123L)
            f.backup.lookup = BackupLookupResult.Failed(BackupFailureReason.Offline)
            f.vm.refresh()
            advanceUntilIdle()
            assertEquals(BackupStatus.OfflinePending, f.vm.manual.value.status)
            assertTrue(f.vm.manual.value.feedback!!.contains("Offline"))
        }

    @Test
    fun `opening and refreshing do not run a manual operation`() = runTest {
        val fixture = Fixture()
        advanceUntilIdle()
        fixture.vm.refresh()
        advanceUntilIdle()
        assertEquals(0, fixture.backup.previews)
        assertEquals(0, fixture.backup.confirmations)
        assertNull(fixture.vm.manual.value.review)
    }

    @Test
    fun `back from preview invalidates it without cancelling account or leaving`() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.vm.previewBackup()
        advanceUntilIdle()
        var exits = 0
        f.vm.leave { exits++ }
        advanceUntilIdle()
        assertNull(f.vm.manual.value.review)
        assertEquals(listOf("backup"), f.backup.discarded)
        assertEquals(0, f.account.cancels)
        assertEquals(0, exits)
        assertEquals(0, f.backup.confirmations)
        f.vm.leave { exits++ }
        advanceUntilIdle()
        assertEquals(1, f.account.cancels)
        assertEquals(1, exits)
    }

    @Test
    fun `conflict choice is explicit exclusive and reset for the next preview`() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.vm.previewSync()
        advanceUntilIdle()
        f.vm.confirm()
        advanceUntilIdle()
        assertEquals(0, f.backup.confirmations)
        f.vm.selectResolution(SyncConflictResolution.KeepLocal)
        f.vm.selectResolution(SyncConflictResolution.KeepCloud)
        assertEquals(SyncConflictResolution.KeepCloud, f.vm.manual.value.resolution)
        f.vm.confirm()
        advanceUntilIdle()
        assertEquals(SyncConflictResolution.KeepCloud, f.backup.resolution)
        assertNull(f.vm.manual.value.review)
        f.vm.previewSync()
        advanceUntilIdle()
        assertNull(f.vm.manual.value.resolution)
        assertNull(f.vm.manual.value.feedback)
    }

    @Test
    fun `destructive backup requires acknowledgement and duplicate confirm is serialized`() =
        runTest {
            val f = Fixture()
            f.backup.backupPreview =
                f.backup.backupPreview.copy(requiresDestructiveConfirmation = true)
            advanceUntilIdle()
            f.vm.previewBackup()
            advanceUntilIdle()
            f.vm.confirm()
            advanceUntilIdle()
            assertEquals(0, f.backup.confirmations)
            f.vm.confirmDestructive(true)
            f.backup.block = CompletableDeferred()
            f.vm.confirm()
            f.vm.confirm()
            runCurrent()
            assertEquals(1, f.backup.confirmations)
            var exits = 0
            f.vm.leave { exits++ }
            f.vm.previewSync()
            runCurrent()
            assertEquals(0, exits)
            assertEquals(0, f.account.cancels)
            assertEquals(1, f.backup.previews)
            f.backup.block!!.complete(Unit)
            advanceUntilIdle()
            assertFalse(f.vm.manual.value.busy)
            assertTrue(f.backup.destructive)
        }

    @Test
    fun `stale failure removes review and next preview clears feedback`() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.vm.previewBackup()
        advanceUntilIdle()
        f.backup.result = BackupActionResult.Failed(BackupFailureReason.StalePreview)
        f.vm.confirm()
        advanceUntilIdle()
        assertNull(f.vm.manual.value.review)
        assertTrue(f.vm.manual.value.feedback!!.contains("fresh preview"))
        f.vm.previewBackup()
        advanceUntilIdle()
        assertNull(f.vm.manual.value.feedback)
        assertNotNull(f.vm.manual.value.review)
    }

    @Test
    fun `new view model has no replayable preview but reads persisted status`() = runTest {
        val f = Fixture()
        advanceUntilIdle()
        f.backup.status.value = BackupStatus.UpToDate(123L)
        f.vm.previewBackup()
        advanceUntilIdle()
        val recreated = AccountBackupViewModel(f.account, Context(), f.backup)
        advanceUntilIdle()
        assertNull(recreated.manual.value.review)
        assertEquals(BackupStatus.UpToDate(123L), recreated.manual.value.status)
        recreated.confirm()
        advanceUntilIdle()
        assertEquals(0, f.backup.confirmations)
    }

    private class Fixture {
        val account = Account()
        val backup = Backup()
        val vm = AccountBackupViewModel(account, Context(), backup)
    }

    private class Context : AccountContextReader {
        override val changes = emptyFlow<Unit>()

        override suspend fun read(): LocalAccountContext = error("unused")
    }

    private class Account : AccountGateway {
        override val state =
            MutableStateFlow<AccountState>(
                AccountState.AwaitingDataChoice(
                    AccountId("test"),
                    DataChoiceContext(
                        LocalOwnership.Unclaimed,
                        false,
                        RemoteSnapshotPresence.Absent,
                        null
                    )
                )
            )
        var cancels = 0

        override suspend fun refresh() = AccountActionResult.Completed

        override suspend fun startGoogleSignIn() = AccountActionResult.Completed

        override suspend fun cancelDataChoice(): AccountActionResult {
            cancels++
            state.value = AccountState.LocalOnly
            return AccountActionResult.Completed
        }

        override suspend fun reauthenticate() = AccountActionResult.Unavailable

        override suspend fun signOut() = AccountActionResult.Unavailable

        override suspend fun deleteAccount() = AccountActionResult.Unavailable
    }

    private class Backup : BackupCoordinator {
        override val status = MutableStateFlow<BackupStatus>(BackupStatus.SignedInNoBackup)
        override val latestSummary = MutableStateFlow<RemoteBackupSummary?>(null)
        var refreshStatusValue: BackupStatus? = null
        var lookup: BackupLookupResult = BackupLookupResult.Unavailable

        override suspend fun refreshStatus() {
            refreshStatusValue?.let { status.value = it }
        }

        var previews = 0
        var confirmations = 0
        var resolution: SyncConflictResolution? = null
        var destructive = false
        var block: CompletableDeferred<Unit>? = null
        var result: BackupActionResult = BackupActionResult.Completed
        val discarded = mutableListOf<String>()
        var backupPreview =
            BackupPreview("backup", 1L, 0L, mapOf("WorkoutLog" to 1), 0, false, false)

        override suspend fun previewBackup(): BackupPreviewResult {
            previews++
            return BackupPreviewResult.Ready(backupPreview)
        }

        override suspend fun previewSync(): SyncPreviewResult {
            previews++
            return SyncPreviewResult.Ready(
                SyncPreview("sync", 1L, 1L, emptyMap(), emptyMap(), mapOf("WorkoutLog" to 1))
            )
        }

        override suspend fun discardPreview(previewId: String) {
            discarded += previewId
        }

        override suspend fun confirmBackup(
            previewId: String,
            destructiveConfirmed: Boolean
        ): BackupActionResult {
            confirmations++
            destructive = destructiveConfirmed
            block?.await()
            return result
        }

        override suspend fun confirmSync(
            previewId: String,
            resolution: SyncConflictResolution?
        ): BackupActionResult {
            confirmations++
            this.resolution = resolution
            block?.await()
            return result
        }

        override suspend fun backUpNow() = BackupActionResult.Unavailable

        override suspend fun latestCompleteBackup(): BackupLookupResult {
            if (lookup is BackupLookupResult.Failed) status.value = BackupStatus.OfflinePending
            return lookup
        }

        override suspend fun restore(request: RestoreRequest) = BackupActionResult.Unavailable

        override suspend fun deleteAllRemoteData() = BackupActionResult.Unavailable
    }
}
