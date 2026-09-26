package com.example.ironpath.ui.screens.accountbackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountContextReader
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
) : ViewModel() {
    val state = accountGateway.state
    private val mutableManual = MutableStateFlow(ManualBackupUiState())
    val manual: StateFlow<ManualBackupUiState> = mutableManual

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
            backup.refreshStatus()
            localContext.changes
                .catch { emit(Unit) }
                .collect {
                    accountGateway.refreshLocal()
                    backup.refreshStatus()
                }
        }
    }

    fun signIn() {
        if (manual.value.busy || manual.value.signOutBusy) return
        viewModelScope.launch {
            val result = accountGateway.startGoogleSignIn()
            backup.refreshStatus()
            if (result == AccountActionResult.Completed) refreshLatestBackupIfEligible()
        }
    }

    fun recoverUnreadableSession() {
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
            backup.refreshStatus()
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
        mutableManual.update { it.copy(feedback = null) }
        viewModelScope.launch {
            accountGateway.refresh()
            if (state.value.isEligibleForLatestBackupLookup()) {
                refreshLatestBackupIfEligible()
            } else {
                backup.refreshStatus()
            }
        }
    }

    private suspend fun refreshLatestBackupIfEligible() {
        if (!state.value.isEligibleForLatestBackupLookup()) return
        when (val result = backup.latestCompleteBackup()) {
            is BackupLookupResult.Failed -> showFailure(result.reason)
            else -> Unit
        }
    }

    private fun AccountState.isEligibleForLatestBackupLookup() =
        this is AccountState.SignedIn || this is AccountState.AwaitingDataChoice

    fun keepDeviceEmpty() {
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
                    backup.refreshStatus()
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
                                signOutReview = null,
                                review = null,
                                latest = null,
                                undoAvailable = false,
                                status = BackupStatus.LocalOnly,
                                feedback =
                                    if (dataWasRemoved)
                                        "Signed out. Training data was removed from this device. The remote backup was not changed."
                                    else
                                        "Signed out. Training data and its account ownership remain on this device."
                            )
                        }
                        backup.refreshStatus()
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
                        backup.refreshStatus()
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
        discardReview()
        when (val result = backup.previewBackup()) {
            is BackupPreviewResult.Ready ->
                mutableManual.update { it.copy(review = ManualReview.Backup(result.preview)) }
            is BackupPreviewResult.Failed -> showFailure(result.reason)
            BackupPreviewResult.Unavailable -> unavailable()
        }
    }

    fun previewSync() = runManual {
        discardReview()
        when (val result = backup.previewSync()) {
            is SyncPreviewResult.Ready ->
                mutableManual.update { it.copy(review = ManualReview.Sync(result.preview)) }
            is SyncPreviewResult.Failed -> showFailure(result.reason)
            SyncPreviewResult.Unavailable -> unavailable()
        }
    }

    fun previewRestore() = runManual {
        discardReview()
        when (val result = backup.previewRestore()) {
            is RestorePreviewResult.Ready ->
                mutableManual.update { it.copy(review = ManualReview.Restore(result.preview)) }
            is RestorePreviewResult.Failed -> showFailure(result.reason)
            RestorePreviewResult.Unavailable -> unavailable()
        }
    }

    fun previewUndo() = runManual {
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
                                "Manual backup complete in demo storage."
                            review is ManualReview.Restore ->
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
                    backup.refreshStatus()
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
        if (manual.value.signOutReview != null) {
            dismissSignOutReview()
            return
        }
        if (
            manual.value.busy ||
                manual.value.signOutBusy ||
                state.value == AccountState.SigningOut ||
                state.value is AccountState.SignOutPending
        )
            return
        if (manual.value.review != null) {
            runManual {
                discardReview()
                backup.refreshStatus()
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
                backup.refreshStatus()
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
        if (manual.value.busy || manual.value.signOutBusy) return
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

    private fun unavailable() {
        mutableManual.update {
            it.copy(
                feedback = "This manual action is not available. Your local data remains available."
            )
        }
    }
}
