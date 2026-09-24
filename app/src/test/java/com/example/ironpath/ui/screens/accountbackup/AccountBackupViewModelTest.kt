package com.example.ironpath.ui.screens.accountbackup

import com.example.ironpath.domain.account.*
import com.example.ironpath.domain.backup.*
import com.example.ironpath.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountBackupViewModelTest {
    @get:Rule val dispatcherRule = MainDispatcherRule()

    @Test
    fun `initialization and retry refresh without starting sign in`() = runTest {
        val gateway = Gateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        assertEquals(1, gateway.refreshes)
        assertEquals(0, gateway.signIns)
        viewModel.refresh()
        viewModel.signIn()
        advanceUntilIdle()
        assertEquals(2, gateway.refreshes)
        assertEquals(1, gateway.signIns)
    }

    @Test
    fun `successful same-route sign in looks up latest backup and enables restore review`() =
        runTest {
            val gateway =
                Gateway().apply {
                    signInStateAfterSuccess = AccountState.SignedIn(AccountId("owner"))
                }
            val backup = Backup()
            val viewModel = viewModel(gateway, backup)
            advanceUntilIdle()
            assertNull(viewModel.manual.value.latest)
            assertEquals(0, backup.lookups)

            viewModel.signIn()
            advanceUntilIdle()

            assertEquals(2, backup.statusRefreshes)
            assertEquals(1, backup.lookups)
            assertEquals(backup.summary, viewModel.manual.value.latest)
            assertTrue(gateway.state.value is AccountState.SignedIn)
            viewModel.previewRestore()
            advanceUntilIdle()
            assertTrue(viewModel.manual.value.review is ManualReview.Restore)
        }

    @Test
    fun `back waits for successful cancellation and failure stays on screen`() = runTest {
        val gateway = Gateway()
        val viewModel = viewModel(gateway)
        advanceUntilIdle()
        var exits = 0
        gateway.state.value = AccountState.SigningIn
        gateway.cancelResult =
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable)
        viewModel.leave { exits++ }
        advanceUntilIdle()
        assertEquals(0, exits)
        gateway.cancelResult = AccountActionResult.Completed
        viewModel.leave { exits++ }
        advanceUntilIdle()
        assertEquals(1, exits)
        assertEquals(2, gateway.cancellations)
    }

    @Test
    fun `pending choice and cancellation failure both require cancellation but local navigation does not`() =
        runTest {
            val gateway = Gateway()
            val viewModel = viewModel(gateway)
            advanceUntilIdle()
            var exits = 0
            listOf(
                    AccountState.AwaitingDataChoice(
                        AccountId("a"),
                        DataChoiceContext(
                            LocalOwnership.Unclaimed,
                            true,
                            RemoteSnapshotPresence.Absent,
                            null
                        )
                    ),
                    AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable, true),
                )
                .forEach { state ->
                    gateway.state.value = state
                    viewModel.leave { exits++ }
                    advanceUntilIdle()
                }
            assertEquals(2, gateway.cancellations)
            gateway.state.value = AccountState.CancellingDataChoice
            viewModel.leave { exits++ }
            advanceUntilIdle()
            assertEquals(2, exits)
            gateway.state.value = AccountState.LocalOnly
            viewModel.leave { exits++ }
            advanceUntilIdle()
            assertEquals(3, exits)
            assertEquals(2, gateway.cancellations)
        }

    private fun viewModel(
        gateway: Gateway,
        backup: BackupCoordinator =
            com.example.ironpath.data.backup.LocalOnlyBackupCoordinator(
                object : com.example.ironpath.data.backup.InstallationGuard {
                    override suspend fun validate() =
                        com.example.ironpath.data.backup.InstallationValidationResult.Validated
                }
            ),
    ) =
        AccountBackupViewModel(
            gateway,
            object : AccountContextReader {
                override val changes = emptyFlow<Unit>()

                override suspend fun read(): LocalAccountContext =
                    error("Gateway owns reading account context")
            },
            backup,
        )

    private class Gateway : AccountGateway {
        override val state = MutableStateFlow<AccountState>(AccountState.LocalOnly)
        var refreshes = 0
        var signIns = 0
        var cancellations = 0
        var cancelResult: AccountActionResult = AccountActionResult.Completed
        var signInStateAfterSuccess: AccountState? = null

        override suspend fun refresh(): AccountActionResult {
            refreshes++
            return AccountActionResult.Completed
        }

        override suspend fun startGoogleSignIn(): AccountActionResult {
            signIns++
            signInStateAfterSuccess?.let { state.value = it }
            return AccountActionResult.Completed
        }

        override suspend fun cancelDataChoice(): AccountActionResult {
            cancellations++
            return cancelResult
        }

        override suspend fun reauthenticate(): AccountActionResult = AccountActionResult.Unavailable

        override suspend fun signOut(): AccountActionResult = AccountActionResult.Unavailable

        override suspend fun deleteAccount(): AccountActionResult = AccountActionResult.Unavailable
    }

    private class Backup : BackupCoordinator {
        override val status = MutableStateFlow<BackupStatus>(BackupStatus.LocalOnly)
        override val latestSummary = MutableStateFlow<RemoteBackupSummary?>(null)
        override val undoAvailable = MutableStateFlow(false)
        val summary = RemoteBackupSummary("latest", 100, "other-device", mapOf("Record" to 1))
        var statusRefreshes = 0
        var lookups = 0

        override suspend fun refreshStatus() {
            statusRefreshes++
        }

        override suspend fun latestCompleteBackup(): BackupLookupResult {
            lookups++
            latestSummary.value = summary
            status.value = BackupStatus.ReviewRequired
            return BackupLookupResult.Complete(summary)
        }

        override suspend fun previewRestore() =
            RestorePreviewResult.Ready(
                RestorePreview(
                    "restore",
                    summary,
                    "Another device",
                    emptyMap(),
                    false,
                    null,
                    emptySet()
                )
            )

        override suspend fun backUpNow() = BackupActionResult.Unavailable

        override suspend fun deleteAllRemoteData() = BackupActionResult.Unavailable
    }
}
