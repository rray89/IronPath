package com.example.ironpath.ui.screens.accountbackup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.domain.account.AccountActionResult
import com.example.ironpath.domain.account.AccountContextReader
import com.example.ironpath.domain.account.AccountGateway
import com.example.ironpath.domain.account.AccountState
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
        if (manual.value.busy) return
        viewModelScope.launch {
            accountGateway.startGoogleSignIn()
            backup.refreshStatus()
        }
    }

    fun refresh() {
        if (manual.value.busy) return
        mutableManual.update { it.copy(feedback = null) }
        viewModelScope.launch {
            accountGateway.refresh()
            if (
                state.value is AccountState.SignedIn ||
                    state.value is AccountState.AwaitingDataChoice
            ) {
                when (val result = backup.latestCompleteBackup()) {
                    is BackupLookupResult.Failed -> showFailure(result.reason)
                    else -> Unit
                }
            } else {
                backup.refreshStatus()
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
        runManual {
            val result =
                when (review) {
                    is ManualReview.Backup ->
                        backup.confirmBackup(review.id, current.destructiveConfirmed)
                    is ManualReview.Sync -> backup.confirmSync(review.id, current.resolution)
                }
            when (result) {
                BackupActionResult.Completed -> {
                    val message =
                        when {
                            review is ManualReview.Backup && review.preview.associationOnly ->
                                "Account ready. No backup was created because there is no included training data."
                            review is ManualReview.Backup ->
                                "Manual backup complete in demo storage."
                            else ->
                                "Manual sync complete. Your training data now reflects the confirmed choice."
                        }
                    mutableManual.update {
                        it.copy(
                            review = null,
                            feedback = message,
                            resolution = null,
                            destructiveConfirmed = false
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
        if (manual.value.busy) return
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
                    current is AccountState.AwaitingDataChoice ||
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
            it.copy(review = null, resolution = null, destructiveConfirmed = false)
        }
    }

    private fun runManual(action: suspend () -> Unit) {
        if (manual.value.busy) return
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
