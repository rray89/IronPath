package com.example.ironpath.data.account

import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.domain.account.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServiceAccountDeletionManagerTest {
    private val account = AccountId("synthetic-owner")
    private val request = AccountDeletionRequest(account, 0, 2)
    private val operation = "b0128305-f28b-4eba-b389-bb4c736f76c9"
    private val secret = "s".repeat(43)
    private val events = mutableListOf<String>()
    private val gate = AccountSessionOperationGate()
    private val store = FakeStore()
    private val identity = FakeIdentity()
    private val service = FakeService()
    private var installation = InstallationValidationResult.Validated

    private fun manager() =
        ServiceAccountDeletionManager(
            store,
            service,
            identity,
            gate,
            object : InstallationGuard {
                override suspend fun validate() = installation
            }
        )

    private fun receipt(
        state: AccountDeletionRemoteState,
        version: Long =
            if (state == AccountDeletionRemoteState.COMPLETE) 3
            else if (state == AccountDeletionRemoteState.RESERVED) 1 else 2
    ) = DeletionServiceResult.Receipt(DeletionServiceReceipt(operation, "subject", state, version))

    private suspend fun prepared() {
        service.activation = DeletionServiceResult.Unavailable
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        events.clear()
    }

    @Test
    fun `reservation ACK precedes journal and activation before local cleanup`() = runTest {
        assertEquals(AccountDeletionResult.Completed, manager().delete(request))
        assertEquals(
            listOf(
                "capability",
                "draft",
                "reauth",
                "reserve",
                "prepare",
                "activate",
                "record",
                "BACKUPS_PURGED",
                "ACCOUNT_TOMBSTONED",
                "clear",
                "marker",
                "signout",
                "complete"
            ),
            events
        )
        assertTrue(store.erased)
    }

    @Test
    fun `cancelled chooser retires nonblocking draft and leaves all training intact`() = runTest {
        identity.result = DeletionReauthentication.Cancelled
        assertEquals(AccountDeletionResult.Cancelled, manager().delete(request))
        assertNull(store.draft)
        assertNull(store.saved)
        assertFalse(store.erased)
        assertFalse(events.contains("reserve"))
        assertFalse(events.contains("activate"))
    }

    @Test
    fun `failed chooser never reserves or installs journal`() = runTest {
        identity.result = DeletionReauthentication.Failed(AccountFailureReason.Offline)
        assertEquals(
            AccountDeletionResult.Failed(AccountFailureReason.Offline),
            manager().delete(request)
        )
        assertNull(store.saved)
        assertFalse(events.contains("reserve"))
    }

    @Test
    fun `unaccepted reserve and lost ACK do not lock local data`() = runTest {
        service.reservation = DeletionServiceResult.Unavailable
        assertEquals(
            AccountDeletionResult.Failed(AccountFailureReason.ServiceUnavailable),
            manager().delete(request)
        )
        assertNull(store.saved)
        assertNull(manager().pending())
        assertFalse(store.erased)
        assertFalse(events.contains("activate"))
    }

    @Test
    fun `wrong receipt operation cannot install journal`() = runTest {
        service.reservation =
            DeletionServiceResult.Receipt(
                DeletionServiceReceipt("other", "subject", AccountDeletionRemoteState.RESERVED, 1)
            )
        assertEquals(AccountDeletionResult.Unavailable, manager().delete(request))
        assertNull(store.saved)
    }

    @Test
    fun `profile changes after ACK prevent promotion and activation`() = runTest {
        service.afterReserve = { store.valid = false }
        assertEquals(AccountDeletionResult.Unavailable, manager().delete(request))
        assertNull(store.saved)
        assertFalse(events.contains("activate"))
    }

    @Test
    fun `provider change after ACK leaves unactivated draft`() = runTest {
        service.afterReserve = { identity.current = AccountId("foreign") }
        assertEquals(AccountDeletionResult.Cancelled, manager().delete(request))
        assertNull(store.saved)
        assertFalse(events.contains("activate"))
    }

    @Test
    fun `epoch change during chooser prevents reservation`() = runTest {
        identity.afterReauth = { gate.advanceSessionEpoch() }
        assertEquals(AccountDeletionResult.Cancelled, manager().delete(request))
        assertNull(store.saved)
        assertFalse(events.contains("reserve"))
    }

    @Test
    fun `existing gateway mutation epoch does not reject valid request`() = runTest {
        gate.advanceSessionEpoch()
        assertEquals(AccountDeletionResult.Completed, manager().delete(request))
    }

    @Test
    fun `missing capability foreign owner and transferred install never request proof`() = runTest {
        service.available = false
        assertEquals(AccountDeletionResult.Unavailable, manager().delete(request))
        assertFalse(events.contains("reauth"))
        service.available = true
        events.clear()
        assertEquals(
            AccountDeletionResult.Unavailable,
            manager().delete(request.copy(expectedLocalOwnerUid = "foreign"))
        )
        installation = InstallationValidationResult.Transferred
        assertEquals(AccountDeletionResult.Unavailable, manager().delete(request))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `client death after committed journal resumes known receipt without Auth`() = runTest {
        prepared()
        identity.current = null
        service.statusResult = receipt(AccountDeletionRemoteState.COMPLETE)
        assertEquals(AccountDeletionResult.Completed, manager().recoverAtStartup())
        assertTrue(events.contains("status"))
        assertFalse(events.contains("reauth"))
        assertFalse(events.contains("activate"))
    }

    @Test
    fun `server reservation survives separate client restart and expired proof`() = runTest {
        prepared()
        service.statusResult = receipt(AccountDeletionRemoteState.RESERVED)
        assertTrue(manager().recoverAtStartup() is AccountDeletionResult.RetryRequired)
        assertEquals(listOf("capability", "status", "record"), events)
        events.clear()
        identity.result = DeletionReauthentication.Authenticated("new-fresh-proof")
        service.activation = receipt(AccountDeletionRemoteState.COMPLETE)
        assertEquals(AccountDeletionResult.Completed, manager().retry())
        assertEquals("new-fresh-proof", service.lastActivationToken)
        assertTrue(events.contains("reauth"))
    }

    @Test
    fun `cancel reserved without Auth preserves all data and terminal identity`() = runTest {
        prepared()
        identity.current = null
        assertEquals(AccountDeletionResult.Cancelled, manager().cancelUnactivated())
        assertFalse(store.erased)
        assertEquals(AccountDeletionStage.CANCELLED, store.saved?.stage)
        assertEquals(AccountDeletionStage.CANCELLED, manager().pending()?.stage)
        assertEquals(AccountDeletionResult.Cancelled, manager().recoverAtStartup())
        assertFalse(events.contains("reauth"))
        assertFalse(events.contains("signout"))
    }

    @Test
    fun `foreign session does not block non destructive cancellation`() = runTest {
        prepared()
        identity.current = AccountId("foreign")
        assertEquals(AccountDeletionResult.Cancelled, manager().cancelUnactivated())
        assertEquals(AccountId("foreign"), identity.current)
        assertFalse(store.erased)
    }

    @Test
    fun `activation wins cancel race so local barrier remains pending`() = runTest {
        prepared()
        service.cancellation = receipt(AccountDeletionRemoteState.PENDING)
        assertTrue(manager().cancelUnactivated() is AccountDeletionResult.RetryRequired)
        assertFalse(store.erased)
        assertEquals(AccountDeletionStage.PREPARED, store.saved?.stage)
        assertEquals(AccountDeletionRemoteState.PENDING, store.saved?.remoteState)
    }

    @Test
    fun `completed canonical wins cancel race and cleans confirmed old graph only`() = runTest {
        prepared()
        identity.current = AccountId("foreign")
        service.cancellation = receipt(AccountDeletionRemoteState.COMPLETE)
        assertEquals(AccountDeletionResult.Completed, manager().cancelUnactivated())
        assertTrue(store.erased)
        assertEquals(AccountId("foreign"), identity.current)
    }

    @Test
    fun `lost cancellation ACK recovers immutable cancellation on cold start`() = runTest {
        prepared()
        service.cancellation = DeletionServiceResult.Unavailable
        assertTrue(manager().cancelUnactivated() is AccountDeletionResult.RetryRequired)
        service.statusResult = receipt(AccountDeletionRemoteState.CANCELLED_NO_DELETE)
        assertEquals(AccountDeletionResult.Cancelled, manager().recoverAtStartup())
        assertFalse(store.erased)
    }

    @Test
    fun `late COMPLETE for retired cancelled journal is never consumed`() = runTest {
        prepared()
        manager().cancelUnactivated()
        events.clear()
        service.statusResult = receipt(AccountDeletionRemoteState.COMPLETE)
        assertEquals(AccountDeletionResult.Cancelled, manager().retry())
        assertTrue(events.isEmpty())
        assertFalse(store.erased)
    }

    @Test
    fun `known pending does not call cancellation endpoint`() = runTest {
        prepared()
        service.statusResult = receipt(AccountDeletionRemoteState.PENDING)
        assertTrue(manager().cancelUnactivated() is AccountDeletionResult.RetryRequired)
        assertFalse(events.contains("cancel"))
        assertFalse(store.erased)
    }

    @Test
    fun `unknown v1 journal cannot upgrade or clear lock after Auth gone`() = runTest {
        store.saved =
            AccountDeletionProgress(
                operation,
                account,
                0,
                2,
                AccountDeletionStage.PREPARED,
                serviceBinding = "binding"
            )
        identity.current = null
        assertTrue(manager().recoverAtStartup() is AccountDeletionResult.RetryRequired)
        assertTrue(events.isEmpty())
        assertFalse(store.erased)
    }

    @Test
    fun `service or subject mismatch and stale version never authorize cleanup`() = runTest {
        prepared()
        service.bindingValue = "replacement"
        assertTrue(manager().retry() is AccountDeletionResult.RetryRequired)
        assertFalse(events.contains("status"))
        service.bindingValue = "binding"
        service.statusResult =
            DeletionServiceResult.Receipt(
                DeletionServiceReceipt(
                    operation,
                    "foreign-subject",
                    AccountDeletionRemoteState.COMPLETE,
                    3
                )
            )
        assertTrue(manager().recoverAtStartup() is AccountDeletionResult.RetryRequired)
        assertFalse(store.erased)
        service.statusResult = receipt(AccountDeletionRemoteState.COMPLETE, 0)
        assertTrue(manager().recoverAtStartup() is AccountDeletionResult.RetryRequired)
        assertFalse(store.erased)
    }

    @Test
    fun `local scope mismatch never cleans foreign graph or session`() = runTest {
        prepared()
        store.valid = false
        identity.current = AccountId("foreign")
        service.statusResult = receipt(AccountDeletionRemoteState.COMPLETE)
        assertTrue(manager().recoverAtStartup() is AccountDeletionResult.RetryRequired)
        assertFalse(store.erased)
        assertEquals(AccountId("foreign"), identity.current)
        assertFalse(events.contains("status"))
    }

    @Test
    fun `marker failure resumes atomic local cleared phase without reactivation`() = runTest {
        store.markerSucceeds = false
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        assertEquals(AccountDeletionStage.LOCAL_CLEARED, store.saved?.stage)
        events.clear()
        store.markerSucceeds = true
        assertEquals(AccountDeletionResult.Completed, manager().recoverAtStartup())
        assertFalse(events.contains("activate"))
        assertFalse(events.contains("status"))
        assertFalse(events.contains("clear"))
    }

    @Test
    fun `failed local reset retains authoritative proof across recreation`() = runTest {
        store.clearSucceeds = false
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        assertEquals(AccountDeletionStage.ACCOUNT_TOMBSTONED, store.saved?.stage)
        store.clearSucceeds = true
        events.clear()
        assertEquals(AccountDeletionResult.Completed, manager().recoverAtStartup())
        assertFalse(events.contains("status"))
    }

    @Test
    fun `completed journal survives restart as completion rather than idle`() = runTest {
        manager().delete(request)
        events.clear()
        assertEquals(AccountDeletionResult.Completed, manager().recoverAtStartup())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `draft and progress strings never expose recovery secret`() = runTest {
        prepared()
        assertFalse(store.saved.toString().contains(secret))
        assertFalse(store.draft.toString().contains(secret))
    }

    @Test
    fun `activation exception retains acknowledged journal for cold recovery`() = runTest {
        service.throwActivation = true
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        assertNotNull(store.saved?.subjectBinding)
        assertFalse(store.erased)
        service.throwActivation = false
        service.statusResult = receipt(AccountDeletionRemoteState.COMPLETE)
        assertEquals(AccountDeletionResult.Completed, manager().recoverAtStartup())
    }

    @Test
    fun `cancelled orphan receipt retires draft without installing barrier`() = runTest {
        service.reservation = receipt(AccountDeletionRemoteState.CANCELLED_NO_DELETE)
        assertEquals(AccountDeletionResult.Cancelled, manager().delete(request))
        assertNull(store.draft)
        assertNull(store.saved)
        assertFalse(store.erased)
    }

    @Test
    fun `cancelled terminal stays durable until exact observed acknowledgment`() = runTest {
        prepared()
        val subject = manager()
        assertEquals(AccountDeletionResult.Cancelled, subject.cancelUnactivated())
        val terminal = checkNotNull(subject.pending())
        assertEquals(AccountDeletionStage.CANCELLED, terminal.stage)
        assertFalse(subject.acknowledgeTerminalRecovery(null))
        assertFalse(
            subject.acknowledgeTerminalRecovery(
                terminal.copy(stage = AccountDeletionStage.PREPARED)
            )
        )
        assertFalse(subject.acknowledgeTerminalRecovery(terminal.copy(receiptVersion = 1)))
        assertFalse(
            subject.acknowledgeTerminalRecovery(terminal.copy(operationId = "other-operation"))
        )
        assertEquals(terminal, store.saved)
        assertTrue(subject.acknowledgeTerminalRecovery(terminal))
        assertNull(store.saved)
        assertEquals(AccountDeletionResult.Idle, manager().recoverAtStartup())
        assertFalse(store.erased)
        assertFalse(gate.withManualOperation(false, false) { true })
    }

    @Test
    fun `completed terminal is acknowledged after restart without another remote or local deletion`() =
        runTest {
            assertEquals(AccountDeletionResult.Completed, manager().delete(request))
            val restarted = manager()
            events.clear()
            assertEquals(AccountDeletionResult.Completed, restarted.recoverAtStartup())
            val terminal = checkNotNull(restarted.pending())
            assertEquals(AccountDeletionStage.COMPLETE, terminal.stage)
            assertTrue(events.isEmpty())
            assertTrue(restarted.acknowledgeTerminalRecovery(terminal))
            assertNull(restarted.pending())
            assertEquals(AccountDeletionResult.Idle, manager().recoverAtStartup())
            assertTrue(events.isEmpty())
        }

    @Test
    fun `terminal cannot acknowledge before its outcome is observed by this manager`() = runTest {
        manager().delete(request)
        val terminal = checkNotNull(store.saved)
        val fresh = manager()
        assertFalse(fresh.acknowledgeTerminalRecovery(terminal))
        assertEquals(terminal, store.saved)
        assertEquals(AccountDeletionResult.Completed, fresh.recoverAtStartup())
        assertTrue(fresh.acknowledgeTerminalRecovery(terminal))
    }

    @Test
    fun `failed or throwing acknowledgment preserves terminal for explicit retry`() = runTest {
        val subject = manager()
        subject.delete(request)
        val terminal = checkNotNull(store.saved)
        store.acknowledgeSucceeds = false
        assertFalse(subject.acknowledgeTerminalRecovery(terminal))
        assertEquals(terminal, subject.pending())
        store.throwAcknowledgment = true
        assertFalse(subject.acknowledgeTerminalRecovery(terminal))
        assertEquals(terminal, subject.pending())
        assertFalse(gate.withManualOperation(false, false) { true })
        events.clear()
        assertEquals(AccountDeletionResult.Completed, subject.retry())
        assertTrue(events.isEmpty())
        store.throwAcknowledgment = false
        store.acknowledgeSucceeds = true
        assertTrue(subject.acknowledgeTerminalRecovery(terminal))
    }

    @Test
    fun `newer or replaced journal cannot be erased by an old terminal acknowledgment`() = runTest {
        val subject = manager()
        subject.delete(request)
        val terminal = checkNotNull(store.saved)
        val replaced = terminal.copy(operationId = "replacement-operation", receiptVersion = 7)
        store.saved = replaced
        assertFalse(subject.acknowledgeTerminalRecovery(terminal))
        assertFalse(subject.acknowledgeTerminalRecovery(replaced))
        assertEquals(replaced, store.saved)
    }

    @Test
    fun `missing journal is not acknowledgment of an observed terminal`() = runTest {
        val subject = manager()
        subject.delete(request)
        val terminal = checkNotNull(store.saved)
        store.saved = null
        assertFalse(subject.acknowledgeTerminalRecovery(null))
        assertFalse(subject.acknowledgeTerminalRecovery(terminal))
    }

    @Test
    fun `chooser cancellation without a journal needs no terminal retirement`() = runTest {
        val subject = manager()
        identity.result = DeletionReauthentication.Cancelled
        assertEquals(AccountDeletionResult.Cancelled, subject.delete(request))
        assertTrue(subject.acknowledgeTerminalRecovery(null))
        assertNull(store.saved)
        assertNull(store.draft)
        assertFalse(store.erased)
    }

    private inner class FakeIdentity : AccountDeletionIdentity {
        var current: AccountId? = account
        var result: DeletionReauthentication =
            DeletionReauthentication.Authenticated("initial-fresh-proof")
        var afterReauth: () -> Unit = {}

        override suspend fun currentAccount() = current

        override suspend fun reauthenticate(account: AccountId): DeletionReauthentication {
            events += "reauth"
            afterReauth()
            return result
        }

        override suspend fun clearDeletedSession(account: AccountId): Boolean {
            events += "signout"
            if (current == account) current = null
            return true
        }
    }

    private inner class FakeService : AccountDeletionService {
        var available = true
        var bindingValue = "binding"
        override val binding
            get() = bindingValue

        var reservation: DeletionServiceResult = receipt(AccountDeletionRemoteState.RESERVED)
        var activation: DeletionServiceResult = receipt(AccountDeletionRemoteState.COMPLETE)
        var statusResult: DeletionServiceResult = receipt(AccountDeletionRemoteState.RESERVED)
        var cancellation: DeletionServiceResult =
            receipt(AccountDeletionRemoteState.CANCELLED_NO_DELETE)
        var afterReserve: () -> Unit = {}
        var lastActivationToken: String? = null
        var throwActivation = false

        override suspend fun available(): Boolean {
            events += "capability"
            return available
        }

        override suspend fun reserve(
            draft: AccountDeletionDraft,
            token: String
        ): DeletionServiceResult {
            events += "reserve"
            afterReserve()
            return reservation
        }

        override suspend fun activate(
            progress: AccountDeletionProgress,
            token: String
        ): DeletionServiceResult {
            events += "activate"
            lastActivationToken = token
            if (throwActivation) throw java.io.IOException("synthetic")
            return activation
        }

        override suspend fun status(progress: AccountDeletionProgress): DeletionServiceResult {
            events += "status"
            return statusResult
        }

        override suspend fun cancel(progress: AccountDeletionProgress): DeletionServiceResult {
            events += "cancel"
            return cancellation
        }

        override suspend fun start(operationId: String, token: String) = error("v1 forbidden")

        override suspend fun resume(operationId: String) = error("v1 forbidden")
    }

    private inner class FakeStore : AccountDeletionStore {
        var saved: AccountDeletionProgress? = null
        var draft: AccountDeletionDraft? = null
        var valid = true
        var erased = false
        var markerSucceeds = true
        var clearSucceeds = true
        var acknowledgeSucceeds = true
        var throwAcknowledgment = false

        override suspend fun acknowledgeTerminal(expected: AccountDeletionProgress): Boolean {
            if (throwAcknowledgment) throw IllegalStateException("isolated acknowledgment failure")
            if (
                !acknowledgeSucceeds ||
                    saved != expected ||
                    expected.stage !in
                        setOf(AccountDeletionStage.CANCELLED, AccountDeletionStage.COMPLETE)
            )
                return false
            saved = null
            return true
        }

        override suspend fun journal() = saved

        override suspend fun createDraft(request: AccountDeletionRequest): AccountDeletionDraft? {
            events += "draft"
            if (!valid) return null
            return AccountDeletionDraft(operation, secret, request, "installation").also {
                draft = it
            }
        }

        override suspend fun discardDraft(draft: AccountDeletionDraft): Boolean {
            if (this.draft != draft || saved?.stage == AccountDeletionStage.PREPARED) return false
            this.draft = null
            return true
        }

        override suspend fun prepareReservation(
            draft: AccountDeletionDraft,
            receipt: DeletionServiceReceipt
        ): AccountDeletionProgress? {
            events += "prepare"
            if (
                !valid ||
                    draft != this.draft ||
                    receipt.operationId != operation ||
                    receipt.subjectBinding != "subject"
            )
                return null
            return AccountDeletionProgress(
                    operation,
                    account,
                    request.sessionEpoch,
                    request.profileGeneration,
                    AccountDeletionStage.PREPARED,
                    request.expectedLocalOwnerUid,
                    "binding",
                    secret,
                    receipt.subjectBinding,
                    receipt.version,
                    receipt.state,
                    "installation"
                )
                .also { saved = it }
        }

        override suspend fun recordReceipt(
            expected: AccountDeletionProgress,
            receipt: DeletionServiceReceipt
        ): AccountDeletionProgress? {
            events += "record"
            if (
                saved != expected ||
                    expected.stage != AccountDeletionStage.PREPARED ||
                    receipt.operationId != expected.operationId ||
                    receipt.subjectBinding != expected.subjectBinding ||
                    receipt.version < expected.receiptVersion ||
                    receipt.version <= 0
            )
                return null
            if (
                expected.remoteState == AccountDeletionRemoteState.PENDING &&
                    receipt.state !in
                        setOf(
                            AccountDeletionRemoteState.PENDING,
                            AccountDeletionRemoteState.COMPLETE
                        )
            )
                return null
            return expected
                .copy(receiptVersion = receipt.version, remoteState = receipt.state)
                .also { saved = it }
        }

        override suspend fun cancelReservation(
            expected: AccountDeletionProgress,
            receipt: DeletionServiceReceipt
        ): Boolean {
            if (
                saved != expected ||
                    receipt.state != AccountDeletionRemoteState.CANCELLED_NO_DELETE ||
                    receipt.subjectBinding != expected.subjectBinding
            )
                return false
            saved = expected.copy(stage = AccountDeletionStage.CANCELLED)
            return true
        }

        override suspend fun prepare(request: AccountDeletionRequest): AccountDeletionProgress? =
            error("v1 forbidden")

        override suspend fun matchesProfile(expected: AccountDeletionProgress) =
            valid && saved?.operationId == expected.operationId

        override suspend fun advance(
            expected: AccountDeletionProgress,
            next: AccountDeletionStage
        ): Boolean {
            events += next.name
            if (saved != expected) return false
            saved = expected.copy(stage = next)
            return true
        }

        override suspend fun clearLocalProfile(expected: AccountDeletionProgress): Boolean {
            events += "clear"
            if (!clearSucceeds || !valid || saved != expected) return false
            erased = true
            saved = expected.copy(stage = AccountDeletionStage.LOCAL_CLEARED)
            return true
        }

        override suspend fun ensureInstallationMarker(expected: AccountDeletionProgress): Boolean {
            events += "marker"
            return markerSucceeds
        }

        override suspend fun markComplete(expected: AccountDeletionProgress): Boolean {
            events += "complete"
            saved = expected.copy(stage = AccountDeletionStage.COMPLETE)
            return true
        }
    }
}
