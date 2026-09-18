package com.example.ironpath.data.account

import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.domain.account.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersistedAccountGatewayTest {
    @Test
    fun `sign in persists identity but never claims unclaimed data`() = runTest {
        val source = Source()
        val reader = Reader()
        val gateway = gateway(source, reader)
        assertEquals(AccountActionResult.Completed, gateway.startGoogleSignIn())
        val state = gateway.state.value as AccountState.AwaitingDataChoice
        assertEquals(profile.id, state.accountId)
        assertEquals(LocalOwnership.Unclaimed, state.context.ownership)
        assertEquals(profile, source.session)
        assertNull(reader.context.ownerUid)
        assertEquals(AccountActionResult.Unavailable, gateway.signOut())
        assertEquals(AccountActionResult.Unavailable, gateway.deleteAccount())
        assertEquals(AccountActionResult.Unavailable, gateway.reauthenticate())
    }

    @Test
    fun `a fresh controller reconstructs pending choice and cancellation persists`() = runTest {
        val source = Source()
        gateway(source).startGoogleSignIn()
        val recreated = gateway(source)
        recreated.refresh()
        assertTrue(recreated.state.value is AccountState.AwaitingDataChoice)
        assertEquals(AccountActionResult.Completed, recreated.cancelDataChoice())
        assertNull(source.session)
        val afterCancel = gateway(source)
        afterCancel.refresh()
        assertEquals(AccountState.LocalOnly, afterCancel.state.value)
    }

    @Test
    fun `same owner resumes and different owner remains blocked even when empty`() = runTest {
        val source = Source().apply { session = profile }
        val reader = Reader()
        reader.context = reader.context.copy(ownerUid = profile.id.opaqueValue)
        val gateway = gateway(source, reader)
        gateway.refresh()
        assertTrue(gateway.state.value is AccountState.SignedIn)
        assertEquals(AccountActionResult.Unavailable, gateway.cancelDataChoice())
        reader.context = reader.context.copy(ownerUid = "another-account", localDataIsEmpty = true)
        gateway.refresh()
        val state = gateway.state.value as AccountState.AwaitingDataChoice
        assertEquals(LocalOwnership.Account(AccountId("another-account")), state.context.ownership)
        assertTrue(state.context.localDataIsEmpty)
    }

    @Test
    fun `cancelled or failed chooser keeps the local app available`() = runTest {
        val source = Source().apply { result = CredentialResult.Cancelled }
        val gateway = gateway(source)
        assertEquals(AccountActionResult.Cancelled, gateway.startGoogleSignIn())
        assertEquals(AccountState.LocalOnly, gateway.state.value)
        source.result = CredentialResult.Failed(AccountFailureReason.Offline)
        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.Offline),
            gateway.startGoogleSignIn()
        )
        assertEquals(
            AccountState.RecoverableError(AccountFailureReason.Offline),
            gateway.state.value
        )
        assertNull(source.session)
    }

    @Test
    fun `duplicate sign in is rejected and cancelled late credentials never persist`() = runTest {
        val source = Source().apply { pending = CompletableDeferred() }
        val gateway = gateway(source)
        val original = async { gateway.startGoogleSignIn() }
        runCurrent()
        assertEquals(AccountState.SigningIn, gateway.state.value)
        assertEquals(AccountActionResult.Unavailable, gateway.startGoogleSignIn())
        assertEquals(1, source.requests)
        assertEquals(AccountActionResult.Completed, gateway.cancelDataChoice())
        source.pending!!.complete(CredentialResult.Selected(profile))
        assertEquals(AccountActionResult.Cancelled, original.await())
        assertNull(source.session)
        assertEquals(AccountState.LocalOnly, gateway.state.value)
    }

    @Test
    fun `failed save never reports signed in and failed clear remains retryable`() = runTest {
        val source = Source().apply { writeSucceeds = false }
        val gateway = gateway(source)
        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            gateway.startGoogleSignIn()
        )
        assertNull(source.session)
        source.writeSucceeds = true
        gateway.startGoogleSignIn()
        source.clearSucceeds = false
        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            gateway.cancelDataChoice()
        )
        assertNotNull(source.session)
        source.clearSucceeds = true
        assertEquals(AccountActionResult.Completed, gateway.cancelDataChoice())
        assertEquals(AccountState.LocalOnly, gateway.state.value)
    }

    @Test
    fun `installation or local reads fail closed before requesting credentials`() = runTest {
        val source = Source()
        val failedGuard = gateway(source, result = InstallationValidationResult.Failed)
        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            failedGuard.startGoogleSignIn()
        )
        assertEquals(0, source.requests)
        val reader = Reader().apply { failure = IllegalStateException("private payload") }
        val gateway = gateway(source, reader)
        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            gateway.refresh()
        )
        assertEquals(
            AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable),
            gateway.state.value
        )
    }

    @Test
    fun `new remote generation reconstructs a data choice without changing lineage`() = runTest {
        val source =
            Source().apply {
                session = profile
                remote = RemoteSnapshotPresence.Complete("remote", 2, "other-installation")
            }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val original = reader.context
        val gateway = gateway(source, reader)
        gateway.refresh()
        assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
        assertEquals(original, reader.context)
    }

    @Test
    fun `cancellation during durable save finishes a reconstructable transition`() = runTest {
        val source =
            Source().apply {
                saveStarted = CompletableDeferred()
                finishSave = CompletableDeferred()
            }
        val gateway = gateway(source)
        val operation = launch { gateway.startGoogleSignIn() }
        runCurrent()
        assertTrue(source.saveStarted!!.isCompleted)
        operation.cancel()
        source.finishSave!!.complete(Unit)
        operation.join()
        assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
        assertEquals(AccountActionResult.Completed, gateway.refresh())
        assertEquals(profile, source.session)
    }

    @Test
    fun `a cancellation ignoring chooser cannot persist its late result`() = runTest {
        val source =
            Source().apply {
                pending = CompletableDeferred()
                ignoreChooserCancellation = true
            }
        val gateway = gateway(source)
        val operation = launch { gateway.startGoogleSignIn() }
        runCurrent()
        operation.cancel()
        source.pending!!.complete(CredentialResult.Selected(profile))
        operation.join()
        assertNull(source.session)
        assertEquals(AccountState.LocalOnly, gateway.state.value)
        assertEquals(AccountActionResult.Completed, gateway.refresh())
    }

    @Test
    fun `cancellation during durable clear never leaves cancellation busy`() = runTest {
        val source = Source()
        val gateway = gateway(source)
        gateway.startGoogleSignIn()
        source.clearStarted = CompletableDeferred()
        source.finishClear = CompletableDeferred()
        val operation = launch { gateway.cancelDataChoice() }
        runCurrent()
        assertTrue(source.clearStarted!!.isCompleted)
        operation.cancel()
        source.finishClear!!.complete(Unit)
        operation.join()
        assertEquals(AccountState.LocalOnly, gateway.state.value)
        assertEquals(AccountActionResult.Completed, gateway.refresh())
        assertNull(source.session)
    }

    @Test
    fun `persisted session can be cancelled when local context cannot be reconstructed`() =
        runTest {
            val source = Source().apply { session = profile }
            val reader = Reader().apply { failure = IllegalStateException("private") }
            val gateway = gateway(source, reader)
            gateway.refresh()
            assertEquals(
                AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable, true),
                gateway.state.value
            )
            assertEquals(AccountActionResult.Completed, gateway.cancelDataChoice())
            assertNull(source.session)
        }

    private fun gateway(
        source: Source,
        reader: Reader = Reader(),
        result: InstallationValidationResult = InstallationValidationResult.Validated
    ) =
        PersistedAccountGateway(
            source,
            reader,
            object : InstallationGuard {
                override suspend fun validate() = result
            }
        )

    private class Reader : AccountContextReader {
        var context =
            LocalAccountContext(
                null,
                false,
                PersistedConflictContext(null, 0, null, null, "installation", 4, 0)
            )
        var failure: Exception? = null
        override val changes = emptyFlow<Unit>()

        override suspend fun read(): LocalAccountContext {
            failure?.let { throw it }
            return context
        }
    }

    private class Source : AccountSessionAdapter {
        var session: AccountProfile? = null
        var result: CredentialResult = CredentialResult.Selected(profile)
        var remote: RemoteSnapshotPresence = RemoteSnapshotPresence.Absent
        var pending: CompletableDeferred<CredentialResult>? = null
        var writeSucceeds = true
        var clearSucceeds = true
        var requests = 0
        var ignoreChooserCancellation = false
        var saveStarted: CompletableDeferred<Unit>? = null
        var finishSave: CompletableDeferred<Unit>? = null
        var clearStarted: CompletableDeferred<Unit>? = null
        var finishClear: CompletableDeferred<Unit>? = null

        override suspend fun requestGoogleCredential(): CredentialResult {
            requests++
            return if (ignoreChooserCancellation)
                withContext(NonCancellable) { pending?.await() ?: result }
            else pending?.await() ?: result
        }

        override suspend fun readSession() = session

        override suspend fun saveSession(profile: AccountProfile): Boolean {
            saveStarted?.complete(Unit)
            finishSave?.await()
            if (writeSucceeds) session = profile
            return writeSucceeds
        }

        override suspend fun clearSession(): Boolean {
            clearStarted?.complete(Unit)
            finishClear?.await()
            if (clearSucceeds) session = null
            return clearSucceeds
        }

        override suspend fun remoteSnapshot(accountId: AccountId) = remote
    }

    private companion object {
        val profile =
            AccountProfile(AccountId("demo-account"), "Demo Athlete", "athlete@example.invalid")
    }
}
