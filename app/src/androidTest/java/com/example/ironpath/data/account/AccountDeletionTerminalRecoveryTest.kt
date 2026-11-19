package com.example.ironpath.data.account

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationSentinel
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.backup.RoomInstallationGuard
import com.example.ironpath.data.local.AccountDeletionInProgressException
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.requireWritesAllowed
import com.example.ironpath.domain.account.*
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.TestData
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real persisted deletion/gateway recovery, with no provider or network transport. */
@RunWith(AndroidJUnit4::class)
class AccountDeletionTerminalRecoveryTest {
    @get:Rule val databases = FileBackedRoomTestDatabaseRule()
    private val oldProfile =
        AccountProfile(AccountId("retiring-owner"), "Retiring Athlete", "retiring@example.invalid")
    private val foreignProfile =
        AccountProfile(AccountId("foreign-owner"), "Foreign Athlete", "foreign@example.invalid")
    private val time = MutableTimeProvider(Instant.ofEpochMilli(10_000), ZoneId.of("UTC"))
    private val ids =
        object : IdProvider {
            private var sequence = 0

            override fun newId() = "terminal-recovery-installation-${++sequence}"
        }
    private val sentinel =
        object : InstallationSentinel {
            private var value: String? = null

            override suspend fun readInstallationId() = value

            override suspend fun writeInstallationId(installationId: String): Boolean {
                value = installationId
                return true
            }
        }

    @Test
    fun cancelledThenInterruptedSignOutWithSameSessionUsesOrdinaryRecovery() = runBlocking {
        terminalThenInterruptedSignOut(AccountDeletionStage.CANCELLED, ProviderAfterSignOut.SAME)
    }

    @Test
    fun cancelledThenInterruptedSignOutWithClearedSessionUsesOrdinaryRecovery() = runBlocking {
        terminalThenInterruptedSignOut(AccountDeletionStage.CANCELLED, ProviderAfterSignOut.CLEARED)
    }

    @Test
    fun cancelledThenInterruptedSignOutWithForeignSessionUsesOrdinaryRecovery() = runBlocking {
        terminalThenInterruptedSignOut(AccountDeletionStage.CANCELLED, ProviderAfterSignOut.FOREIGN)
    }

    @Test
    fun completedThenInterruptedSignOutWithSameSessionUsesOrdinaryRecovery() = runBlocking {
        terminalThenInterruptedSignOut(AccountDeletionStage.COMPLETE, ProviderAfterSignOut.SAME)
    }

    @Test
    fun completedThenInterruptedSignOutWithClearedSessionUsesOrdinaryRecovery() = runBlocking {
        terminalThenInterruptedSignOut(AccountDeletionStage.COMPLETE, ProviderAfterSignOut.CLEARED)
    }

    @Test
    fun completedThenInterruptedSignOutWithForeignSessionUsesOrdinaryRecovery() = runBlocking {
        terminalThenInterruptedSignOut(AccountDeletionStage.COMPLETE, ProviderAfterSignOut.FOREIGN)
    }

    @Test
    fun failedCancellationStabilizationRetainsTerminalThroughAllProviderAndLocalObservations() =
        runBlocking {
            failedStabilizationRetainsTerminal(AccountDeletionStage.CANCELLED)
        }

    @Test
    fun failedCompletionStabilizationRetainsTerminalThroughAllProviderAndLocalObservations() =
        runBlocking {
            failedStabilizationRetainsTerminal(AccountDeletionStage.COMPLETE)
        }

