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
        val backup = Backup()
        val viewModel = viewModel(gateway, backup)
        advanceUntilIdle()
        assertEquals(1, gateway.refreshes)
        assertEquals(0, gateway.signIns)
        assertEquals(0, backup.lookups)
        viewModel.refresh()
        viewModel.signIn()
        advanceUntilIdle()
        assertEquals(2, gateway.refreshes)
        assertEquals(1, gateway.signIns)
    }

    @Test
    fun `explicit invalid session recovery preserves account boundary and training state`() =
        runTest {
            val gateway =
                Gateway().apply {
                    state.value =
                        AccountState.RecoverableError(
                            AccountFailureReason.LocalStateUnavailable,
                            canRecoverUnreadableSession = true,
                        )
                }
            val viewModel = viewModel(gateway)
            advanceUntilIdle()

            viewModel.recoverUnreadableSession()
            advanceUntilIdle()

            assertEquals(1, gateway.sessionRecoveries)
            assertEquals(AccountState.LocalOnly, gateway.state.value)
            assertTrue(viewModel.manual.value.feedback!!.contains("Training data remains"))
            assertFalse(viewModel.manual.value.signOutBusy)
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
    fun `recreated signed-in account looks up backup when account screen refreshes`() = runTest {
        val gateway =
            Gateway().apply {
                state.value =
                    AccountState.AwaitingDataChoice(
                        AccountId("owner"),
                        DataChoiceContext(
                            LocalOwnership.Unclaimed,
                            localDataIsEmpty = true,
                            remoteSnapshot =
                                RemoteSnapshotPresence.Complete(
                                    "latest",
                                    1,
                                    "other-device",
                                    "digest"
                                ),
                            conflict =
                                PersistedConflictContext(
                                    null,
                                    0,
                                    null,
                                    null,
                                    "installation",
                                    1,
                                    0,
                                ),
                        ),
                    )
            }
        val backup = Backup()

        val viewModel = viewModel(gateway, backup)
        advanceUntilIdle()

        assertEquals(0, backup.lookups)
        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, backup.lookups)
        assertEquals(backup.summary, viewModel.manual.value.latest)
    }

    @Test
    fun `keep empty explicitly associates without starting another backup operation`() = runTest {
        val gateway =
            Gateway().apply {
                state.value =
                    AccountState.AwaitingDataChoice(
                        AccountId("owner"),
                        DataChoiceContext(
                            LocalOwnership.Unclaimed,
                            localDataIsEmpty = true,
                            remoteSnapshot =
                                RemoteSnapshotPresence.Complete(
                                    "latest",
                                    1,
                                    "other-device",
                                    "digest"
                                ),
                            conflict =
                                PersistedConflictContext(
                                    null,
                                    0,
                                    null,
                                    null,
                                    "installation",
                                    1,
                                    0,
                                ),
                        ),
                        sessionEpoch = 7,
                    )
            }
        val backup = Backup()
        val viewModel = viewModel(gateway, backup)
        advanceUntilIdle()

        viewModel.keepDeviceEmpty()
        advanceUntilIdle()

        assertEquals(1, backup.associations)
        assertEquals(AccountId("owner"), backup.associatedAccount)
        assertEquals(7L, backup.associatedSessionEpoch)
        assertEquals("installation", backup.associatedInstallationId)
        assertEquals(1L, backup.associatedLocalChangeRevision)
        assertEquals("latest", backup.associatedRemote?.backupId)
        assertEquals(BackupActionResult.Completed, backup.associationResult)
        assertTrue(viewModel.manual.value.feedback!!.contains("device will stay empty"))
        assertEquals(2, backup.statusRefreshes)
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
    fun `data choice back preserves session while credential cancellation remains explicit`() =
        runTest {
            val gateway = Gateway()
            val viewModel = viewModel(gateway)
            advanceUntilIdle()
            var exits = 0
            gateway.state.value =
                AccountState.AwaitingDataChoice(
                    AccountId("a"),
                    DataChoiceContext(
                        LocalOwnership.Unclaimed,
                        true,
                        RemoteSnapshotPresence.Absent,
                        null
                    )
                )
            viewModel.leave { exits++ }
            advanceUntilIdle()
            assertEquals(1, exits)
            assertEquals(0, gateway.cancellations)

            gateway.state.value =
                AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
            viewModel.leave { exits++ }
            advanceUntilIdle()
            assertEquals(2, exits)
            assertEquals(0, gateway.cancellations)
            gateway.state.value = AccountState.CancellingDataChoice
            viewModel.leave { exits++ }
            advanceUntilIdle()
            assertEquals(2, exits)
            gateway.state.value = AccountState.LocalOnly
            viewModel.leave { exits++ }
            advanceUntilIdle()
            assertEquals(3, exits)
            assertEquals(0, gateway.cancellations)
        }

    @Test
    fun `sign out review defaults to keep and dismissal does not call gateway`() = runTest {
        val profile = AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
        val gateway =
            Gateway().apply {
                state.value = AccountState.SignedIn(profile.id, profile, sessionEpoch = 7)
            }
        val viewModel = viewModel(gateway)
        advanceUntilIdle()

        viewModel.openSignOutReview()
        assertEquals(SignOutDataChoice.KeepData, viewModel.manual.value.signOutReview?.choice)
        assertEquals(profile.id, viewModel.manual.value.signOutReview?.target?.accountId)
        viewModel.dismissSignOutReview()

        assertNull(viewModel.manual.value.signOutReview)
        assertTrue(gateway.signOutRequests.isEmpty())
    }

    @Test
    fun `remove data requires second confirmation and pending retry only clears session`() =
        runTest {
            val profile =
                AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
            val gateway =
                Gateway().apply {
                    state.value = AccountState.SignedIn(profile.id, profile, sessionEpoch = 7)
                    signOutResult = AccountActionResult.Completed
                }
            val viewModel = viewModel(gateway)
            advanceUntilIdle()

            viewModel.openSignOutReview()
            viewModel.chooseSignOutChoice(SignOutDataChoice.RemoveData)
            viewModel.confirmSignOut(removeDataConfirmed = true)
            assertTrue(gateway.signOutRequests.isEmpty())
            viewModel.requestRemoveConfirmation()
            viewModel.confirmSignOut(removeDataConfirmed = true)
            advanceUntilIdle()

            assertEquals(
                listOf(SignOutRequest(profile.id, 7, SignOutDataChoice.RemoveData, true)),
                gateway.signOutRequests,
            )
            assertNull(viewModel.manual.value.signOutReview)
            assertFalse(viewModel.manual.value.signOutBusy)
            assertTrue(
                viewModel.manual.value.feedback?.contains("Training data was removed") == true
            )

            gateway.state.value = AccountState.SignOutPending(profile.id, profile, sessionEpoch = 8)
            viewModel.retrySignOut()
            advanceUntilIdle()
            assertEquals(
                SignOutRequest(profile.id, 8, SignOutDataChoice.KeepData),
                gateway.signOutRequests.last(),
            )
            assertTrue(
                viewModel.manual.value.feedback?.contains("Training data was removed") == true
            )
            assertFalse(viewModel.manual.value.feedback?.contains("remain on this device") == true)
        }

    @Test
    fun `sign out failure does not claim data is still available when state is unknown`() =
        runTest {
            val profile =
                AccountProfile(AccountId("demo"), "Demo Athlete", "athlete@example.invalid")
            val gateway =
                Gateway().apply {
                    state.value = AccountState.SignedIn(profile.id, profile, sessionEpoch = 7)
                    signOutResult =
                        AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable)
                    signOutStateAfterFailure =
                        AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
                }
            val viewModel = viewModel(gateway)
            advanceUntilIdle()

            viewModel.openSignOutReview()
            viewModel.chooseSignOutChoice(SignOutDataChoice.RemoveData)
            viewModel.requestRemoveConfirmation()
            viewModel.confirmSignOut(removeDataConfirmed = true)
            advanceUntilIdle()

            val feedback = viewModel.manual.value.feedback.orEmpty()
            assertTrue(feedback.contains("Check account and training data status"))
            assertFalse(feedback.contains("remain available"))
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
        var sessionRecoveries = 0
        var recoveryResult: AccountActionResult = AccountActionResult.Completed
        var cancelResult: AccountActionResult = AccountActionResult.Completed
        var signOutResult: AccountActionResult = AccountActionResult.Unavailable
        val signOutRequests = mutableListOf<SignOutRequest>()
        var signInStateAfterSuccess: AccountState? = null
        var signOutStateAfterFailure: AccountState? = null

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

        override suspend fun recoverUnreadableSession(): AccountActionResult {
            sessionRecoveries++
            if (recoveryResult == AccountActionResult.Completed)
                state.value = AccountState.LocalOnly
            return recoveryResult
        }

        override suspend fun reauthenticate(): AccountActionResult = AccountActionResult.Unavailable

        override suspend fun signOut(request: SignOutRequest): AccountActionResult {
            signOutRequests += request
            if (signOutResult == AccountActionResult.Completed) state.value = AccountState.LocalOnly
            if (signOutResult is AccountActionResult.Failed)
                signOutStateAfterFailure?.let { state.value = it }
            return signOutResult
        }

        override suspend fun deleteAccount(): AccountActionResult = AccountActionResult.Unavailable
    }

    private class Backup : BackupCoordinator {
        override val status = MutableStateFlow<BackupStatus>(BackupStatus.LocalOnly)
        override val latestSummary = MutableStateFlow<RemoteBackupSummary?>(null)
        override val undoAvailable = MutableStateFlow(false)
        val summary = RemoteBackupSummary("latest", 100, "other-device", mapOf("Record" to 1))
        var statusRefreshes = 0
        var lookups = 0
        var associations = 0
        var associatedAccount: AccountId? = null
        var associatedSessionEpoch: Long? = null
        var associatedInstallationId: String? = null
        var associatedLocalChangeRevision: Long? = null
        var associatedRemote: RemoteSnapshotPresence.Complete? = null
        var associationResult: BackupActionResult = BackupActionResult.Completed

        override suspend fun refreshStatus() {
            statusRefreshes++
        }

        override suspend fun latestCompleteBackup(): BackupLookupResult {
            lookups++
            latestSummary.value = summary
            status.value = BackupStatus.ReviewRequired
            return BackupLookupResult.Complete(summary)
        }

        override suspend fun associateEmptyProfile(
            accountId: AccountId,
            sessionEpoch: Long,
            expectedInstallationId: String,
            expectedLocalChangeRevision: Long,
            expectedRemoteSnapshot: RemoteSnapshotPresence.Complete,
        ): BackupActionResult {
            associations++
            associatedAccount = accountId
            associatedSessionEpoch = sessionEpoch
            associatedInstallationId = expectedInstallationId
            associatedLocalChangeRevision = expectedLocalChangeRevision
            associatedRemote = expectedRemoteSnapshot
            return associationResult
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
