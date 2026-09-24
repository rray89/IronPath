package com.example.ironpath.ui.screens.accountbackup

import com.example.ironpath.domain.account.*
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

    private fun viewModel(gateway: Gateway) =
        AccountBackupViewModel(
            gateway,
            object : AccountContextReader {
                override val changes = emptyFlow<Unit>()

                override suspend fun read(): LocalAccountContext =
                    error("Gateway owns reading account context")
            },
            com.example.ironpath.data.backup.LocalOnlyBackupCoordinator(
                object : com.example.ironpath.data.backup.InstallationGuard {
                    override suspend fun validate() =
                        com.example.ironpath.data.backup.InstallationValidationResult.Validated
                }
            ),
        )

    private class Gateway : AccountGateway {
        override val state = MutableStateFlow<AccountState>(AccountState.LocalOnly)
        var refreshes = 0
        var signIns = 0
        var cancellations = 0
        var cancelResult: AccountActionResult = AccountActionResult.Completed

        override suspend fun refresh(): AccountActionResult {
            refreshes++
            return AccountActionResult.Completed
        }

        override suspend fun startGoogleSignIn(): AccountActionResult {
            signIns++
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
}
