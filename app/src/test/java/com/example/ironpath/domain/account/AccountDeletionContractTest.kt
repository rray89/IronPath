package com.example.ironpath.domain.account

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionContractTest {
    @Test
    fun `profile generation token captures its first value for the lifetime of the screen`() =
        runTest {
            val reader = ContextReader(profileGeneration = 7)
            val token = ProfileGenerationToken(reader)

            assertEquals(7L, token.initialize())
            reader.context = reader.context.copy(profileGeneration = 8)
            assertEquals(7L, token.initialize())
            assertEquals(7L, token.current())
            assertEquals(1, reader.reads)
        }

    @Test
    fun `clearing a deleted session treats absence as complete and protects a different owner`() =
        runTest {
            val deleted = AccountId("deleted-incarnation")
            val another =
                AccountProfile(AccountId("another-incarnation"), "Other", "other@example.invalid")
            val absent = SessionAdapter()
            assertTrue(absent.clearDeletedSession(deleted))
            assertEquals(0, absent.clears)

            val foreign = SessionAdapter(another)
            assertFalse(foreign.clearDeletedSession(deleted))
            assertEquals(0, foreign.clears)
        }

    @Test
    fun `clearing a deleted session clears only the matching incarnation and returns its result`() =
        runTest {
            val deleted =
                AccountProfile(AccountId("deleted-incarnation"), "Demo", "demo@example.invalid")
            val successful = SessionAdapter(deleted)
            assertTrue(successful.clearDeletedSession(deleted.id))
            assertEquals(1, successful.clears)

            val failed = SessionAdapter(deleted, clearResult = false)
            assertFalse(failed.clearDeletedSession(deleted.id))
            assertEquals(1, failed.clears)
        }

    private class ContextReader(profileGeneration: Long) : AccountContextReader {
        var reads = 0
        var context =
            LocalAccountContext(
                ownerUid = null,
                localDataIsEmpty = false,
                conflict = PersistedConflictContext(null, 0, null, null, "install", 0, 0),
                profileGeneration = profileGeneration,
            )

        override val changes = emptyFlow<Unit>()

        override suspend fun read(): LocalAccountContext {
            reads++
            return context
        }
    }

    private class SessionAdapter(
        var session: AccountProfile? = null,
        private val clearResult: Boolean = true,
    ) : AccountSessionAdapter {
        var clears = 0

        override suspend fun requestGoogleCredential(requestId: Long) = CredentialResult.Cancelled

        override suspend fun commitGoogleCredential(
            candidate: PendingGoogleCredential,
        ) = CredentialCommitResult.Failed(AccountFailureReason.Unknown)

        override suspend fun readSession() = session

        override suspend fun clearSession(): Boolean {
            clears++
            if (clearResult) session = null
            return clearResult
        }

        override suspend fun remoteSnapshot(accountId: AccountId) = RemoteSnapshotPresence.Absent
    }
}
