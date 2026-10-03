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
    private val deletionManager: AccountDeletionManager = UnavailableAccountDeletionManager,
    private val capabilities: AccountExperienceCapabilities = AccountExperienceCapabilities.Demo,
) : AccountGateway {
    private val mutableState = MutableStateFlow<AccountState>(AccountState.Loading)
    override val state: StateFlow<AccountState> = mutableState
    private val mutex = Mutex()
    private var generation = 0L
    @Volatile private var canCancel = false
    private var observedRemote: Pair<AccountId, RemoteSnapshotPresence>? = null
    private var signOutAccountId: AccountId? = null
    @Volatile private var deletionRecoveryInFlight = false

    override val sessionChanges = sessions.sessionChanges

    override suspend fun refresh(): AccountActionResult =
        refreshContext(inspectRemote = capabilities.inspectRemoteSessionState)

    override suspend fun refreshLocal(): AccountActionResult = refreshContext(inspectRemote = false)

    override suspend fun reconcileAfterDeletionRecovery(): AccountActionResult =
        withContext(NonCancellable) {
            operationGate.withSessionObservation {
                mutex.withLock {
                    settleDeletionTerminal(
                        actionResult = AccountActionResult.Completed,
                        previousProgress =
                            (mutableState.value as? AccountState.AccountDeletionPending)?.progress,
                    )
                }
            }
        }

    override suspend fun reconcileSessionChange(): AccountActionResult =
        withContext(NonCancellable) {
            operationGate.withSessionObservation { _ ->
                val result =
                    mutex.withLock {
                        // A failed journal read is not proof that deletion ended. Preserve the
                        // last known state, including its recovery UI, and leave admission closed.
                        val pendingDeletion =
                            try {
                                deletionManager.pending()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                return@withLock AccountActionResult.Failed(
                                    AccountFailureReason.LocalStateUnavailable
                                )
                            }
                        safely {
                            // Auth callbacks are observations, not authority to retire a durable
                            // deletion. The deletion manager alone verifies completion.
                            if (pendingDeletion != null) {
                                return@safely publishPendingDeletion(pendingDeletion)
                            }
                            if (mutableState.value == AccountState.DeletingAccount) {
                                return@safely AccountActionResult.Completed
                            }
                            val profile = readSession()
                            val changed = mutableState.value.sessionAccountId() != profile?.id
                            if (changed) {
                                val local = localContext.read()
                                generation++
                                operationGate.advanceSessionEpoch()
                                publishIdentityOnlySession(
                                    profile,
                                    local,
                                    operationGate.sessionEpoch
                                )
                            }
                            AccountActionResult.Completed
                        }
                    }
                AccountSessionOperationGate.MutationResult(
                    result,
                    reopenAdmission =
                        result == AccountActionResult.Completed &&
                            mutableState.value.isStableAccountState(),
                )
            }
        }

    private suspend fun refreshContext(inspectRemote: Boolean): AccountActionResult =
        mutex.withLock {
            if (mutableState.value.isTransitioning())
                return@withLock AccountActionResult.Unavailable
            val pendingDeletion =
                try {
                    deletionManager.pending()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    operationGate.closeAdmission()
                    return@withLock AccountActionResult.Failed(
                        AccountFailureReason.LocalStateUnavailable
                    )
                }
            if (pendingDeletion != null) {
                return@withLock publishPendingDeletion(pendingDeletion)
            }
            safely { refreshSessionContext(inspectRemote) }
        }

    private fun publishPendingDeletion(progress: AccountDeletionProgress): AccountActionResult {
        operationGate.closeAdmission()
        canCancel = false
        mutableState.value = AccountState.AccountDeletionPending(progress)
        return AccountActionResult.Completed
    }

    private suspend fun refreshSessionContext(inspectRemote: Boolean): AccountActionResult {
        val profile = readSession()
        val local =
            try {
                localContext.read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                canCancel = false
                return fail(AccountFailureReason.LocalStateUnavailable)
            }
        if (!capabilities.canAssociateLocalData) {
            publishIdentityOnlySession(profile, local, operationGate.sessionEpoch)
            return AccountActionResult.Completed
        }

        val pending = local.pendingSignOutUid
        return when {
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
                // The old account is no longer the persisted session. Its removal is already
                // committed, so retire only the journal and reconstruct the new account without
                // clearing or claiming its data.
                check(localProfileResetter.clearPendingSignOut(pending))
                publishRefreshedSession(profile, inspectRemote) { localContext.read() }
            }
            else -> publishRefreshedSession(profile, inspectRemote) { local }
        }
    }

    private suspend fun publishRefreshedSession(
        profile: AccountProfile?,
        inspectRemote: Boolean,
        readLocalContext: suspend () -> LocalAccountContext,
    ): AccountActionResult {
        validateInstallation()
        publishSession(profile, inspectRemote, readLocalContext())
        reopenAdmissionIfStable()
        return AccountActionResult.Completed
    }

    override suspend fun startGoogleSignIn(): AccountActionResult {
        if (!capabilities.canSignIn) return AccountActionResult.Unavailable
        val request =
            mutex.withLock {
                if (
                    mutableState.value.isTransitioning() ||
                        mutableState.value is AccountState.SignOutPending ||
                        mutableState.value is AccountState.AccountDeletionPending ||
                        deletionManager.pending() != null
                )
                    return AccountActionResult.Unavailable
                val preparation = safely {
                    if (capabilities.canAssociateLocalData) validateInstallation()
                    val existing = readSession()
                    val local = localContext.read()
                    if (local.pendingSignOutUid != null)
                        return@safely fail(AccountFailureReason.LocalStateUnavailable)
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
        var returnedCandidate: PendingGoogleCredential? = null
        val credential =
            try {
                sessions.requestGoogleCredential(request).also { selected ->
                    if (selected is CredentialResult.Selected) {
                        returnedCandidate = selected.candidate
                    }
                    currentCoroutineContext().ensureActive()
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    val candidate = returnedCandidate
                    if (candidate != null) sessions.discardGoogleCredential(candidate)
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

        val accepted =
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation != request || mutableState.value != AccountState.SigningIn) {
                        false
                    } else {
                        canCancel = false
                        mutableState.value = AccountState.SavingSignIn
                        true
                    }
                }
            }
        if (!accepted) {
            withContext(NonCancellable) { sessions.discardGoogleCredential(credential.candidate) }
            return AccountActionResult.Cancelled
        }

        return try {
            try {
                withContext(NonCancellable) {
                    operationGate.withSessionMutation { previousEpoch, _ ->
                        val result =
                            mutex.withLock {
                                if (
                                    generation != request ||
                                        mutableState.value != AccountState.SavingSignIn
                                )
                                    return@withLock AccountActionResult.Cancelled
                                if (previousEpoch != expectedEpoch) {
                                    canCancel = false
                                    mutableState.value = AccountState.LocalOnly
                                    return@withLock AccountActionResult.Cancelled
                                }
                                safely {
                                    if (capabilities.canAssociateLocalData) validateInstallation()
                                    val local = localContext.read()
                                    check(local.pendingSignOutUid == null)
                                    check(readSession() == null)
                                    when (
                                        val committed =
                                            sessions.commitGoogleCredential(credential.candidate)
                                    ) {
                                        is CredentialCommitResult.Authenticated -> {
                                            publishSession(committed.profile, local = local)
                                            AccountActionResult.Completed
                                        }
                                        is CredentialCommitResult.Failed -> fail(committed.reason)
                                    }
                                }
                            }
                        AccountSessionOperationGate.MutationResult(result, reopenAdmission = true)
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    sessions.discardGoogleCredential(credential.candidate)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (generation == request && mutableState.value == AccountState.SavingSignIn) {
                        try {
                            publishSession(
                                readSession(),
                                inspectRemote = false,
                                local = localContext.read(),
                            )
                        } catch (_: Exception) {
                            fail(AccountFailureReason.LocalStateUnavailable)
                        }
                    }
                }
                reopenAdmissionIfStable()
            }
            throw cancelled
        }
    }

    override suspend fun cancelDataChoice(): AccountActionResult {
        val (request, chooserRequestId) =
            mutex.withLock {
                if (
                    !canCancel ||
                        (mutableState.value.isTransitioning() &&
                            mutableState.value != AccountState.SigningIn)
                )
                    return AccountActionResult.Unavailable
                val chooserRequestId =
                    if (mutableState.value == AccountState.SigningIn) generation else null
                canCancel = false
                mutableState.value = AccountState.CancellingDataChoice
                (++generation) to chooserRequestId
            }
        chooserRequestId?.let(sessions::cancelGoogleCredentialRequest)
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
                                val persisted = readSession()
                                if (persisted != null) {
                                    publishSession(
                                        persisted,
                                        inspectRemote = false,
                                        local = localContext.read(),
                                    )
                                    return@safely AccountActionResult.Unavailable
                                }
                                if (chooserRequestId != null) {
                                    observedRemote = null
                                    mutableState.value = AccountState.LocalOnly
                                    return@safely AccountActionResult.Completed
                                }
                                try {
                                    sessions.clearSession()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Verify durable state below; a failed write may have
                                    // committed.
                                }
                                val remaining = readSession()
                                if (remaining != null) {
                                    publishSession(
                                        remaining,
                                        inspectRemote = false,
                                        local = localContext.read(),
                                    )
                                    return@safely AccountActionResult.Unavailable
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

    override suspend fun recoverUnreadableSession(): AccountActionResult =
        operationGate.withSessionMutation { _, _ ->
            val result =
                withContext(NonCancellable) {
                    mutex.withLock {
                        val current = mutableState.value as? AccountState.RecoverableError
                        if (current?.canRecoverUnreadableSession != true)
                            return@withLock AccountActionResult.Unavailable
                        try {
                            if (!sessions.clearUnreadableSession())
                                return@withLock AccountActionResult.Unavailable
                            check(sessions.readSession() == null)
                            observedRemote = null
                            canCancel = false
                            mutableState.value = AccountState.LocalOnly
                            AccountActionResult.Completed
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            canCancel = false
                            mutableState.value =
                                AccountState.RecoverableError(
                                    AccountFailureReason.LocalStateUnavailable,
                                    canRecoverUnreadableSession = true,
                                )
                            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable)
                        }
                    }
                }
            AccountSessionOperationGate.MutationResult(result, reopenAdmission = true)
        }

    override suspend fun signOut(request: SignOutRequest): AccountActionResult {
        val plan =
            mutex.withLock {
                when (val current = mutableState.value) {
                    AccountState.SigningIn,
                    AccountState.SavingSignIn,
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
                        signOutAccountId = current.accountId
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
                        signOutAccountId = current.accountId
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
                        signOutAccountId = current.accountId
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

    override suspend fun deleteAccount(request: AccountDeletionRequest): AccountActionResult {
        if (!capabilities.canDeleteAccount) return AccountActionResult.Unavailable
        val plan =
            mutex.withLock {
                val candidate =
                    when (val current = mutableState.value) {
                        is AccountState.SignedIn -> {
                            if (!current.canDeleteAccount) return AccountActionResult.Unavailable
                            DeletionCandidate(
                                current,
                                current.accountId,
                                current.sessionEpoch,
                                current.profileGeneration,
                                current.accountId.opaqueValue,
                            )
                        }
                        is AccountState.AwaitingDataChoice -> {
                            if (
                                !current.canDeleteUnclaimedData ||
                                    current.context.ownership !is LocalOwnership.Unclaimed
                            )
                                return AccountActionResult.Unavailable
                            DeletionCandidate(
                                current,
                                current.accountId,
                                current.sessionEpoch,
                                current.profileGeneration,
                                null,
                            )
                        }
                        else -> return AccountActionResult.Unavailable
                    }
                if (
                    candidate.accountId != request.accountId ||
                        candidate.sessionEpoch != request.sessionEpoch ||
                        candidate.profileGeneration != request.profileGeneration ||
                        candidate.expectedLocalOwnerUid != request.expectedLocalOwnerUid
                )
                    return AccountActionResult.Cancelled
                canCancel = false
                mutableState.value = AccountState.DeletingAccount
                DeletionPlan(++generation, candidate.priorState)
            }
        return try {
            operationGate.withSessionMutation { previousEpoch, mutationEpoch ->
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (
                            generation != plan.generation ||
                                mutableState.value != AccountState.DeletingAccount
                        )
                            return@withLock AccountSessionOperationGate.MutationResult(
                                AccountActionResult.Cancelled,
                                reopenAdmission = true,
                            )
                        if (previousEpoch != request.sessionEpoch) {
                            restorePriorSignOutState(plan.priorState)
                            return@withLock AccountSessionOperationGate.MutationResult(
                                AccountActionResult.Cancelled,
                                reopenAdmission = true,
                            )
                        }
                        val persisted = sessions.readSession()
                        val local = localContext.read()
                        if (
                            persisted?.id != request.accountId ||
                                local.ownerUid != request.expectedLocalOwnerUid ||
                                local.profileGeneration != request.profileGeneration ||
                                local.pendingSignOutUid != null
                        ) {
                            restorePriorSignOutState(plan.priorState)
                            return@withLock AccountSessionOperationGate.MutationResult(
                                AccountActionResult.Cancelled,
                                reopenAdmission = true,
                            )
                        }
                        when (val deletion = deletionManager.delete(request)) {
                            AccountDeletionResult.Completed ->
                                settleDeletionTerminal(
                                    AccountActionResult.Completed,
                                    deletedAccount = request.accountId,
                                )
                            is AccountDeletionResult.RetryRequired -> {
                                canCancel = false
                                mutableState.value =
                                    AccountState.AccountDeletionPending(deletion.progress)
                                AccountSessionOperationGate.MutationResult(
                                    AccountActionResult.Failed(
                                        AccountFailureReason.ServiceUnavailable
                                    ),
                                    reopenAdmission = false,
                                )
                            }
                            AccountDeletionResult.Cancelled ->
                                settleDeletionTerminal(AccountActionResult.Cancelled)
                            is AccountDeletionResult.Failed -> {
                                restorePriorSignOutState(plan.priorState)
                                AccountSessionOperationGate.MutationResult(
                                    AccountActionResult.Failed(deletion.reason),
                                    reopenAdmission = true
                                )
                            }
                            AccountDeletionResult.Idle,
                            AccountDeletionResult.Unavailable -> {
                                restorePriorSignOutState(plan.priorState)
                                AccountSessionOperationGate.MutationResult(
                                    AccountActionResult.Unavailable,
                                    reopenAdmission = true,
                                )
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                var pendingReadSucceeded = false
                var pending: AccountDeletionProgress? = null
                try {
                    pending = deletionManager.pending()
                    pendingReadSucceeded = true
                } catch (_: Exception) {
                    // Keep writes fenced if the durable journal cannot be inspected.
                }
                mutex.withLock {
                    if (
                        generation == plan.generation &&
                            mutableState.value == AccountState.DeletingAccount
                    ) {
                        when {
                            !pendingReadSucceeded -> {
                                operationGate.closeAdmission()
                                mutableState.value =
                                    AccountState.RecoverableError(
                                        AccountFailureReason.LocalStateUnavailable
                                    )
                            }
                            pending != null -> {
                                operationGate.closeAdmission()
                                mutableState.value =
                                    AccountState.AccountDeletionPending(checkNotNull(pending))
                            }
                            else -> restorePriorSignOutState(plan.priorState)
                        }
                    }
                }
            }
            throw cancelled
        } catch (_: Exception) {
            val pending = deletionManager.pending()
            mutex.withLock {
                if (pending != null) {
                    operationGate.closeAdmission()
                    mutableState.value = AccountState.AccountDeletionPending(pending)
                } else {
                    mutableState.value =
                        AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
                    operationGate.reopenAdmission()
                }
            }
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable)
        }
    }

    override suspend fun retryAccountDeletion(): AccountActionResult =
        continuePendingDeletion(cancelUnactivated = false)

    override suspend fun cancelAccountDeletion(): AccountActionResult =
        continuePendingDeletion(cancelUnactivated = true)

    private suspend fun continuePendingDeletion(cancelUnactivated: Boolean): AccountActionResult {
        if (deletionRecoveryInFlight) return AccountActionResult.Unavailable
        val previousProgress =
            mutex.withLock {
                if (deletionRecoveryInFlight) return AccountActionResult.Unavailable
                val pending =
                    mutableState.value as? AccountState.AccountDeletionPending
                        ?: return AccountActionResult.Unavailable
                if (
                    cancelUnactivated &&
                        (capabilities.mode != AccountExperienceCapabilities.Mode.AuthPreview ||
                            !pending.progress.isCancellableReservation())
                ) {
                    return AccountActionResult.Unavailable
                }
                deletionRecoveryInFlight = true
                operationGate.closeAdmission()
                canCancel = false
                mutableState.value = AccountState.DeletingAccount
                pending.progress
            }
        // Keep the serialized transition and its reconciliation together even if the screen leaves.
        return withContext(NonCancellable) {
            try {
                operationGate.withSessionMutation { _, _ ->
                    mutex.withLock {
                        when (
                            val result =
                                if (cancelUnactivated) deletionManager.cancelUnactivated()
                                else deletionManager.retry()
                        ) {
                            AccountDeletionResult.Completed ->
                                settleDeletionTerminal(
                                    AccountActionResult.Completed,
                                    previousProgress,
                                    previousProgress.accountId
                                )
                            AccountDeletionResult.Cancelled ->
                                settleDeletionTerminal(
                                    AccountActionResult.Cancelled,
                                    previousProgress
                                )
                            is AccountDeletionResult.RetryRequired -> {
                                publishPendingDeletion(result.progress)
                                AccountSessionOperationGate.MutationResult(
                                    AccountActionResult.Failed(
                                        AccountFailureReason.ServiceUnavailable
                                    ),
                                    false,
                                )
                            }
                            AccountDeletionResult.Idle,
                            AccountDeletionResult.Unavailable,
                            is AccountDeletionResult.Failed -> {
                                retainDeletionBarrier(previousProgress)
                                AccountSessionOperationGate.MutationResult(
                                    if (result is AccountDeletionResult.Failed)
                                        AccountActionResult.Failed(result.reason)
                                    else AccountActionResult.Unavailable,
                                    reopenAdmission = false,
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                mutex.withLock { retainDeletionBarrier(previousProgress) }
                throw cancelled
            } catch (_: Exception) {
                mutex.withLock { retainDeletionBarrier(previousProgress) }
                AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable)
            } finally {
                mutex.withLock { deletionRecoveryInFlight = false }
            }
        }
    }

    private fun AccountDeletionProgress.isCancellableReservation(): Boolean =
        stage == AccountDeletionStage.PREPARED &&
            remoteState == AccountDeletionRemoteState.RESERVED &&
            !serviceBinding.isNullOrBlank() &&
            !receiptSecret.isNullOrBlank() &&
            !subjectBinding.isNullOrBlank() &&
            !installationId.isNullOrBlank() &&
            receiptVersion > 0

    /**
     * A terminal service outcome still requires stable local and provider state before admission.
     */
    private suspend fun settleDeletionTerminal(
        actionResult: AccountActionResult,
        previousProgress: AccountDeletionProgress? = null,
        deletedAccount: AccountId? = null,
    ): AccountSessionOperationGate.MutationResult<AccountActionResult> {
        return try {
            deletionManager.pending()?.let { pending ->
                publishPendingDeletion(pending)
                return AccountSessionOperationGate.MutationResult(
                    AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                    false,
                )
            }
            val beforeValidation = readSession()
            validateInstallation()
            val local = localContext.read()
            check(local.pendingSignOutUid == null)
            val current = readSession()
            check(beforeValidation?.id == current?.id)
            check(deletedAccount == null || current?.id != deletedAccount)
            generation++
            observedRemote = null
            canCancel = false
            operationGate.advanceSessionEpoch()
            // Do not clear a foreign session or reset any training/ownership state here.
            publishSession(current, inspectRemote = false, local = local)
            val stable = mutableState.value.isStableAccountState()
            AccountSessionOperationGate.MutationResult(
                if (stable) actionResult
                else AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                reopenAdmission = stable,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            operationGate.closeAdmission()
            mutableState.value =
                previousProgress?.let(AccountState::AccountDeletionPending)
                    ?: AccountState.RecoverableError(AccountFailureReason.LocalStateUnavailable)
            AccountSessionOperationGate.MutationResult(
                AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable),
                false,
            )
        }
    }

    private suspend fun retainDeletionBarrier(previousProgress: AccountDeletionProgress) {
        val current =
            try {
                deletionManager.pending()
            } catch (_: Exception) {
                null
            }
        publishPendingDeletion(current ?: previousProgress)
    }

    private fun AccountState.isStableAccountState(): Boolean =
        this == AccountState.LocalOnly ||
            this is AccountState.SignedIn ||
            this is AccountState.AwaitingDataChoice

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

    private data class DeletionPlan(
        val generation: Long,
        val priorState: AccountState,
    )

    private data class DeletionCandidate(
        val priorState: AccountState,
        val accountId: AccountId,
        val sessionEpoch: Long,
        val profileGeneration: Long,
        val expectedLocalOwnerUid: String?,
    )

    private fun AccountState.isTransitioning(): Boolean =
        this == AccountState.SigningIn ||
            this == AccountState.SavingSignIn ||
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
        if (!capabilities.canAssociateLocalData) {
            publishIdentityOnlySession(profile, local, operationGate.sessionEpoch)
            return
        }
        // Until local ownership has been verified, a persisted identity is a pending setup.
        canCancel = false
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
                            source,
                            lineage.lastObservedRemoteDigest,
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
                activeWorkoutPresent = local.activeWorkoutPresent,
            )
        canCancel = false
        val sessionEpoch = operationGate.sessionEpoch
        mutableState.value =
            when (resolved) {
                is AccountState.SignedIn ->
                    resolved.copy(
                        profile = profile,
                        sessionEpoch = sessionEpoch,
                        profileGeneration = local.profileGeneration,
                        canDeleteAccount =
                            capabilities.canDeleteAccount &&
                                profile?.id?.opaqueValue == local.ownerUid &&
                                local.pendingSignOutUid == null,
                    )
                is AccountState.AwaitingDataChoice ->
                    resolved.copy(
                        profile = profile,
                        sessionEpoch = sessionEpoch,
                        profileGeneration = local.profileGeneration,
                        canDeleteUnclaimedData =
                            capabilities.canDeleteAccount &&
                                resolved.context.ownership is LocalOwnership.Unclaimed &&
                                local.pendingSignOutUid == null,
                    )
                else -> resolved
            }
    }

    private fun publishIdentityOnlySession(
        profile: AccountProfile?,
        local: LocalAccountContext,
        sessionEpoch: Long,
    ) {
        canCancel = false
        observedRemote = null
        val pendingUid = local.pendingSignOutUid
        mutableState.value =
            when {
                pendingUid != null ->
                    AccountState.SignOutPending(
                        accountId = AccountId(pendingUid),
                        profile = profile?.takeIf { it.id.opaqueValue == pendingUid },
                        sessionEpoch = sessionEpoch,
                    )
                profile == null -> AccountState.LocalOnly
                else ->
                    AccountState.SignedIn(
                        accountId = profile.id,
                        profile = profile,
                        sessionEpoch = sessionEpoch,
                        profileGeneration = local.profileGeneration,
                        canDeleteAccount = false,
                    )
            }
    }

    private fun AccountState.sessionAccountId(): AccountId? =
        when (this) {
            is AccountState.SignedIn -> accountId
            is AccountState.AwaitingDataChoice -> accountId
            is AccountState.SignOutPending -> accountId
            AccountState.SigningOut -> signOutAccountId
            else -> null
        }

    private suspend fun readSession(): AccountProfile? =
        try {
            sessions.readSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            canCancel = false
            throw failure
        }

    private suspend fun safely(block: suspend () -> AccountActionResult): AccountActionResult =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnreadableAccountSessionException) {
            canCancel = false
            mutableState.value =
                AccountState.RecoverableError(
                    AccountFailureReason.LocalStateUnavailable,
                    canRecoverUnreadableSession = true,
                )
            AccountActionResult.Failed(AccountFailureReason.LocalStateUnavailable)
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
