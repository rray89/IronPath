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
    private val request = AccountDeletionRequest(account, 4, 2)
    private val operation = "b0128305-f28b-4eba-b389-bb4c736f76c9"
    private val events = mutableListOf<String>()
    private val store = FakeStore()
    private val identity = FakeIdentity()
    private val service = FakeService()
    private val gate = AccountSessionOperationGate()
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

    @Test
    fun `cancelled Google reauthentication changes no durable data`() = runTest {
        identity.result = DeletionReauthentication.Cancelled
        assertEquals(AccountDeletionResult.Cancelled, manager().delete(request))
        assertNull(store.saved)
        assertEquals(listOf("capability", "reauth"), events)
    }

    @Test
    fun `missing actual capability blocks before reauth and preparation`() = runTest {
        service.available = false
        assertEquals(AccountDeletionResult.Unavailable, manager().delete(request))
        assertNull(store.saved)
        assertEquals(listOf("capability"), events)
    }

    @Test
    fun `remote completion precedes local deletion and signout`() = runTest {
        assertEquals(AccountDeletionResult.Completed, manager().delete(request))
        assertEquals(
            listOf(
                "capability",
                "reauth",
                "prepare",
                "start",
                "BACKUPS_PURGED",
                "ACCOUNT_TOMBSTONED",
                "clear",
                "marker",
                "signout",
                "complete"
            ),
            events
        )
        assertEquals(AccountDeletionStage.COMPLETE, store.saved?.stage)
    }

    @Test
    fun `lost start receipt resumes without credentials after identity removal`() = runTest {
        service.startResult = DeletionServiceResult.Unavailable
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        assertFalse(events.contains("clear"))
        identity.current = null
        events.clear()
        assertEquals(AccountDeletionResult.Completed, manager().recoverAtStartup())
        assertEquals("resume", events.first())
        assertFalse(events.contains("reauth"))
        assertFalse(events.contains("start"))
    }

    @Test
    fun `unaccepted durable request never starts automatically and explicit retry needs reauth`() =
        runTest {
            service.startResult = DeletionServiceResult.Unavailable
            manager().delete(request)
            service.resumeResult = DeletionServiceResult.Missing
            events.clear()
            assertTrue(manager().recoverAtStartup() is AccountDeletionResult.RetryRequired)
            assertEquals(listOf("resume"), events)
            service.startResult = DeletionServiceResult.Complete
            events.clear()
            assertEquals(AccountDeletionResult.Completed, manager().retry())
            assertEquals(listOf("resume", "capability", "reauth", "start"), events.take(4))
        }

    @Test
    fun `pending cancel reauth preserves durable barrier`() = runTest {
        service.startResult = DeletionServiceResult.Pending
        manager().delete(request)
        service.resumeResult = DeletionServiceResult.Missing
        identity.result = DeletionReauthentication.Cancelled
        assertTrue(manager().retry() is AccountDeletionResult.RetryRequired)
        assertEquals(AccountDeletionStage.PREPARED, store.saved?.stage)
        assertFalse(events.contains("clear"))
    }

    @Test
    fun `new provider session cannot clear old local profile or sign out new account`() = runTest {
        service.startResult = DeletionServiceResult.Pending
        manager().delete(request)
        identity.current = AccountId("other")
        events.clear()
        assertTrue(manager().retry() is AccountDeletionResult.RetryRequired)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `changed local owner or generation blocks remote deletion`() = runTest {
        store.valid = false
        assertEquals(AccountDeletionResult.Unavailable, manager().delete(request))
        assertFalse(events.contains("start"))
        assertNull(store.saved)
    }

    @Test
    fun `foreign explicit owner and transferred installation cannot start deletion`() = runTest {
        assertEquals(
            AccountDeletionResult.Unavailable,
            manager().delete(request.copy(expectedLocalOwnerUid = "other"))
        )
        installation = InstallationValidationResult.Transferred
        assertEquals(AccountDeletionResult.Unavailable, manager().delete(request))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `changed session during chooser blocks journal and remote start`() = runTest {
        identity.afterReauth = { identity.current = AccountId("other") }
        assertEquals(AccountDeletionResult.Cancelled, manager().delete(request))
        assertNull(store.saved)
    }

    @Test
    fun `pre journal authentication error is typed and harmless`() = runTest {
        identity.result =
            DeletionReauthentication.Failed(AccountFailureReason.ReauthenticationRequired)
        assertEquals(
            AccountDeletionResult.Failed(AccountFailureReason.ReauthenticationRequired),
            manager().delete(request)
        )
        assertNull(store.saved)
    }

    @Test
    fun `local transaction failure retains remote proof for recreation`() = runTest {
        store.clearSucceeds = false
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        assertEquals(AccountDeletionStage.ACCOUNT_TOMBSTONED, store.saved?.stage)
        store.clearSucceeds = true
        events.clear()
        assertEquals(AccountDeletionResult.Completed, manager().recoverAtStartup())
        assertFalse(events.contains("resume"))
        assertFalse(events.contains("reauth"))
    }

    @Test
    fun `marker and session failure retain local cleared stage until retry`() = runTest {
        store.markerSucceeds = false
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        assertEquals(AccountDeletionStage.LOCAL_CLEARED, store.saved?.stage)
        store.markerSucceeds = true
        identity.clearSucceeds = false
        assertTrue(manager().retry() is AccountDeletionResult.RetryRequired)
        identity.clearSucceeds = true
        assertEquals(AccountDeletionResult.Completed, manager().retry())
        assertEquals(1, events.count { it == "clear" })
    }

    @Test
    fun `duplicate final confirmation does not prepare or submit another job`() = runTest {
        service.startResult = DeletionServiceResult.Pending
        manager().delete(request)
        assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
        assertEquals(1, events.count { it == "start" })
        assertEquals(1, events.count { it == "prepare" })
    }

    @Test
    fun `profile change after reauth and while server runs cannot erase unrelated data`() =
        runTest {
            service.afterStart = { store.valid = false }
            assertTrue(manager().delete(request) is AccountDeletionResult.RetryRequired)
            assertFalse(events.contains("clear"))
        }

    @Test
    fun `startup without journal is idle even service unavailable`() = runTest {
        service.available = false
        assertEquals(AccountDeletionResult.Idle, manager().recoverAtStartup())
        assertTrue(events.isEmpty())
    }

    @Test
    fun `changed backend configuration leaves a durable job and local data locked`() = runTest {
        service.startResult = DeletionServiceResult.Pending
        manager().delete(request)
        assertEquals("synthetic-binding", store.saved?.serviceBinding)
        service.binding = "different-project-or-endpoint"
        events.clear()
        assertTrue(manager().retry() is AccountDeletionResult.RetryRequired)
        assertTrue(events.isEmpty())
        service.binding = "synthetic-binding"
        assertEquals(AccountDeletionResult.Completed, manager().retry())
    }

    private inner class FakeStore : AccountDeletionStore {
        var saved: AccountDeletionProgress? = null
        var valid = true
        var clearSucceeds = true
        var markerSucceeds = true

        override suspend fun journal() = saved

        override suspend fun prepare(request: AccountDeletionRequest): AccountDeletionProgress? {
            if (!valid) return null
            events += "prepare"
            return AccountDeletionProgress(
                    operation,
                    request.accountId,
                    request.sessionEpoch,
                    request.profileGeneration,
                    AccountDeletionStage.PREPARED,
                    request.expectedLocalOwnerUid,
                    request.serviceBinding,
                )
                .also { saved = it }
        }

        override suspend fun matchesProfile(expected: AccountDeletionProgress) = valid

        override suspend fun advance(
            expected: AccountDeletionProgress,
            next: AccountDeletionStage
        ): Boolean {
            events += next.name
            saved = expected.copy(stage = next)
            return true
        }

        override suspend fun clearLocalProfile(expected: AccountDeletionProgress): Boolean {
            if (!valid || !clearSucceeds) return false
            events += "clear"
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

    private inner class FakeIdentity : AccountDeletionIdentity {
        var current: AccountId? = account
        var result: DeletionReauthentication =
            DeletionReauthentication.Authenticated("synthetic-token")
        var afterReauth: () -> Unit = {}
        var clearSucceeds = true

        override suspend fun currentAccount() = current

        override suspend fun reauthenticate(account: AccountId): DeletionReauthentication {
            events += "reauth"
            afterReauth()
            return result
        }

        override suspend fun clearDeletedSession(account: AccountId): Boolean {
            events += "signout"
            if (clearSucceeds) current = null
            return clearSucceeds
        }
    }

    private inner class FakeService : AccountDeletionService {
        override var binding: String? = "synthetic-binding"
        var available = true
        var startResult: DeletionServiceResult = DeletionServiceResult.Complete
        var resumeResult: DeletionServiceResult = DeletionServiceResult.Complete
        var afterStart: () -> Unit = {}

        override suspend fun available(): Boolean {
            events += "capability"
            return available
        }

        override suspend fun start(operationId: String, token: String): DeletionServiceResult {
            assertEquals(operation, operationId)
            assertEquals(AccountDeletionStage.PREPARED, store.saved?.stage)
            events += "start"
            afterStart()
            return startResult
        }

        override suspend fun resume(operationId: String): DeletionServiceResult {
            assertEquals(operation, operationId)
            events += "resume"
            return resumeResult
        }
    }
}
