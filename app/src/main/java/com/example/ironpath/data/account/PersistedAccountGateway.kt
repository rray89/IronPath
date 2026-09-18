package com.example.ironpath.data.account

import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.domain.account.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Identity and read-only local context. No workout or ownership write is available here. */
@Singleton
class PersistedAccountGateway
@Inject
constructor(
    private val sessions: AccountSessionAdapter,
    private val localContext: AccountContextReader,
    private val installationGuard: InstallationGuard,
) : AccountGateway {
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Loading)
    override val state: StateFlow<AccountState> = mutableState
    private val mutex = Mutex()
    private var generation = 0L
    private var canCancel = false

    override suspend fun refresh(): AccountActionResult =
        mutex.withLock {
            if (
                mutableState.value == AccountState.SigningIn ||
                    mutableState.value == AccountState.CancellingDataChoice
            ) {
                return@withLock AccountActionResult.Unavailable
            }
            safely {
                validateInstallation()
                publishSession(readSession())
                AccountActionResult.Completed
            }
        }

    override suspend fun startGoogleSignIn(): AccountActionResult {
        val request =
            mutex.withLock {
                if (
                    mutableState.value == AccountState.SigningIn ||
                        mutableState.value == AccountState.CancellingDataChoice
                )
                    return AccountActionResult.Unavailable
                val preparation = safely {
                    validateInstallation()
                    val existing = readSession()
                    if (existing != null) {
                        publishSession(existing)
                        AccountActionResult.Unavailable
                    } else {
                        localContext.read()
                        AccountActionResult.Completed
                    }
                }
                if (preparation != AccountActionResult.Completed) return preparation
                canCancel = true
                mutableState.value = AccountState.SigningIn
                ++generation
            }
        val credential =
            try {
                sessions.requestGoogleCredential().also { currentCoroutineContext().ensureActive() }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (generation == request) {
                            generation++
                            canCancel = false
                            mutableState.value = AccountState.LocalOnly
                        }
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                CredentialResult.Failed(AccountFailureReason.ServiceUnavailable)
            }
        // A checked disk commit may finish after its caller is cancelled. Complete the short
        // durable transition and publish its actual outcome before releasing the operation lock.
        return withContext(NonCancellable) {
            mutex.withLock {
                if (generation != request) return@withLock AccountActionResult.Cancelled
                when (credential) {
                    CredentialResult.Cancelled -> {
                        canCancel = false
                        mutableState.value = AccountState.LocalOnly
                        AccountActionResult.Cancelled
                    }
                    is CredentialResult.Failed -> {
                        canCancel = false
                        fail(credential.reason)
                    }
                    is CredentialResult.Selected ->
                        safely {
                            validateInstallation()
                            check(sessions.saveSession(credential.profile))
                            publishSession(credential.profile)
                            AccountActionResult.Completed
                        }
                }
            }
        }
    }

    override suspend fun cancelDataChoice(): AccountActionResult =
        withContext(NonCancellable) {
            mutex.withLock {
                if (!canCancel) return@withLock AccountActionResult.Unavailable
                generation++
                mutableState.value = AccountState.CancellingDataChoice
                safely {
                    check(sessions.clearSession())
                    canCancel = false
                    mutableState.value = AccountState.LocalOnly
                    AccountActionResult.Completed
                }
            }
        }

    private suspend fun validateInstallation() {
        check(installationGuard.validate() != InstallationValidationResult.Failed)
    }

    private suspend fun publishSession(profile: AccountProfile?) {
        // Until local ownership has been verified, a persisted identity is a pending setup.
        canCancel = profile != null
        val local = localContext.read()
        val resolved =
            AccountStateResolver.resolve(
                authenticatedUid = profile?.id?.opaqueValue,
                localOwnerUid = local.ownerUid,
                localDataIsEmpty = local.localDataIsEmpty,
                remoteSnapshot =
                    profile?.let { sessions.remoteSnapshot(it.id) }
                        ?: RemoteSnapshotPresence.Absent,
                conflict = local.conflict,
            )
        canCancel = resolved is AccountState.AwaitingDataChoice
        mutableState.value =
            when (resolved) {
                is AccountState.SignedIn -> resolved.copy(profile = profile)
                is AccountState.AwaitingDataChoice -> resolved.copy(profile = profile)
                else -> resolved
            }
    }

    private suspend fun readSession(): AccountProfile? =
        try {
            sessions.readSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            canCancel = true
            throw failure
        }

    private suspend fun safely(block: suspend () -> AccountActionResult): AccountActionResult =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            fail(AccountFailureReason.LocalStateUnavailable)
        }

    private fun fail(reason: AccountFailureReason): AccountActionResult {
        mutableState.value = AccountState.RecoverableError(reason, canCancel)
        return AccountActionResult.Failed(reason)
    }

    override suspend fun reauthenticate(): AccountActionResult = AccountActionResult.Unavailable

    override suspend fun signOut(): AccountActionResult = AccountActionResult.Unavailable

    override suspend fun deleteAccount(): AccountActionResult = AccountActionResult.Unavailable
}
