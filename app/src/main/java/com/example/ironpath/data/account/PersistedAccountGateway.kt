package com.example.ironpath.data.account

import com.example.ironpath.data.backup.InstallationGuard
import com.example.ironpath.data.backup.InstallationValidationResult
import com.example.ironpath.data.backup.LocalProfileResetResult
import com.example.ironpath.data.backup.LocalProfileResetter
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
    private val localProfileResetter: LocalProfileResetter,
    private val operationGate: AccountSessionOperationGate,
) : AccountGateway {
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Loading)
    override val state: StateFlow<AccountState> = mutableState
    private val mutex = Mutex()
    private var generation = 0L
    private var canCancel = false
    private var observedRemote: Pair<AccountId, RemoteSnapshotPresence>? = null

    override suspend fun refresh(): AccountActionResult = refreshContext(inspectRemote = true)

    override suspend fun refreshLocal(): AccountActionResult = refreshContext(inspectRemote = false)

    private suspend fun refreshContext(inspectRemote: Boolean): AccountActionResult =
        mutex.withLock {
            if (mutableState.value.isTransitioning())
                return@withLock AccountActionResult.Unavailable
            safely {
                val profile = readSession()
                val local =
                    try {
                        localContext.read()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        canCancel = profile != null
                        return@safely fail(AccountFailureReason.LocalStateUnavailable)
                    }
                val pending = local.pendingSignOutUid
                when {
                    pending != null && profile?.id?.opaqueValue == pending -> {
                        operationGate.closeAdmission()
                        canCancel = false
                        mutableState.value =
                            AccountState.SignOutPending(
                                AccountId(pending),
                                profile,
                                operationGate.sessionEpoch,
                            )
                        AccountActionResult.Completed
                    }
                    pending != null && profile == null -> {
                        check(localProfileResetter.clearPendingSignOut(pending))
                        observedRemote = null
                        canCancel = false
                        mutableState.value = AccountState.LocalOnly
                        operationGate.reopenAdmission()
                        AccountActionResult.Completed
                    }
                    pending != null -> {
                        // The old account is no longer the persisted session. Its removal is
                        // already committed, so retire only the journal and reconstruct the new
                        // account without clearing or claiming its data.
                        check(localProfileResetter.clearPendingSignOut(pending))
                        validateInstallation()
                        publishSession(profile, inspectRemote, localContext.read())
                        reopenAdmissionIfStable()
                        AccountActionResult.Completed
                    }
                    else -> {
                        validateInstallation()
                        publishSession(profile, inspectRemote, local)
                        reopenAdmissionIfStable()
                        AccountActionResult.Completed
                    }
                }
            }
        }

    override suspend fun startGoogleSignIn(): AccountActionResult {
        val request =
            mutex.withLock {
                if (
                    mutableState.value.isTransitioning() ||
                        mutableState.value is AccountState.SignOutPending
                )
                    return AccountActionResult.Unavailable
                val preparation = safely {
                    validateInstallation()
                    val existing = readSession()
                    val local = localContext.read()
                    if (local.pendingSignOutUid != null)
                        fail(AccountFailureReason.LocalStateUnavailable)
                    if (existing != null) {
                        publishSession(existing, local = local)
                        AccountActionResult.Unavailable
                    } else {
                        AccountActionResult.Completed
                    }
                }
                if (preparation != AccountActionResult.Completed) return preparation
                canCancel = true
                mutableState.value = AccountState.SigningIn
                ++generation
            }
        val expectedEpoch = operationGate.sessionEpoch
        val credential =
            try {
                sessions.requestGoogleCredential().also { currentCoroutineContext().ensureActive() }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (generation == request && mutableState.value == AccountState.SigningIn) {
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

        if (credential !is CredentialResult.Selected) {
            return mutex.withLock {
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
                    is CredentialResult.Selected -> error("Handled above")
                }
            }
        }

        return try {
            operationGate.withSessionMutation { previousEpoch, mutationEpoch ->
                val result =
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (
                                generation != request ||
                                    mutableState.value != AccountState.SigningIn
                            )
                                return@withLock AccountActionResult.Cancelled
                            if (previousEpoch != expectedEpoch) {
                                canCancel = false
                                mutableState.value = AccountState.LocalOnly
                                return@withLock AccountActionResult.Cancelled
                            }
                            safely {
                                validateInstallation()
                                val local = localContext.read()
                                check(local.pendingSignOutUid == null)
                                check(readSession() == null)
                                check(sessions.saveSession(credential.profile))
                                publishSession(credential.profile, local = local)
                                AccountActionResult.Completed
                            }
                        }
                    }
                AccountSessionOperationGate.MutationResult(result, reopenAdmission = true)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation == request && mutableState.value == AccountState.SigningIn) {
                        generation++
                        canCancel = false
                        mutableState.value = AccountState.LocalOnly
                    }
                }
            }
            throw cancelled
        }
    }

    override suspend fun cancelDataChoice(): AccountActionResult {
        val request =
            mutex.withLock {
                if (
                    !canCancel ||
                        (mutableState.value.isTransitioning() &&
                            mutableState.value != AccountState.SigningIn)
                )
                    return AccountActionResult.Unavailable
                canCancel = false
                mutableState.value = AccountState.CancellingDataChoice
                ++generation
            }
        return try {
            operationGate.withSessionMutation { _, _ ->
                val result =
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (
                                generation != request ||
                                    mutableState.value != AccountState.CancellingDataChoice
                            )
                                return@withLock AccountActionResult.Cancelled
                            safely {
                                canCancel = true
                                try {
                                    sessions.clearSession()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Verify the durable session below; a failed AtomicFile write
                                    // can still have committed. This also clears an unreadable
                                    // persisted demo fixture without touching training data.
                                }
                                val remaining = readSession()
                                if (remaining != null) {
                                    canCancel = true
                                    return@safely fail(AccountFailureReason.LocalStateUnavailable)
                                }
                                observedRemote = null
                                canCancel = false
                                mutableState.value = AccountState.LocalOnly
                                AccountActionResult.Completed
                            }
                        }
                    }
                AccountSessionOperationGate.MutationResult(
                    result,
                    // Local account setup may have been cancelled before sign-in finished. An
                    // empty session is a stable state, so later local or account actions can enter.
                    reopenAdmission =
                        result != AccountActionResult.Completed ||
                            mutableState.value == AccountState.LocalOnly,
                )
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (
                        generation == request &&
                            mutableState.value == AccountState.CancellingDataChoice
                    ) {
                        generation++
                        try {
                            val profile = readSession()
                            publishSession(
                                profile,
                                inspectRemote = false,
                                local = localContext.read(),
                            )
                        } catch (_: Exception) {
                            fail(AccountFailureReason.LocalStateUnavailable)
                        }
                        if (
                            mutableState.value is AccountState.SignedIn ||
                                mutableState.value is AccountState.AwaitingDataChoice
                        )
                            operationGate.reopenAdmission()
                    }
                }
            }
            throw cancelled
        }
    }

    override suspend fun signOut(request: SignOutRequest): AccountActionResult {
        val plan =
            mutex.withLock {
                when (val current = mutableState.value) {
                    AccountState.SigningIn,
                    AccountState.CancellingDataChoice,
                    AccountState.SigningOut,
                    AccountState.DeletingAccount -> return AccountActionResult.Unavailable
                    is AccountState.SignOutPending -> {
                        if (
                            current.accountId != request.accountId ||
                                current.sessionEpoch != request.sessionEpoch
                        )
                            return AccountActionResult.Cancelled
                        canCancel = false
                        mutableState.value = AccountState.SigningOut
                        SignOutPlan(++generation, current, resumeRemoval = true)
                    }
                    is AccountState.SignedIn -> {
                        if (!matchesRequest(current.accountId, current.sessionEpoch, request))
                            return AccountActionResult.Cancelled
                        if (
                            request.choice == SignOutDataChoice.RemoveData &&
                                !request.removeDataConfirmed
                        )
                            return AccountActionResult.Unavailable
                        canCancel = false
                        mutableState.value = AccountState.SigningOut
                        SignOutPlan(++generation, current, resumeRemoval = false)
                    }
                    is AccountState.AwaitingDataChoice -> {
                        if (!matchesRequest(current.accountId, current.sessionEpoch, request))
                            return AccountActionResult.Cancelled
                        if (
                            request.choice == SignOutDataChoice.RemoveData &&
                                !request.removeDataConfirmed
                        )
                            return AccountActionResult.Unavailable
                        canCancel = false
                        mutableState.value = AccountState.SigningOut
                        SignOutPlan(++generation, current, resumeRemoval = false)
                    }
                    else -> return AccountActionResult.Unavailable
                }
            }
        return try {
            operationGate.withSessionMutation { previousEpoch, mutationEpoch ->
                val result =
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (
                                generation != plan.generation ||
                                    mutableState.value != AccountState.SigningOut
                            )
                                return@withLock AccountSessionOperationGate.MutationResult(
                                    AccountActionResult.Cancelled,
                                    reopenAdmission = true,
                                )
                            if (!plan.resumeRemoval && previousEpoch != request.sessionEpoch) {
                                restorePriorSignOutState(plan.priorState)
                                return@withLock AccountSessionOperationGate.MutationResult(
                                    AccountActionResult.Cancelled,
                                    reopenAdmission = true,
                                )
                            }
                            completeSignOut(request, plan, mutationEpoch)
                        }
                    }
                result
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (
                        generation == plan.generation &&
                            mutableState.value == AccountState.SigningOut
                    )
                        restorePriorSignOutState(plan.priorState)
                }
                if (
                    mutableState.value is AccountState.SignedIn ||
                        mutableState.value is AccountState.AwaitingDataChoice
                )
                    operationGate.reopenAdmission()
            }
            throw cancelled
        }
    }

    private suspend fun completeSignOut(
        request: SignOutRequest,
        plan: SignOutPlan,
        mutationEpoch: Long,
    ): AccountSessionOperationGate.MutationResult<AccountActionResult> {
        val expectedUid = request.accountId.opaqueValue
        val current =
            try {
                readSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (plan.resumeRemoval)
                    return signOutReadUnavailable(
                        request,
                        plan,
                        mutationEpoch,
                        removalCommitted = true
                    )
                canCancel = false
                mutableState.value =
                    AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
                return AccountSessionOperationGate.MutationResult(
                    AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                    reopenAdmission = false,
                )
            }
        if (current == null && plan.resumeRemoval)
            return finishCommittedRemovalWithoutSession(request, plan, mutationEpoch)
        if (current?.id != request.accountId) {
            return reconstructChangedSession(current, expectedUid)
        }

        // Keeping data only clears the demo session. It does not depend on Room or installation
        // validation, so a local read failure cannot turn a non-destructive sign-out into a block.
        val local =
            if (request.choice == SignOutDataChoice.RemoveData || plan.resumeRemoval) {
                try {
                    localContext.read()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return signOutFailed(current, request, plan, mutationEpoch)
                }
            } else null
        val recordedRemoval = local?.pendingSignOutUid == expectedUid
        if (local?.pendingSignOutUid != null && !recordedRemoval)
            return signOutFailed(current, request, plan, mutationEpoch)
        if (plan.resumeRemoval && !recordedRemoval)
            return signOutFailed(current, request, plan, mutationEpoch)

        val removalCommitted = recordedRemoval || request.choice == SignOutDataChoice.RemoveData
        if (removalCommitted && !recordedRemoval) {
            val reset =
                try {
                    localProfileResetter.resetLocalProfile(expectedUid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // An exception can be ambiguous after a Room commit. The durable UID journal
                    // is the authority for deciding whether this retry may clear the session.
                    try {
                        if (localContext.read().pendingSignOutUid == expectedUid)
                            LocalProfileResetResult.Committed(installationMarkerUpdated = false)
                        else LocalProfileResetResult.NotCommitted
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        canCancel = false
                        mutableState.value =
                            AccountState.RecoverableError(
                                AccountFailureReason.LocalStateUnavailable
                            )
                        return AccountSessionOperationGate.MutationResult(
                            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                            reopenAdmission = false,
                        )
                    }
                }
            if (reset == LocalProfileResetResult.NotCommitted) {
                restorePriorSignOutState(plan.priorState)
                return AccountSessionOperationGate.MutationResult(
                    AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                    reopenAdmission = true,
                )
            }
        }

        // The reset and sentinel repair are committed before this final identity check. A changed
        // account is never cleared; the Room journal makes the remaining session-clear retry safe.
        val verifiedSession =
            try {
                readSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return signOutReadUnavailable(
                    request,
                    plan,
                    mutationEpoch,
                    removalCommitted = removalCommitted,
                    knownProfile = current,
                )
            }
        if (verifiedSession?.id != request.accountId)
            return reconstructChangedSession(verifiedSession, expectedUid)

        try {
            sessions.clearSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Verify the persisted result below; AtomicFile failure can be ambiguous to a caller.
        }
        val afterClear =
            try {
                readSession()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return signOutReadUnavailable(
                    request,
                    plan,
                    mutationEpoch,
                    removalCommitted = removalCommitted,
                    knownProfile = current,
                )
            }
        if (afterClear != null) {
            if (afterClear.id != request.accountId)
                return reconstructChangedSession(afterClear, expectedUid)
            return signOutFailed(current, request, plan, mutationEpoch, removalCommitted)
        }

        observedRemote = null
        canCancel = false
        mutableState.value = AccountState.LocalOnly
        if (removalCommitted) runCatching { localProfileResetter.clearPendingSignOut(expectedUid) }
        return AccountSessionOperationGate.MutationResult(
            AccountActionResult.Completed,
            reopenAdmission = false,
        )
    }

    private fun signOutReadUnavailable(
        request: SignOutRequest,
        plan: SignOutPlan,
        mutationEpoch: Long,
        removalCommitted: Boolean,
        knownProfile: AccountProfile? = plan.priorState.signOutProfile(),
    ): AccountSessionOperationGate.MutationResult<AccountActionResult> {
        canCancel = false
        mutableState.value =
            if (removalCommitted || plan.resumeRemoval)
                AccountState.SignOutPending(request.accountId, knownProfile, mutationEpoch)
            else AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
        return AccountSessionOperationGate.MutationResult(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            reopenAdmission = false,
        )
    }

    private suspend fun finishCommittedRemovalWithoutSession(
        request: SignOutRequest,
        plan: SignOutPlan,
        mutationEpoch: Long,
    ): AccountSessionOperationGate.MutationResult<AccountActionResult> {
        val local =
            try {
                localContext.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return signOutReadUnavailable(
                    request,
                    plan,
                    mutationEpoch,
                    removalCommitted = true,
                )
            }
        val expectedUid = request.accountId.opaqueValue
        if (local.pendingSignOutUid != null && local.pendingSignOutUid != expectedUid)
            return reconstructChangedSession(null, expectedUid)
        if (local.pendingSignOutUid == expectedUid) {
            val cleared =
                try {
                    localProfileResetter.clearPendingSignOut(expectedUid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            if (!cleared)
                return signOutReadUnavailable(
                    request,
                    plan,
                    mutationEpoch,
                    removalCommitted = true,
                )
        }
        observedRemote = null
        canCancel = false
        mutableState.value = AccountState.LocalOnly
        return AccountSessionOperationGate.MutationResult(
            AccountActionResult.Completed,
            reopenAdmission = true,
        )
    }

    private suspend fun signOutFailed(
        current: AccountProfile,
        request: SignOutRequest,
        plan: SignOutPlan,
        mutationEpoch: Long,
        removalCommitted: Boolean = false,
    ): AccountSessionOperationGate.MutationResult<AccountActionResult> {
        if (removalCommitted || plan.resumeRemoval) {
            canCancel = false
            mutableState.value =
                AccountState.SignOutPending(request.accountId, current, mutationEpoch)
            return AccountSessionOperationGate.MutationResult(
                AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                reopenAdmission = false,
            )
        }
        if (request.choice == SignOutDataChoice.KeepData && !plan.resumeRemoval) {
            restorePriorSignOutState(plan.priorState)
            return AccountSessionOperationGate.MutationResult(
                AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                reopenAdmission = true,
            )
        }
        val local =
            try {
                localContext.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                canCancel = false
                mutableState.value =
                    AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
                return AccountSessionOperationGate.MutationResult(
                    AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                    reopenAdmission = false,
                )
            }
        publishSession(current, inspectRemote = false, local = local)
        return AccountSessionOperationGate.MutationResult(
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
            reopenAdmission = true,
        )
    }

    /**
     * Reconstructs a newly persisted account without ever clearing it as part of the old request.
     */
    private suspend fun reconstructChangedSession(
        current: AccountProfile?,
        expectedUid: String,
    ): AccountSessionOperationGate.MutationResult<AccountActionResult> {
        val local =
            try {
                localContext.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                canCancel = false
                mutableState.value =
                    AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
                return AccountSessionOperationGate.MutationResult(
                    AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                    reopenAdmission = false,
                )
            }
        if (local.pendingSignOutUid == expectedUid) {
            val cleared =
                try {
                    localProfileResetter.clearPendingSignOut(expectedUid)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            if (!cleared) {
                canCancel = false
                mutableState.value =
                    AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
                return AccountSessionOperationGate.MutationResult(
                    AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                    reopenAdmission = false,
                )
            }
        } else if (local.pendingSignOutUid != null) {
            operationGate.closeAdmission()
            canCancel = false
            mutableState.value =
                AccountState.SignOutPending(
                    AccountId(local.pendingSignOutUid),
                    current?.takeIf { it.id.opaqueValue == local.pendingSignOutUid },
                    operationGate.sessionEpoch,
                )
            return AccountSessionOperationGate.MutationResult(
                AccountActionResult.Cancelled,
                reopenAdmission = false,
            )
        }

        observedRemote = null
        canCancel = false
        if (current == null) {
            mutableState.value = AccountState.LocalOnly
        } else {
            val refreshedLocal =
                try {
                    localContext.read()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value =
                        AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
                    return AccountSessionOperationGate.MutationResult(
                        AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                        reopenAdmission = false,
                    )
                }
            publishSession(current, inspectRemote = false, local = refreshedLocal)
        }
        return AccountSessionOperationGate.MutationResult(
            AccountActionResult.Cancelled,
            reopenAdmission = true,
        )
    }

    private fun restorePriorSignOutState(state: AccountState) {
        val restored =
            when (state) {
                is AccountState.SignedIn -> state.copy(sessionEpoch = operationGate.sessionEpoch)
                is AccountState.AwaitingDataChoice ->
                    state.copy(sessionEpoch = operationGate.sessionEpoch)
                is AccountState.SignOutPending ->
                    state.copy(sessionEpoch = operationGate.sessionEpoch)
                else -> state
            }
        canCancel = restored is AccountState.AwaitingDataChoice
        mutableState.value = restored
    }

    private fun reopenAdmissionIfStable() {
        if (
            mutableState.value == AccountState.LocalOnly ||
                mutableState.value is AccountState.SignedIn ||
                mutableState.value is AccountState.AwaitingDataChoice
        )
            operationGate.reopenAdmission()
    }

    private fun AccountState.signOutProfile(): AccountProfile? =
        when (this) {
            is AccountState.SignedIn -> profile
            is AccountState.AwaitingDataChoice -> profile
            is AccountState.SignOutPending -> profile
            else -> null
        }

    private fun matchesRequest(
        accountId: AccountId,
        sessionEpoch: Long,
        request: SignOutRequest,
    ): Boolean =
        accountId == request.accountId &&
            sessionEpoch == request.sessionEpoch &&
            request.sessionEpoch == operationGate.sessionEpoch

    private data class SignOutPlan(
        val generation: Long,
        val priorState: AccountState,
        val resumeRemoval: Boolean,
    )

    private fun AccountState.isTransitioning(): Boolean =
        this == AccountState.SigningIn ||
            this == AccountState.CancellingDataChoice ||
            this == AccountState.SigningOut ||
            this == AccountState.DeletingAccount

    private suspend fun validateInstallation() {
        check(installationGuard.validate() != InstallationValidationResult.Failed)
    }

    private suspend fun publishSession(
        profile: AccountProfile?,
        inspectRemote: Boolean = true,
        local: LocalAccountContext,
    ) {
        // Until local ownership has been verified, a persisted identity is a pending setup.
        canCancel = profile != null
        val pending = local.pendingSignOutUid
        if (pending != null) {
            if (profile?.id?.opaqueValue == pending) {
                operationGate.closeAdmission()
                canCancel = false
                mutableState.value =
                    AccountState.SignOutPending(
                        AccountId(pending),
                        profile,
                        operationGate.sessionEpoch,
                    )
                return
            }
            if (profile == null && localProfileResetter.clearPendingSignOut(pending)) {
                observedRemote = null
                canCancel = false
                mutableState.value = AccountState.LocalOnly
                return
            }
            fail(AccountFailureReason.LocalStateUnavailable)
        }
        val persistedPresence =
            local.conflict
                .takeIf { local.ownerUid == profile?.id?.opaqueValue }
                ?.let { lineage ->
                    val id = lineage.lastObservedRemoteBackupId
                    val source = lineage.lastObservedSourceInstallationId
                    if (id != null && source != null)
                        RemoteSnapshotPresence.Complete(
                            id,
                            lineage.lastObservedRemoteGeneration,
                            source
                        )
                    else null
                }
        val cachedPresence = observedRemote?.takeIf { it.first == profile?.id }?.second
        val remotePresence =
            when {
                profile == null -> RemoteSnapshotPresence.Absent.also { observedRemote = null }
                inspectRemote ->
                    sessions.remoteSnapshot(profile.id).also { observedRemote = profile.id to it }
                persistedPresence != null &&
                    persistedPresence.generation >
                        ((cachedPresence as? RemoteSnapshotPresence.Complete)?.generation ?: -1) ->
                    persistedPresence
                cachedPresence != null -> cachedPresence
                else -> persistedPresence ?: RemoteSnapshotPresence.Absent
            }
        val resolved =
            AccountStateResolver.resolve(
                authenticatedUid = profile?.id?.opaqueValue,
                localOwnerUid = local.ownerUid,
                localDataIsEmpty = local.localDataIsEmpty,
                remoteSnapshot = remotePresence,
                conflict = local.conflict,
            )
        canCancel = resolved is AccountState.AwaitingDataChoice
        val sessionEpoch = operationGate.sessionEpoch
        mutableState.value =
            when (resolved) {
                is AccountState.SignedIn ->
                    resolved.copy(profile = profile, sessionEpoch = sessionEpoch)
                is AccountState.AwaitingDataChoice ->
                    resolved.copy(profile = profile, sessionEpoch = sessionEpoch)
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

    override suspend fun deleteAccount(): AccountActionResult = AccountActionResult.Unavailable
}
