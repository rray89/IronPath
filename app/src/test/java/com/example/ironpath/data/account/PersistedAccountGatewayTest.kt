package com.example.ironpath.data.account

import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.data.backup.LocalProfileResetResult
import com.example.ironpath.data.backup.LocalProfileResetter
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
    fun `completed empty lineage supersedes older observation and Back cannot cancel established account`() =
        runTest {
            val source =
                Source().apply {
                    session = profile
                    remote = RemoteSnapshotPresence.Complete("old", 1, "installation")
                }
            val reader =
                Reader().apply {
                    context =
                        context.copy(
                            ownerUid = profile.id.opaqueValue,
                            conflict =
                                PersistedConflictContext(
                                    "old",
                                    1,
                                    "old-digest",
                                    "installation",
                                    "installation",
                                    4,
                                    4
                                )
                        )
                }
            val gateway = gateway(source, reader)
            gateway.refresh()
            reader.context =
                reader.context.copy(
                    localDataIsEmpty = true,
                    conflict =
                        PersistedConflictContext(
                            "empty",
                            2,
                            "empty-digest",
                            "installation",
                            "installation",
                            5,
                            5
                        )
                )
            gateway.refreshLocal()
            assertTrue(gateway.state.value is AccountState.SignedIn)
            assertEquals(AccountActionResult.Unavailable, gateway.cancelDataChoice())
            assertEquals(profile, source.session)
            assertEquals(1, source.remoteReads)
        }

    @Test
    fun `local reconstruction never reads remote and keeps an explicit observation`() = runTest {
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val gateway = gateway(source, reader)
        gateway.refreshLocal()
        assertEquals(0, source.remoteReads)
        assertTrue(gateway.state.value is AccountState.SignedIn)
        source.remote = RemoteSnapshotPresence.Complete("remote", 7, "another-installation")
        gateway.refresh()
        assertEquals(1, source.remoteReads)
        assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
        gateway.refreshLocal()
        assertEquals(1, source.remoteReads)
        assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
        assertEquals(AccountActionResult.Unavailable, gateway.cancelDataChoice())
        assertEquals(profile, source.session)
        gateway.refreshLocal()
        assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
        assertEquals(1, source.remoteReads)
    }

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
        assertEquals(
            AccountActionResult.Unavailable,
            gateway.signOut(
                SignOutRequest(
                    profile.id,
                    state.sessionEpoch,
                    SignOutDataChoice.RemoveData,
                )
            )
        )
        assertEquals(profile, source.session)
        assertEquals(AccountActionResult.Unavailable, gateway.deleteAccount())
        assertEquals(AccountActionResult.Unavailable, gateway.reauthenticate())
    }

    @Test
    fun `a fresh controller reconstructs data choice and only explicit sign out clears session`() =
        runTest {
            val source = Source()
            gateway(source).startGoogleSignIn()
            val recreated = gateway(source)
            recreated.refresh()
            val awaiting = recreated.state.value as AccountState.AwaitingDataChoice
            assertEquals(AccountActionResult.Unavailable, recreated.cancelDataChoice())
            assertEquals(profile, source.session)
            assertEquals(
                AccountActionResult.Completed,
                recreated.signOut(
                    SignOutRequest(
                        awaiting.accountId,
                        awaiting.sessionEpoch,
                        SignOutDataChoice.KeepData
                    )
                ),
            )
            assertNull(source.session)
            assertEquals(AccountState.LocalOnly, recreated.state.value)
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
    fun `failed save never reports signed in and only explicit sign out clears a saved session`() =
        runTest {
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
            val state = gateway.state.value as AccountState.AwaitingDataChoice
            assertEquals(AccountActionResult.Unavailable, gateway.cancelDataChoice())
            assertNotNull(source.session)
            assertEquals(
                AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                gateway.signOut(
                    SignOutRequest(state.accountId, state.sessionEpoch, SignOutDataChoice.KeepData)
                ),
            )
            assertNotNull(source.session)
            source.clearSucceeds = true
            val retry = gateway.state.value as AccountState.AwaitingDataChoice
            assertEquals(
                AccountActionResult.Completed,
                gateway.signOut(
                    SignOutRequest(retry.accountId, retry.sessionEpoch, SignOutDataChoice.KeepData)
                ),
            )
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
    fun `selected credential finishes durable sign in when caller leaves while waiting for gate`() =
        runTest {
            val gate = AccountSessionOperationGate()
            val source = Source()
            val gateway = gateway(source, gate = gate)
            val operationStarted = CompletableDeferred<Unit>()
            val finishOperation = CompletableDeferred<Unit>()
            val holder = launch {
                gate.withManualOperation(waitForTurn = true, unavailable = Unit) {
                    operationStarted.complete(Unit)
                    finishOperation.await()
                }
            }
            operationStarted.await()

            val signIn = launch { gateway.startGoogleSignIn() }
            runCurrent()
            assertEquals(AccountState.SavingSignIn, gateway.state.value)

            signIn.cancel()
            finishOperation.complete(Unit)
            holder.join()
            signIn.join()

            assertEquals(profile, source.session)
            assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
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
    fun `cancellation during explicit sign out never leaves signing out busy`() = runTest {
        val source = Source()
        val gateway = gateway(source)
        gateway.startGoogleSignIn()
        val account = gateway.state.value as AccountState.AwaitingDataChoice
        source.clearStarted = CompletableDeferred()
        source.finishClear = CompletableDeferred()
        val operation = launch {
            gateway.signOut(
                SignOutRequest(
                    account.accountId,
                    account.sessionEpoch,
                    SignOutDataChoice.KeepData,
                )
            )
        }
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
    fun `ordinary cancellation cannot clear persisted session when local context cannot be reconstructed`() =
        runTest {
            val source = Source().apply { session = profile }
            val reader = Reader().apply { failure = IllegalStateException("private") }
            val gateway = gateway(source, reader)
            gateway.refresh()
            assertEquals(
                AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable),
                gateway.state.value
            )
            assertEquals(AccountActionResult.Unavailable, gateway.cancelDataChoice())
            assertEquals(profile, source.session)
        }

    @Test
    fun `keep-data sign-out clears only the session and does not require Room reads`() = runTest {
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val resetter = TestResetter(reader)
        val gate = AccountSessionOperationGate()
        val gateway = gateway(source, reader, resetter = resetter, gate = gate)
        gateway.refreshLocal()
        val state = gateway.state.value as AccountState.SignedIn
        val originalContext = reader.context
        reader.failure = IllegalStateException("Room unavailable")

        assertEquals(
            AccountActionResult.Completed,
            gateway.signOut(
                SignOutRequest(state.accountId, state.sessionEpoch, SignOutDataChoice.KeepData)
            ),
        )

        assertNull(source.session)
        assertEquals(originalContext, reader.context)
        assertEquals(0, resetter.resetCalls)
        assertEquals(AccountState.LocalOnly, gateway.state.value)
    }

    @Test
    fun `remove-data sign-out requires confirmation and commits reset before clearing session`() =
        runTest {
            val source = Source().apply { session = profile }
            val reader =
                Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
            val resetter = TestResetter(reader)
            val gateway = gateway(source, reader, resetter = resetter)
            gateway.refreshLocal()
            val state = gateway.state.value as AccountState.SignedIn

            assertEquals(
                AccountActionResult.Unavailable,
                gateway.signOut(
                    SignOutRequest(
                        state.accountId,
                        state.sessionEpoch,
                        SignOutDataChoice.RemoveData
                    )
                ),
            )
            assertEquals(0, resetter.resetCalls)
            assertEquals(profile, source.session)

            assertEquals(
                AccountActionResult.Completed,
                gateway.signOut(
                    SignOutRequest(
                        state.accountId,
                        state.sessionEpoch,
                        SignOutDataChoice.RemoveData,
                        removeDataConfirmed = true,
                    )
                ),
            )

            assertNull(source.session)
            assertEquals(1, resetter.resetCalls)
            assertEquals(1, source.clearCalls)
            assertNull(reader.context.ownerUid)
            assertTrue(reader.context.localDataIsEmpty)
            assertNull(reader.context.pendingSignOutUid)
            assertEquals(AccountState.LocalOnly, gateway.state.value)
        }

    @Test
    fun `failed Room reset restores account with a fresh epoch and can be retried`() = runTest {
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val resetter = TestResetter(reader).apply { result = LocalProfileResetResult.NotCommitted }
        val gateway = gateway(source, reader, resetter = resetter)
        gateway.refreshLocal()
        val state = gateway.state.value as AccountState.SignedIn

        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            gateway.signOut(
                SignOutRequest(
                    state.accountId,
                    state.sessionEpoch,
                    SignOutDataChoice.RemoveData,
                    removeDataConfirmed = true,
                )
            ),
        )

        assertEquals(profile, source.session)
        assertEquals(0, source.clearCalls)
        assertEquals(1, resetter.resetCalls)
        assertEquals(profile.id.opaqueValue, reader.context.ownerUid)
        val restored = gateway.state.value as AccountState.SignedIn
        assertNotEquals(state.sessionEpoch, restored.sessionEpoch)

        resetter.result = LocalProfileResetResult.Committed(installationMarkerUpdated = true)
        assertEquals(
            AccountActionResult.Completed,
            gateway.signOut(
                SignOutRequest(
                    restored.accountId,
                    restored.sessionEpoch,
                    SignOutDataChoice.RemoveData,
                    removeDataConfirmed = true,
                )
            ),
        )
        assertNull(source.session)
        assertEquals(2, resetter.resetCalls)
    }

    @Test
    fun `reset exception after Room commit uses journal before clearing session`() = runTest {
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val resetter = TestResetter(reader).apply { throwAfterCommit = true }
        val gateway = gateway(source, reader, resetter = resetter)
        gateway.refreshLocal()
        val state = gateway.state.value as AccountState.SignedIn

        assertEquals(
            AccountActionResult.Completed,
            gateway.signOut(
                SignOutRequest(
                    state.accountId,
                    state.sessionEpoch,
                    SignOutDataChoice.RemoveData,
                    removeDataConfirmed = true,
                )
            ),
        )

        assertNull(source.session)
        assertEquals(1, resetter.resetCalls)
        assertNull(reader.context.pendingSignOutUid)
        assertEquals(AccountState.LocalOnly, gateway.state.value)
    }

    @Test
    fun `post-reset session read failure remains pending and retries without resetting again`() =
        runTest {
            val source = Source().apply { session = profile }
            val reader =
                Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
            val resetter =
                TestResetter(reader).apply {
                    afterReset = {
                        source.readFailure = IllegalStateException("temporary read failure")
                    }
                }
            val gateway = gateway(source, reader, resetter = resetter)
            gateway.refreshLocal()
            val state = gateway.state.value as AccountState.SignedIn

            assertEquals(
                AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                gateway.signOut(
                    SignOutRequest(
                        state.accountId,
                        state.sessionEpoch,
                        SignOutDataChoice.RemoveData,
                        removeDataConfirmed = true,
                    )
                ),
            )
            val pending = gateway.state.value as AccountState.SignOutPending
            assertEquals(profile, pending.profile)
            assertEquals(profile, source.session)
            assertEquals(profile.id.opaqueValue, reader.context.pendingSignOutUid)
            assertEquals(1, resetter.resetCalls)

            assertEquals(
                AccountActionResult.Completed,
                gateway.signOut(
                    SignOutRequest(
                        pending.accountId,
                        pending.sessionEpoch,
                        SignOutDataChoice.KeepData,
                    )
                ),
            )

            assertNull(source.session)
            assertEquals(1, resetter.resetCalls)
            assertNull(reader.context.pendingSignOutUid)
            assertEquals(AccountState.LocalOnly, gateway.state.value)
        }

    @Test
    fun `post-clear session read failure retry finalizes an already cleared session`() = runTest {
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val resetter = TestResetter(reader)
        source.afterClear = { source.readFailure = IllegalStateException("temporary read failure") }
        val gateway = gateway(source, reader, resetter = resetter)
        gateway.refreshLocal()
        val state = gateway.state.value as AccountState.SignedIn

        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            gateway.signOut(
                SignOutRequest(
                    state.accountId,
                    state.sessionEpoch,
                    SignOutDataChoice.RemoveData,
                    removeDataConfirmed = true,
                )
            ),
        )
        val pending = gateway.state.value as AccountState.SignOutPending
        assertNull(source.session)
        assertEquals(profile.id.opaqueValue, reader.context.pendingSignOutUid)

        assertEquals(
            AccountActionResult.Completed,
            gateway.signOut(
                SignOutRequest(
                    pending.accountId,
                    pending.sessionEpoch,
                    SignOutDataChoice.KeepData,
                )
            ),
        )

        assertEquals(1, source.clearCalls)
        assertEquals(1, resetter.resetCalls)
        assertNull(reader.context.pendingSignOutUid)
        assertEquals(AccountState.LocalOnly, gateway.state.value)
    }

    @Test
    fun `failed session read while resuming pending removal keeps a fresh retry epoch`() = runTest {
        val source = Source().apply { session = profile }
        val reader =
            Reader().apply {
                context =
                    context.copy(
                        localDataIsEmpty = true,
                        pendingSignOutUid = profile.id.opaqueValue
                    )
            }
        val resetter = TestResetter(reader)
        val gateway = gateway(source, reader, resetter = resetter)
        gateway.refreshLocal()
        val firstPending = gateway.state.value as AccountState.SignOutPending
        source.readFailure = IllegalStateException("temporary read failure")

        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            gateway.signOut(
                SignOutRequest(
                    firstPending.accountId,
                    firstPending.sessionEpoch,
                    SignOutDataChoice.KeepData,
                )
            ),
        )
        val retryPending = gateway.state.value as AccountState.SignOutPending
        assertNotEquals(firstPending.sessionEpoch, retryPending.sessionEpoch)

        assertEquals(
            AccountActionResult.Completed,
            gateway.signOut(
                SignOutRequest(
                    retryPending.accountId,
                    retryPending.sessionEpoch,
                    SignOutDataChoice.KeepData,
                )
            ),
        )
        assertNull(source.session)
        assertEquals(0, resetter.resetCalls)
        assertEquals(AccountState.LocalOnly, gateway.state.value)
    }

    @Test
    fun `committed removal recreation retries only session clear`() = runTest {
        val source =
            Source().apply {
                session = profile
                clearSucceeds = false
            }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val resetter = TestResetter(reader)
        val first = gateway(source, reader, resetter = resetter)
        first.refreshLocal()
        val state = first.state.value as AccountState.SignedIn
        val removeRequest =
            SignOutRequest(
                state.accountId,
                state.sessionEpoch,
                SignOutDataChoice.RemoveData,
                removeDataConfirmed = true,
            )

        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            first.signOut(removeRequest),
        )
        assertTrue(first.state.value is AccountState.SignOutPending)
        assertEquals(profile, source.session)
        assertEquals(profile.id.opaqueValue, reader.context.pendingSignOutUid)
        assertEquals(1, resetter.resetCalls)

        source.clearSucceeds = true
        val recreated = gateway(source, reader, resetter = resetter)
        assertEquals(AccountActionResult.Completed, recreated.refreshLocal())
        val pending = recreated.state.value as AccountState.SignOutPending
        assertEquals(
            AccountActionResult.Completed,
            recreated.signOut(
                SignOutRequest(pending.accountId, pending.sessionEpoch, SignOutDataChoice.KeepData)
            ),
        )

        assertNull(source.session)
        assertEquals(2, source.clearCalls)
        assertEquals(1, resetter.resetCalls)
        assertNull(reader.context.pendingSignOutUid)
        assertNull(reader.context.ownerUid)
        assertEquals(AccountState.LocalOnly, recreated.state.value)
    }

    @Test
    fun `changed account during removal is reconstructed without clearing its session`() = runTest {
        val other =
            AccountProfile(AccountId("another-account"), "Other Athlete", "other@example.invalid")
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val resetter = TestResetter(reader).apply { afterReset = { source.session = other } }
        val gateway = gateway(source, reader, resetter = resetter)
        gateway.refreshLocal()
        val state = gateway.state.value as AccountState.SignedIn

        assertEquals(
            AccountActionResult.Cancelled,
            gateway.signOut(
                SignOutRequest(
                    state.accountId,
                    state.sessionEpoch,
                    SignOutDataChoice.RemoveData,
                    removeDataConfirmed = true,
                )
            ),
        )

        assertEquals(other, source.session)
        assertEquals(0, source.clearCalls)
        assertNull(reader.context.pendingSignOutUid)
        assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
        assertEquals(other.id, (gateway.state.value as AccountState.AwaitingDataChoice).accountId)
    }

    @Test
    fun `reconstruction read failure after old journal cleanup stays refreshable`() = runTest {
        val other =
            AccountProfile(AccountId("another-account"), "Other Athlete", "other@example.invalid")
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val resetter =
            TestResetter(reader).apply {
                afterReset = { source.session = other }
                afterClearPending = {
                    reader.failure = IllegalStateException("temporary read failure")
                }
            }
        val gateway = gateway(source, reader, resetter = resetter)
        gateway.refreshLocal()
        val state = gateway.state.value as AccountState.SignedIn

        assertEquals(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            gateway.signOut(
                SignOutRequest(
                    state.accountId,
                    state.sessionEpoch,
                    SignOutDataChoice.RemoveData,
                    removeDataConfirmed = true,
                )
            ),
        )
        assertTrue(gateway.state.value is AccountState.RecoverableError)
        assertNull(reader.context.pendingSignOutUid)
        assertEquals(other, source.session)

        reader.failure = null
        assertEquals(AccountActionResult.Completed, gateway.refreshLocal())
        assertTrue(gateway.state.value is AccountState.AwaitingDataChoice)
        assertEquals(other.id, (gateway.state.value as AccountState.AwaitingDataChoice).accountId)
    }

    @Test
    fun `stale account epoch cannot sign out a later session`() = runTest {
        val source = Source().apply { session = profile }
        val reader = Reader().apply { context = context.copy(ownerUid = profile.id.opaqueValue) }
        val gate = AccountSessionOperationGate()
        val resetter = TestResetter(reader)
        val gateway = gateway(source, reader, resetter = resetter, gate = gate)
        gateway.refreshLocal()
        val state = gateway.state.value as AccountState.SignedIn
        gate.withSessionMutation { _, _ ->
            AccountSessionOperationGate.MutationResult(Unit, reopenAdmission = true)
        }

        assertEquals(
            AccountActionResult.Cancelled,
            gateway.signOut(
                SignOutRequest(state.accountId, state.sessionEpoch, SignOutDataChoice.KeepData)
            ),
        )
        assertEquals(profile, source.session)
        assertEquals(0, source.clearCalls)
        assertEquals(0, resetter.resetCalls)
    }

    private fun gateway(
        source: Source,
        reader: Reader = Reader(),
        result: InstallationValidationResult = InstallationValidationResult.Validated,
        resetter: TestResetter? = null,
        gate: AccountSessionOperationGate = AccountSessionOperationGate(),
    ) =
        PersistedAccountGateway(
            source,
            reader,
            object : InstallationGuard {
                override suspend fun validate() = result
            },
            resetter ?: TestResetter(reader),
            gate,
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

    private class TestResetter(private val reader: Reader) : LocalProfileResetter {
        var resetCalls = 0
        var clearPendingCalls = 0
        var afterReset: (() -> Unit)? = null
        var afterClearPending: (() -> Unit)? = null
        var throwAfterCommit = false
        var result: LocalProfileResetResult = LocalProfileResetResult.Committed(true)

        override suspend fun resetLocalProfile(
            pendingSignOutUid: String?
        ): LocalProfileResetResult {
            resetCalls++
            if (result is LocalProfileResetResult.Committed) {
                val old = reader.context
                reader.context =
                    old.copy(
                        ownerUid = null,
                        localDataIsEmpty = true,
                        pendingSignOutUid = pendingSignOutUid,
                        conflict =
                            old.conflict.copy(
                                lastObservedRemoteBackupId = null,
                                lastObservedRemoteGeneration = 0,
                                lastObservedRemoteDigest = null,
                                lastObservedSourceInstallationId = null,
                                currentInstallationId = "reset-${resetCalls}",
                                localChangeRevision = 0,
                                lastCompleteLocalRevision = 0,
                            ),
                    )
                afterReset?.invoke()
                if (throwAfterCommit) error("ambiguous post-commit result")
            }
            return result
        }

        override suspend fun clearPendingSignOut(uid: String): Boolean {
            clearPendingCalls++
            if (reader.context.pendingSignOutUid != uid) return false
            reader.context = reader.context.copy(pendingSignOutUid = null)
            afterClearPending?.invoke()
            return true
        }
    }

    private class Source : AccountSessionAdapter {
        var session: AccountProfile? = null
        var readFailure: Exception? = null
        var afterClear: (() -> Unit)? = null
        var result: CredentialResult = CredentialResult.Selected(profile)
        var remote: RemoteSnapshotPresence = RemoteSnapshotPresence.Absent
        var pending: CompletableDeferred<CredentialResult>? = null
        var writeSucceeds = true
        var clearSucceeds = true
        var clearCalls = 0
        var requests = 0
        var remoteReads = 0
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

        override suspend fun readSession(): AccountProfile? {
            readFailure?.let {
                readFailure = null
                throw it
            }
            return session
        }

        override suspend fun saveSession(profile: AccountProfile): Boolean {
            saveStarted?.complete(Unit)
            finishSave?.await()
            if (writeSucceeds) session = profile
            return writeSucceeds
        }

        override suspend fun clearSession(): Boolean {
            clearCalls++
            clearStarted?.complete(Unit)
            finishClear?.await()
            if (clearSucceeds) {
                session = null
                afterClear?.invoke()
            }
            return clearSucceeds
        }

        override suspend fun remoteSnapshot(accountId: AccountId): RemoteSnapshotPresence {
            remoteReads++
            return remote
        }
    }

    private companion object {
        val profile =
            AccountProfile(AccountId("demo-account"), "Demo Athlete", "athlete@example.invalid")
    }
}