    private suspend fun terminalThenInterruptedSignOut(
        stage: AccountDeletionStage,
        provider: ProviderAfterSignOut
    ) {
        val first = databases.open()
        val sessions = Sessions(oldProfile)
        val service = ReceiptService()
        val live = runtime(first, sessions, service)
        prepareReservation(first, live)
        service.statusState =
            if (stage == AccountDeletionStage.COMPLETE) AccountDeletionRemoteState.COMPLETE
            else AccountDeletionRemoteState.RESERVED
        val expectedResult =
            if (stage == AccountDeletionStage.CANCELLED) AccountDeletionResult.Cancelled
            else AccountDeletionResult.Completed
        val result =
            if (stage == AccountDeletionStage.CANCELLED) live.manager.cancelUnactivated()
            else live.manager.recoverAtStartup()
        assertEquals(expectedResult, result)
        val terminal = checkNotNull(live.manager.pending())
        assertEquals(stage, terminal.stage)
        assertFalse(admitted(live.gate))
        // Crash after durable terminal state, before gateway acknowledgment.
        first.close()

        val settledDatabase = databases.open()
        val settled = runtime(settledDatabase, sessions, service)
        assertFalse(settled.manager.acknowledgeTerminalRecovery(terminal))
        assertEquals(terminal, settled.store.journal())
        assertEquals(expectedResult, settled.manager.recoverAtStartup())
        assertFalse(settled.manager.acknowledgeTerminalRecovery(null))
        assertFalse(
            settled.manager.acknowledgeTerminalRecovery(
                terminal.copy(receiptVersion = terminal.receiptVersion + 1)
            )
        )
        assertEquals(terminal, settled.manager.pending())
        assertFalse(admitted(settled.gate))
        assertEquals(
            AccountActionResult.Completed,
            settled.gateway.reconcileAfterDeletionRecovery()
        )
        assertNull(settled.store.journal())
        assertNull(settled.manager.pending())
        assertTrue(admitted(settled.gate))
        val serviceCallsAfterTerminal = service.calls

        // A later account lifecycle is independent of the acknowledged deletion.
        val laterProfile =
            if (stage == AccountDeletionStage.CANCELLED) oldProfile
            else AccountProfile(AccountId("later-owner"), "Later Athlete", "later@example.invalid")
        sessions.current = laterProfile
        val beforeSignOut = checkNotNull(settledDatabase.backupDao().getMetadata())
        settledDatabase
            .backupDao()
            .updateMetadata(beforeSignOut.copy(ownerUid = laterProfile.id.opaqueValue))
        settledDatabase
            .backupDao()
            .insertWorkoutLogs(listOf(TestData.log(id = "later-sign-out-log")))
        assertEquals(AccountActionResult.Completed, settled.gateway.refreshLocal())
        val signedIn = settled.gateway.state.value as AccountState.SignedIn
        sessions.failClear = true
        settled.gateway.signOut(
            SignOutRequest(
                laterProfile.id,
                signedIn.sessionEpoch,
                SignOutDataChoice.RemoveData,
                removeDataConfirmed = true
            )
        )
        val committed = checkNotNull(settledDatabase.backupDao().getMetadata())
        assertEquals(beforeSignOut.profileGeneration + 1, committed.profileGeneration)
        assertEquals(laterProfile.id.opaqueValue, committed.pendingSignOutUid)
        assertNull(committed.ownerUid)
        assertTrue(settledDatabase.backupDao().getWorkoutLogs().isEmpty())
        assertEquals(laterProfile, sessions.current)
        sessions.failClear = false
        sessions.current =
            when (provider) {
                ProviderAfterSignOut.SAME -> laterProfile
                ProviderAfterSignOut.CLEARED -> null
                ProviderAfterSignOut.FOREIGN -> foreignProfile
            }
        settledDatabase.close()

        val restartedDatabase = databases.open()
        val restarted = runtime(restartedDatabase, sessions, service)
        assertEquals(AccountDeletionResult.Idle, restarted.manager.recoverAtStartup())
        assertNull(restarted.manager.pending())
        assertEquals(AccountActionResult.Completed, restarted.gateway.refreshLocal())
        when (provider) {
            ProviderAfterSignOut.SAME -> {
                val pending = restarted.gateway.state.value as AccountState.SignOutPending
                assertEquals(laterProfile.id, pending.accountId)
                assertEquals(laterProfile, sessions.current)
                assertEquals(committed, restartedDatabase.backupDao().getMetadata())
                assertFalse(admitted(restarted.gate))
                assertEquals(
                    AccountActionResult.Completed,
                    restarted.gateway.signOut(
                        SignOutRequest(
                            laterProfile.id,
                            pending.sessionEpoch,
                            SignOutDataChoice.RemoveData,
                            removeDataConfirmed = true
                        )
                    )
                )
                assertNull(sessions.current)
                assertEquals(AccountActionResult.Completed, restarted.gateway.refreshLocal())
            }
            ProviderAfterSignOut.CLEARED -> {
                assertEquals(AccountState.LocalOnly, restarted.gateway.state.value)
                assertNull(sessions.current)
            }
            ProviderAfterSignOut.FOREIGN -> {
                val awaiting = restarted.gateway.state.value as AccountState.AwaitingDataChoice
                assertEquals(foreignProfile.id, awaiting.accountId)
                assertEquals(LocalOwnership.Unclaimed, awaiting.context.ownership)
                assertEquals(foreignProfile, sessions.current)
            }
        }
        assertEquals(
            committed.copy(pendingSignOutUid = null),
            restartedDatabase.backupDao().getMetadata()
        )
        assertTrue(restartedDatabase.backupDao().getWorkoutLogs().isEmpty())
        assertNull(restarted.store.journal())
        assertTrue(admitted(restarted.gate))
        assertEquals(serviceCallsAfterTerminal, service.calls)
        assertEquals(0, sessions.reauthentications)
    }

