package com.example.ironpath.ui.screens.accountbackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountContextReader
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRequest
import com.example.ironpath.domain.account.AccountExperienceCapabilities
import com.example.ironpath.domain.account.AccountFailureReason
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.account.LocalOwnership
import com.example.ironpath.domain.account.RemoteSnapshotPresence
import com.example.ironpath.domain.account.SignOutDataChoice
import com.example.ironpath.domain.account.SignOutRequest
import com.example.ironpath.domain.backup.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AccountBackupViewModel
@Inject
constructor(
    private val accountGateway: AccountGateway,
    localContext: AccountContextReader,
    private val backup: BackupCoordinator,
    private val capabilities: AccountExperienceCapabilities = AccountExperienceCapabilities.Demo,
) : ViewModel() {
    val state = accountGateway.state
    private val mutableManual = MutableStateFlow(ManualBackupUiState())
    val manual: StateFlow<ManualBackupUiState> = mutableManual
    private var deletionActionInFlight = false

    init {
        viewModelScope.launch {
            backup.status.collect { status -> mutableManual.update { it.copy(status = status) } }
        }
        viewModelScope.launch {
            backup.latestSummary.collect { latest ->
                mutableManual.update { it.copy(latest = latest) }
            }
        }
        viewModelScope.launch {
            backup.undoAvailable.collect { available ->
                mutableManual.update { it.copy(undoAvailable = available) }
            }
        }
        viewModelScope.launch {
            accountGateway.refreshLocal()
            refreshBackupStatus()
            localContext.changes
                // A failed invalidation source gets one explicit refresh before collection stops.
                .catch { emit(Unit) }
                .collect {
                    accountGateway.refreshLocal()
                    refreshBackupStatus()
                }
        }
        viewModelScope.launch {
            state.collect { accountState ->
                when (accountState) {
                    AccountState.DeletingAccount ->
                        mutableManual.update {
                            it.copy(
                                busy = true,
                                accountDeletion = it.accountDeletion.copy(busy = true),
                            )
                        }
                    is AccountState.AccountDeletionPending -> {
                        if (deletionActionInFlight) {
                            mutableManual.update {
                                it.copy(
                                    accountDeletion =
                                        it.accountDeletion.copy(progress = accountState.progress)
                                )
                            }
                        } else showDeletionRetry(accountState.progress)
                    }
                    else ->
                        mutableManual.update {
                            if (
                                !deletionActionInFlight &&
                                    (it.accountDeletion.progress != null ||
                                        it.accountDeletion.retryAvailable)
                            )
                                it.copy(
                                    busy = false,
                                    feedback = null,
                                    accountDeletion = AccountDeletionUiState(),
                                )
                            else it
                        }
                }
            }
        }
    }

    fun signIn() {
        if (!capabilities.canSignIn || manual.value.busy || manual.value.signOutBusy) return
        viewModelScope.launch {
            val result = accountGateway.startGoogleSignIn()
            refreshBackupStatus()
            if (
                capabilities.mode == AccountExperienceCapabilities.Mode.Demo &&
                    capabilities.canUseBackup &&
                    result == AccountActionResult.Completed
            )
                refreshLatestBackupIfEligible()
        }
    }

    fun recoverUnreadableSession() {
        if (capabilities.mode == AccountExperienceCapabilities.Mode.AuthPreview) return
        val recoverable = state.value as? AccountState.RecoverableError ?: return
        if (
            !recoverable.canRecoverUnreadableSession ||
                manual.value.busy ||
                manual.value.signOutBusy
        )
            return
        viewModelScope.launch {
            mutableManual.update { it.copy(signOutBusy = true, feedback = null) }
            val result = accountGateway.recoverUnreadableSession()
            refreshBackupStatus()
            mutableManual.update {
                it.copy(
                    signOutBusy = false,
                    feedback =
                        when (result) {
                            AccountActionResult.Completed ->
                                "The unreadable demo session was cleared. Training data remains on this device."
                            AccountActionResult.Cancelled,
                            AccountActionResult.Unavailable,
                            is AccountActionResult.Failed ->
                                "The unreadable demo session could not be cleared. Try again."
                        },
                )
            }
        }
    }

    fun refresh() {
        if (manual.value.busy || manual.value.signOutBusy) return
        val cloudStatusRequest = capabilities.mode == AccountExperienceCapabilities.Mode.AuthPreview
        mutableManual.update { it.copy(busy = cloudStatusRequest, feedback = null) }
        viewModelScope.launch {
            try {
                accountGateway.refresh()
                if (capabilities.canUseBackup && state.value.isEligibleForLatestBackupLookup()) {
                    refreshLatestBackupIfEligible()
                } else {
                    refreshBackupStatus()
                }
            } finally {
                if (cloudStatusRequest) mutableManual.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun refreshLatestBackupIfEligible() {
        if (!capabilities.canUseBackup || !state.value.isEligibleForLatestBackupLookup()) return
        when (val result = backup.latestCompleteBackup()) {
            is BackupLookupResult.Failed -> showFailure(result.reason)
            else -> Unit
        }
    }

    private fun AccountState.isEligibleForLatestBackupLookup() =
        this is AccountState.SignedIn || this is AccountState.AwaitingDataChoice

    fun keepDeviceEmpty() {
        if (!capabilities.canUseBackup || !capabilities.canAssociateLocalData) return
        val pending = state.value as? AccountState.AwaitingDataChoice ?: return
        val reviewedProfile = pending.context.conflict ?: return
        val expectedRemoteSnapshot =
            pending.context.remoteSnapshot as? RemoteSnapshotPresence.Complete ?: return
        if (
            pending.context.ownership !is LocalOwnership.Unclaimed ||
                !pending.context.localDataIsEmpty ||
                pending.context.activeWorkoutPresent ||
                manual.value.busy ||
                manual.value.signOutBusy
        )
            return
        runManual {
            when (
                val result =
                    backup.associateEmptyProfile(
                        pending.accountId,
                        pending.sessionEpoch,
                        reviewedProfile.currentInstallationId,
                        reviewedProfile.localChangeRevision,
                        expectedRemoteSnapshot,
                    )
            ) {
                BackupActionResult.Completed -> {
                    accountGateway.refreshLocal()
                    refreshBackupStatus()
                    mutableManual.update {
                        it.copy(
                            feedback =
                                "This device will stay empty. The complete demo backup remains available to restore."
                        )
                    }
                }
                is BackupActionResult.Failed -> showFailure(result.reason)
                BackupActionResult.Cancelled ->
                    mutableManual.update {
                        it.copy(
                            feedback = "The account changed before this device could be associated."
                        )
                    }
                BackupActionResult.Unavailable -> unavailable()
                is BackupActionResult.ActiveSessionRequiresConfirmation ->
                    showFailure(BackupFailureReason.ActiveSessionPresent)
            }
        }
    }

    fun openSignOutReview() {
        if (manual.value.busy || manual.value.signOutBusy) return
        val current = state.value
        val target =
            when (current) {
                is AccountState.SignedIn -> SignOutTarget(current.accountId, current.sessionEpoch)
                is AccountState.AwaitingDataChoice ->
                    SignOutTarget(current.accountId, current.sessionEpoch)
                else -> return
            }
        mutableManual.update {
            it.copy(
                signOutReview = SignOutReviewUiState(target),
                feedback = null,
            )
        }
    }

    fun dismissSignOutReview() {
        if (!manual.value.signOutBusy) {
            mutableManual.update { it.copy(signOutReview = null) }
        }
    }

    fun chooseSignOutChoice(choice: SignOutDataChoice) {
        if (manual.value.signOutBusy) return
        mutableManual.update { state ->
            state.copy(
                signOutReview =
                    state.signOutReview?.copy(
                        choice = choice,
                        confirmingRemoval = false,
                    )
            )
        }
    }

    fun requestRemoveConfirmation() {
        if (manual.value.signOutBusy) return
        mutableManual.update { state ->
            state.copy(
                signOutReview =
                    state.signOutReview
                        ?.takeIf { it.choice == SignOutDataChoice.RemoveData }
                        ?.copy(confirmingRemoval = true)
            )
        }
    }

    fun dismissRemoveConfirmation() {
        if (manual.value.signOutBusy) return
        mutableManual.update { state ->
            state.copy(signOutReview = state.signOutReview?.copy(confirmingRemoval = false))
        }
    }

    fun confirmSignOut(removeDataConfirmed: Boolean = false) {
        val review = manual.value.signOutReview ?: return
        if (manual.value.signOutBusy) return
        if (
            review.choice == SignOutDataChoice.RemoveData &&
                (!removeDataConfirmed || !review.confirmingRemoval)
        )
            return
        val request =
            SignOutRequest(
                accountId = review.target.accountId,
                sessionEpoch = review.target.sessionEpoch,
                choice = review.choice,
                removeDataConfirmed = removeDataConfirmed,
            )
        performSignOut(request)
    }

    fun retrySignOut() {
        if (manual.value.signOutBusy) return
        val pending = state.value as? AccountState.SignOutPending ?: return
        performSignOut(
            SignOutRequest(
                accountId = pending.accountId,
                sessionEpoch = pending.sessionEpoch,
                choice = SignOutDataChoice.KeepData,
            )
        )
    }

    fun openDeleteReview() {
        if (!capabilities.canDeleteAccount) return
        if (manual.value.busy || manual.value.signOutBusy) return
        val current = state.value
        val request =
            when (current) {
                is AccountState.SignedIn -> {
                    if (!current.canDeleteAccount) return
                    AccountDeletionRequest(
                        current.accountId,
                        current.sessionEpoch,
                        current.profileGeneration,
                        expectedLocalOwnerUid = current.accountId.opaqueValue,
                    )
                }
                is AccountState.AwaitingDataChoice -> {
                    if (
                        !current.canDeleteUnclaimedData ||
                            current.context.ownership !is LocalOwnership.Unclaimed
                    )
                        return
                    AccountDeletionRequest(
                        current.accountId,
                        current.sessionEpoch,
                        current.profileGeneration,
                        expectedLocalOwnerUid = null,
                    )
                }
                else -> return
            }
        val profile =
            when (current) {
                is AccountState.SignedIn -> current.profile
                is AccountState.AwaitingDataChoice -> current.profile
            } ?: return
        mutableManual.update {
            it.copy(
                accountDeletion =
                    AccountDeletionUiState(
                        target =
                            AccountDeletionTarget(
                                request,
                                profile.displayName,
                                profile.email,
                            )
                    ),
                feedback = null,
            )
        }
    }

    fun dismissDeleteReview() {
        if (manual.value.accountDeletion.busy || manual.value.accountDeletion.retryAvailable) return
        mutableManual.update {
            it.copy(accountDeletion = AccountDeletionUiState(), feedback = null)
        }
    }

    fun continueAccountDeletion() {
        if (!capabilities.canDeleteAccount) return
        if (manual.value.accountDeletion.busy) return
        mutableManual.update { current ->
            current.copy(
                accountDeletion =
                    current.accountDeletion.target?.let {
                        current.accountDeletion.copy(target = it.copy(confirmingIdentity = true))
                    } ?: current.accountDeletion
            )
        }
    }

    fun confirmAccountDeletion() {
        if (!capabilities.canDeleteAccount) return
        val deletion = manual.value.accountDeletion
        val target = deletion.target ?: return
        if (!target.confirmingIdentity || deletion.busy || manual.value.signOutBusy) return
        if (!state.value.matchesDeletionRequest(target.request)) {
            mutableManual.update {
                it.copy(
                    accountDeletion = AccountDeletionUiState(),
                    feedback =
                        "The signed-in account or local profile changed. Review the account before deleting it.",
                )
            }
            return
        }
        mutableManual.update {
            it.copy(
                busy = true,
                feedback = null,
                accountDeletion = it.accountDeletion.copy(busy = true),
            )
        }
        deletionActionInFlight = true
        viewModelScope.launch { performAccountDeletion(target.request, retry = false) }
    }

    private fun AccountState.matchesDeletionRequest(request: AccountDeletionRequest): Boolean =
        when (this) {
            is AccountState.SignedIn ->
                canDeleteAccount &&
                    accountId == request.accountId &&
                    sessionEpoch == request.sessionEpoch &&
                    profileGeneration == request.profileGeneration &&
                    accountId.opaqueValue == request.expectedLocalOwnerUid
            is AccountState.AwaitingDataChoice ->
                canDeleteUnclaimedData &&
                    context.ownership is LocalOwnership.Unclaimed &&
                    accountId == request.accountId &&
                    sessionEpoch == request.sessionEpoch &&
                    profileGeneration == request.profileGeneration &&
                    request.expectedLocalOwnerUid == null
            else -> false
        }

    fun retryAccountDeletion() {
        // An existing journal must remain recoverable after configuration becomes unavailable.
        if (!manual.value.accountDeletion.retryAvailable || manual.value.accountDeletion.busy)
            return
        mutableManual.update {
            it.copy(
                busy = true,
                feedback = null,
                accountDeletion = it.accountDeletion.copy(busy = true),
            )
        }
        deletionActionInFlight = true
        viewModelScope.launch { performAccountDeletion(null, retry = true) }
    }

    fun cancelAccountDeletion() {
        if (capabilities.mode != AccountExperienceCapabilities.Mode.AuthPreview) return
        val deletion = manual.value.accountDeletion
        val progress = (state.value as? AccountState.AccountDeletionPending)?.progress ?: return
        if (deletionActionInFlight || deletion.busy || !progress.canCancelBeforeActivation()) return
        deletionActionInFlight = true
        mutableManual.update {
            it.copy(
                busy = true,
                feedback = null,
                accountDeletion = it.accountDeletion.copy(busy = true, cancelling = true),
            )
        }
        viewModelScope.launch {
            performAccountDeletion(null, retry = false, cancelUnactivated = true)
        }
    }

    fun acknowledgeDeletionNavigation(targetGeneration: Long) {
        if (manual.value.accountDeletion.completionTargetGeneration == targetGeneration) {
            mutableManual.update {
                it.copy(
                    accountDeletion = it.accountDeletion.copy(completionTargetGeneration = null)
                )
            }
        }
    }

    private suspend fun performAccountDeletion(
        request: AccountDeletionRequest?,
        retry: Boolean,
        cancelUnactivated: Boolean = false,
    ) {
        val sourceProfileGeneration =
            request?.profileGeneration
                ?: (state.value as? AccountState.AccountDeletionPending)
                    ?.progress
                    ?.profileGeneration
                ?: manual.value.accountDeletion.progress?.profileGeneration
        val completionTargetGeneration =
            sourceProfileGeneration?.let { source ->
                runCatching { Math.addExact(source, 1L) }.getOrNull()
            }
        try {
            val result =
                when {
                    cancelUnactivated -> accountGateway.cancelAccountDeletion()
                    retry -> accountGateway.retryAccountDeletion()
                    else -> accountGateway.deleteAccount(checkNotNull(request))
                }
            when (result) {
                AccountActionResult.Completed -> {
                    mutableManual.update {
                        it.copy(
                            busy = false,
                            profileResetEpoch = it.profileResetEpoch + 1,
                            latest = null,
                            undoAvailable = false,
                            status = BackupStatus.LocalOnly,
                            feedback =
                                if (capabilities.mode == AccountExperienceCapabilities.Mode.Demo)
                                    "The demo IronPath account, all demo backups, and this device's training data were deleted. Your Google account was not affected."
                                else
                                    "The requested IronPath account, all of its cloud backups, and this device's reviewed training data were deleted. Your Google account was not affected.",
                            accountDeletion =
                                it.accountDeletion.copy(
                                    target = null,
                                    busy = false,
                                    cancelling = false,
                                    progress = null,
                                    retryAvailable = false,
                                    completionTargetGeneration = completionTargetGeneration,
                                ),
                        )
                    }
                    refreshBackupStatus()
                }
                AccountActionResult.Cancelled -> {
                    val pending = state.value as? AccountState.AccountDeletionPending
                    if (pending != null) showDeletionRetry(pending.progress)
                    else
                        mutableManual.update {
                            it.copy(
                                busy = false,
                                accountDeletion = AccountDeletionUiState(),
                                feedback =
                                    "This deletion request was cancelled. This device's training data was preserved. Another device can still request account deletion later.",
                            )
                        }
                }
                AccountActionResult.Unavailable -> {
                    val pending = state.value as? AccountState.AccountDeletionPending
                    if (pending != null)
                        showDeletionRetry(
                            pending.progress,
                            "Deletion could not continue because the deletion service is unavailable. Retry when the service is available.",
                        )
                    else
                        mutableManual.update {
                            it.copy(
                                busy = false,
                                accountDeletion = AccountDeletionUiState(),
                                feedback =
                                    "Account deletion is unavailable in the current account state.",
                            )
                        }
                }
                is AccountActionResult.Failed -> {
                    val pending = state.value as? AccountState.AccountDeletionPending
                    if (pending != null) showDeletionRetry(pending.progress)
                    else
                        mutableManual.update {
                            it.copy(
                                busy = false,
                                accountDeletion = AccountDeletionUiState(),
                                feedback = accountDeletionFailureMessage(result.reason),
                            )
                        }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val pending = state.value as? AccountState.AccountDeletionPending
            if (pending != null) showDeletionRetry(pending.progress)
            else
                mutableManual.update {
                    it.copy(
                        busy = false,
                        accountDeletion = AccountDeletionUiState(),
                        feedback =
                            "Account deletion could not finish. Check the account state before trying again.",
                    )
                }
        } finally {
            deletionActionInFlight = false
        }
    }

    private fun accountDeletionFailureMessage(reason: AccountFailureReason): String =
        when (reason) {
            AccountFailureReason.ReauthenticationRequired ->
                "Account deletion did not start. Verify the same Google account after reviewing deletion again."
            AccountFailureReason.ServiceUnavailable ->
                "Account deletion could not start because the deletion service is unavailable. Try again later."
            AccountFailureReason.Offline ->
                "Account deletion did not start. Connect to the internet and review deletion again."
            AccountFailureReason.LocalStateUnavailable ->
                "Account deletion could not start. Check account and training data status before trying again."
            AccountFailureReason.Unknown ->
                "Account deletion could not start. Check account status and try again."
        }

    private fun showDeletionRetry(
        progress: AccountDeletionProgress,
        feedback: String = ACCOUNT_DELETION_PENDING_MESSAGE,
    ) {
        mutableManual.update {
            it.copy(
                busy = true,
                review = null,
                signOutReview = null,
                feedback = feedback,
                accountDeletion =
                    it.accountDeletion.copy(
                        target = null,
                        busy = false,
                        cancelling = false,
                        progress = progress,
                        retryAvailable = true,
                    ),
            )
        }
    }

    private fun performSignOut(request: SignOutRequest) {
        val dataWasRemoved =
            request.choice == SignOutDataChoice.RemoveData ||
                state.value is AccountState.SignOutPending
        mutableManual.update {
            it.copy(
                signOutBusy = true,
                signOutReview = it.signOutReview?.copy(confirmingRemoval = false),
                feedback = null,
            )
        }
        viewModelScope.launch {
            try {
                when (val result = accountGateway.signOut(request)) {
                    AccountActionResult.Completed -> {
                        mutableManual.update {
                            it.copy(
                                signOutBusy = false,
                                profileResetEpoch =
                                    if (dataWasRemoved) it.profileResetEpoch + 1
                                    else it.profileResetEpoch,
                                signOutReview = null,
                                review = null,
                                latest = null,
                                undoAvailable = false,
                                status = BackupStatus.LocalOnly,
                                feedback =
                                    when {
                                        capabilities.canUseBackup && dataWasRemoved ->
                                            "Signed out. Training data was removed from this device. The remote backup was not changed."
                                        capabilities.canUseBackup ->
                                            "Signed out. Training data and its account ownership remain on this device."
                                        dataWasRemoved ->
                                            "Signed out. Training data was removed from this device."
                                        else ->
                                            "Signed out. Training data remains on this device and is not linked to this Google identity."
                                    }
                            )
                        }
                        refreshBackupStatus()
                    }
                    AccountActionResult.Cancelled -> {
                        mutableManual.update {
                            it.copy(
                                signOutBusy = false,
                                signOutReview = null,
                                feedback =
                                    "The account changed before sign-out. No other account was cleared."
                            )
                        }
                        refresh()
                    }
                    AccountActionResult.Unavailable -> {
                        mutableManual.update {
                            it.copy(
                                signOutBusy = false,
                                signOutReview = null,
                                feedback = "Sign-out is unavailable in the current account state."
                            )
                        }
                    }
                    is AccountActionResult.Failed -> {
                        val pending = state.value is AccountState.SignOutPending
                        mutableManual.update {
                            it.copy(
                                signOutBusy = false,
                                signOutReview = null,
                                review = null,
                                latest = if (pending) null else it.latest,
                                undoAvailable = if (pending) false else it.undoAvailable,
                                feedback =
                                    if (pending)
                                        "Training data was removed. Finish sign-out to verify and clear this account session."
                                    else
                                        "Sign-out could not finish. Check account and training data status before trying again."
                            )
                        }
                        refreshBackupStatus()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableManual.update {
                    it.copy(
                        signOutBusy = false,
                        signOutReview = null,
                        feedback =
                            "Sign-out could not finish. Check the account state and try again."
                    )
                }
            }
        }
    }

    fun previewBackup() = runManual {
        if (!capabilities.canUseBackup) return@runManual
        discardReview()
        when (val result = backup.previewBackup()) {
            is BackupPreviewResult.Ready ->
                mutableManual.update { it.copy(review = ManualReview.Backup(result.preview)) }
            is BackupPreviewResult.Failed -> showFailure(result.reason)
            BackupPreviewResult.Unavailable -> unavailable()
        }
    }

    fun previewSync() = runManual {
        if (!capabilities.canUseBackup) return@runManual
        discardReview()
        when (val result = backup.previewSync()) {
            is SyncPreviewResult.Ready ->
                mutableManual.update { it.copy(review = ManualReview.Sync(result.preview)) }
            is SyncPreviewResult.Failed -> showFailure(result.reason)
            SyncPreviewResult.Unavailable -> unavailable()
        }
    }

    fun previewRestore() = runManual {
        if (!capabilities.canUseBackup) return@runManual
        discardReview()
        when (val result = backup.previewRestore()) {
            is RestorePreviewResult.Ready ->
                mutableManual.update { it.copy(review = ManualReview.Restore(result.preview)) }
            is RestorePreviewResult.Failed -> showFailure(result.reason)
            RestorePreviewResult.Unavailable -> unavailable()
        }
    }

    fun previewUndo() = runManual {
        if (!capabilities.canUseBackup) return@runManual
        discardReview()
        when (val result = backup.previewUndo()) {
            is UndoPreviewResult.Ready ->
                mutableManual.update { it.copy(review = ManualReview.Undo(result.preview)) }
            is UndoPreviewResult.Failed -> showFailure(result.reason)
            UndoPreviewResult.Unavailable -> unavailable()
        }
    }

    fun selectResolution(resolution: SyncConflictResolution) {
        if (manual.value.busy) return
        val review = (manual.value.review as? ManualReview.Sync)?.preview ?: return
        if (resolution == SyncConflictResolution.KeepLocal && !review.canKeepLocal) return
        if (resolution == SyncConflictResolution.KeepCloud && !review.canKeepCloud) return
        mutableManual.update { it.copy(resolution = resolution, feedback = null) }
    }

    fun confirmDestructive(confirmed: Boolean) {
        if (!manual.value.busy) mutableManual.update { it.copy(destructiveConfirmed = confirmed) }
    }

    fun confirmActiveWorkoutDiscard(confirmed: Boolean) {
        if (!manual.value.busy)
            mutableManual.update {
                it.copy(activeWorkoutDiscardConfirmed = confirmed, feedback = null)
            }
    }

    fun holdGuidance() {
        if (!manual.value.busy)
            mutableManual.update {
                it.copy(feedback = "Press and hold to confirm this whole-backup change.")
            }
    }

    fun confirm() {
        if (!capabilities.canUseBackup) return
        val current = manual.value
        val review = current.review ?: return
        if (
            review is ManualReview.Backup &&
                review.preview.requiresDestructiveConfirmation &&
                !current.destructiveConfirmed
        )
            return
        if (
            review is ManualReview.Sync &&
                review.preview.conflicts.values.sum() > 0 &&
                current.resolution == null
        )
            return
        if (
            review is ManualReview.Restore &&
                review.preview.activeWorkoutDiscardRequired &&
                !current.activeWorkoutDiscardConfirmed
        )
            return
        if (review is ManualReview.Undo && review.preview.activeWorkoutPresent) return
        runManual {
            val result =
                when (review) {
                    is ManualReview.Backup ->
                        backup.confirmBackup(review.id, current.destructiveConfirmed)
                    is ManualReview.Sync -> backup.confirmSync(review.id, current.resolution)
                    is ManualReview.Restore ->
                        backup.confirmRestore(review.id, current.activeWorkoutDiscardConfirmed)
                    is ManualReview.Undo -> backup.confirmUndo(review.id)
                }
            when (result) {
                BackupActionResult.Completed -> {
                    val message =
                        when {
                            review is ManualReview.Backup && review.preview.associationOnly ->
                                "Account ready. No backup was created because there is no included training data."
                            review is ManualReview.Backup ->
                                if (
                                    capabilities.mode ==
                                        AccountExperienceCapabilities.Mode.AuthPreview
                                )
                                    "Manual cloud backup complete."
                                else "Manual backup complete in demo storage."
                            review is ManualReview.Restore ->
                                if (
                                    capabilities.mode ==
                                        AccountExperienceCapabilities.Mode.AuthPreview
                                )
                                    "The latest complete cloud backup replaced the included training data. One local undo is available. The cloud backup is unchanged."
                                else
                                    "The latest complete demo backup replaced the included training data. One local undo is available."
                            review is ManualReview.Undo ->
                                "One undo restored the pre-restore training data. Local changes still need an explicit backup or sync review."
                            else ->
                                "Manual sync complete. Your training data now reflects the confirmed choice."
                        }
                    mutableManual.update {
                        it.copy(
                            review = null,
                            feedback = message,
                            resolution = null,
                            destructiveConfirmed = false,
                            activeWorkoutDiscardConfirmed = false,
                        )
                    }
                    accountGateway.refreshLocal()
                    refreshBackupStatus()
                }
                is BackupActionResult.Failed -> {
                    discardReview()
                    showFailure(result.reason)
                }
                is BackupActionResult.ActiveSessionRequiresConfirmation -> {
                    discardReview()
                    showFailure(BackupFailureReason.ActiveSessionPresent)
                }
                BackupActionResult.Cancelled -> {
                    discardReview()
                    mutableManual.update { it.copy(feedback = "Manual operation cancelled.") }
                }
                BackupActionResult.Unavailable -> {
                    discardReview()
                    unavailable()
                }
            }
        }
    }

    fun leave(onLeave: () -> Unit) {
        if (manual.value.accountDeletion.target != null) {
            dismissDeleteReview()
            return
        }
        if (manual.value.signOutReview != null) {
            dismissSignOutReview()
            return
        }
        if (
            manual.value.busy ||
                manual.value.signOutBusy ||
                state.value == AccountState.SigningOut ||
                state.value is AccountState.SignOutPending ||
                state.value == AccountState.DeletingAccount ||
                state.value is AccountState.AccountDeletionPending
        )
            return
        if (manual.value.review != null) {
            runManual {
                discardReview()
                refreshBackupStatus()
            }
            return
        }
        viewModelScope.launch {
            val current = state.value
            val cancellationRequired =
                current == AccountState.SigningIn ||
                    (current is AccountState.RecoverableError && current.canCancelDataChoice)
            if (current == AccountState.CancellingDataChoice) return@launch
            if (
                !cancellationRequired ||
                    accountGateway.cancelDataChoice() == AccountActionResult.Completed
            ) {
                mutableManual.update { it.copy(feedback = null) }
                refreshBackupStatus()
                onLeave()
            }
        }
    }

    private suspend fun discardReview() {
        manual.value.review?.let { backup.discardPreview(it.id) }
        mutableManual.update {
            it.copy(
                review = null,
                resolution = null,
                destructiveConfirmed = false,
                activeWorkoutDiscardConfirmed = false,
            )
        }
    }

    private fun runManual(action: suspend () -> Unit) {
        if (
            !capabilities.canUseBackup ||
                manual.value.busy ||
                manual.value.signOutBusy ||
                manual.value.accountDeletion.busy
        )
            return
        mutableManual.update { it.copy(busy = true, feedback = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                discardReview()
                showFailure(BackupFailureReason.Unknown)
            } finally {
                mutableManual.update { it.copy(busy = false) }
            }
        }
    }

    private fun showFailure(reason: BackupFailureReason) {
        mutableManual.update { it.copy(feedback = backupFailureMessage(reason)) }
    }

    private suspend fun refreshBackupStatus() {
        if (capabilities.canUseBackup) backup.refreshStatus()
    }

    private fun unavailable() {
        mutableManual.update {
            it.copy(
                feedback = "This manual action is not available. Your local data remains available."
            )
        }
    }
}