    private suspend fun failedStabilizationRetainsTerminal(stage: AccountDeletionStage) {
        val database = databases.open()
        val sessions = Sessions(oldProfile)
        val service = ReceiptService()
        val live = runtime(database, sessions, service)
        prepareReservation(database, live)
        live.gateway.refreshLocal()
        assertTrue(live.gateway.state.value is AccountState.AccountDeletionPending)
        service.statusState =
            if (stage == AccountDeletionStage.COMPLETE) AccountDeletionRemoteState.COMPLETE
            else AccountDeletionRemoteState.RESERVED
        live.guard.failed = true
        val result =
            if (stage == AccountDeletionStage.CANCELLED) live.gateway.cancelAccountDeletion()
            else live.gateway.retryAccountDeletion()
        assertEquals(AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable), result)
        val terminal = checkNotNull(live.store.journal())
        assertEquals(stage, terminal.stage)
        assertEquals(terminal, live.manager.pending())
        assertTrue(
            runCatching { database.withTransaction { database.requireWritesAllowed() } }
                .exceptionOrNull() is AccountDeletionInProgressException
        )
        val metadata = database.backupDao().getMetadata()
        val logs = database.backupDao().getWorkoutLogs()
        val records = database.backupDao().getPersonalRecords()
        listOf(oldProfile, null, foreignProfile).forEach { current ->
            sessions.current = current
            live.gateway.reconcileSessionChange()
            assertEquals(AccountState.AccountDeletionPending(terminal), live.gateway.state.value)
            assertFalse(admitted(live.gate))
            live.gateway.refreshLocal()
            assertEquals(AccountState.AccountDeletionPending(terminal), live.gateway.state.value)
            assertFalse(admitted(live.gate))
            assertEquals(terminal, live.store.journal())
            assertEquals(metadata, database.backupDao().getMetadata())
            assertEquals(logs, database.backupDao().getWorkoutLogs())
            assertEquals(records, database.backupDao().getPersonalRecords())
            assertEquals(current, sessions.current)
        }
        live.guard.failed = false
        assertEquals(
            if (stage == AccountDeletionStage.CANCELLED) AccountActionResult.Cancelled
            else AccountActionResult.Completed,
            live.gateway.retryAccountDeletion(),
        )
        assertNull(live.store.journal())
        assertNull(live.manager.pending())
        database.withTransaction { database.requireWritesAllowed() }
        assertEquals(foreignProfile, sessions.current)
        assertTrue(admitted(live.gate))
        assertEquals(metadata, database.backupDao().getMetadata())
        assertEquals(logs, database.backupDao().getWorkoutLogs())
        assertEquals(records, database.backupDao().getPersonalRecords())
    }

    private suspend fun prepareReservation(database: IronPathDatabase, live: Runtime) {
        assertEquals(InstallationValidationResult.Initialized, live.guard.validate())
        val metadata = checkNotNull(database.backupDao().getMetadata())
        database.backupDao().updateMetadata(metadata.copy(ownerUid = oldProfile.id.opaqueValue))
        database.backupDao().insertWorkoutLogs(listOf(TestData.log(id = "before-terminal-log")))
        database
            .backupDao()
            .insertPersonalRecords(listOf(TestData.record(id = "before-terminal-record")))
        val request =
            AccountDeletionRequest(
                oldProfile.id,
                0,
                metadata.profileGeneration,
                serviceBinding = "isolated-terminal-service"
            )
        val draft = checkNotNull(live.store.createDraft(request))
        assertNotNull(
            live.store.prepareReservation(
                draft,
                DeletionServiceReceipt(
                    draft.operationId,
                    "a".repeat(64),
                    AccountDeletionRemoteState.RESERVED,
                    1
                )
            )
        )
    }

    private fun runtime(
        database: IronPathDatabase,
        sessions: Sessions,
        service: ReceiptService
    ): Runtime {
        val gate = AccountSessionOperationGate()
        val backup = RoomBackupStore(database, ids, sentinel)
        val guard = ControllableGuard(RoomInstallationGuard(backup))
        val store = RoomAccountDeletionStore(database, ids, time, sentinel)
        val manager = ServiceAccountDeletionManager(store, service, sessions, gate, guard)
        val gateway =
            PersistedAccountGateway(
                sessions,
                RoomAccountContextReader(database),
                guard,
                backup,
                gate,
                manager,
                AccountExperienceCapabilities.AuthPreview.copy(
                    canAssociateLocalData = true,
                    canDeleteAccount = true
                ),
            )
        return Runtime(store, manager, gateway, gate, guard)
    }

    private suspend fun admitted(gate: AccountSessionOperationGate) =
        gate.withManualOperation(waitForTurn = false, unavailable = false) { true }

    private enum class ProviderAfterSignOut {
        SAME,
        CLEARED,
        FOREIGN
    }

    private data class Runtime(
        val store: RoomAccountDeletionStore,
        val manager: ServiceAccountDeletionManager,
        val gateway: PersistedAccountGateway,
        val gate: AccountSessionOperationGate,
        val guard: ControllableGuard,
    )

    private class ControllableGuard(private val real: InstallationGuard) : InstallationGuard {
        var failed = false

        override suspend fun validate() =
            if (failed) InstallationValidationResult.Failed else real.validate()
    }

    private class ReceiptService : AccountDeletionService {
        override val binding = "isolated-terminal-service"
        var statusState = AccountDeletionRemoteState.RESERVED
        var calls = 0

        override suspend fun available() = true

        override suspend fun start(operationId: String, token: String): DeletionServiceResult =
            error("Legacy activation is forbidden")

        override suspend fun resume(operationId: String): DeletionServiceResult =
            error("Legacy recovery is forbidden")

        override suspend fun status(progress: AccountDeletionProgress): DeletionServiceResult {
            calls++
            return receipt(progress, statusState)
        }

        override suspend fun cancel(progress: AccountDeletionProgress): DeletionServiceResult {
            calls++
            statusState = AccountDeletionRemoteState.CANCELLED_NO_DELETE
            return receipt(progress, statusState)
        }

        private fun receipt(progress: AccountDeletionProgress, state: AccountDeletionRemoteState) =
            DeletionServiceResult.Receipt(
                DeletionServiceReceipt(
                    progress.operationId,
                    checkNotNull(progress.subjectBinding),
                    state,
                    progress.receiptVersion + if (state == progress.remoteState) 0 else 1
                )
            )
    }

    private class Sessions(initial: AccountProfile) :
        AccountSessionAdapter, AccountDeletionIdentity {
        var current: AccountProfile? = initial
        var failClear = false
        var reauthentications = 0

        override suspend fun readSession() = current

        override suspend fun currentAccount() = current?.id

        override suspend fun clearSession(): Boolean {
            if (failClear) return false
            current = null
            return true
        }

        override suspend fun clearDeletedSession(account: AccountId): Boolean {
            if (current?.id == account) current = null
            return true
        }

        override suspend fun reauthenticate(account: AccountId): DeletionReauthentication {
            reauthentications++
            return DeletionReauthentication.Authenticated("isolated-transient-proof")
        }

        override suspend fun requestGoogleCredential(requestId: Long): CredentialResult =
            CredentialResult.Cancelled

        override suspend fun commitGoogleCredential(
            candidate: PendingGoogleCredential
        ): CredentialCommitResult =
            CredentialCommitResult.Failed(AccountFailureReason.ServiceUnavailable)

        override suspend fun remoteSnapshot(accountId: AccountId): RemoteSnapshotPresence =
            RemoteSnapshotPresence.Absent
    }
}
