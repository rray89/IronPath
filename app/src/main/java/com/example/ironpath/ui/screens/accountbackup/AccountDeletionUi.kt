package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.AccountDeletionProgress
import com.example.ironpath.domain.account.AccountDeletionRemoteState
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountState

internal const val ACCOUNT_DELETION_PENDING_MESSAGE =
    "Deletion is pending. Training data is locked until cleanup is verified. Retry when connected."

internal const val ACCOUNT_DELETION_RESERVED_MESSAGE =
    "The last verified status is waiting to start. Retry asks you to verify the same Google account before requesting deletion. You may ask to cancel if no device has started deletion."
internal const val ACCOUNT_DELETION_CANCELLED_VERIFICATION_MESSAGE =
    "The service cancelled this reservation. Training data stays locked until the local profile and account status are verified. Retry to finish verification."
internal const val ACCOUNT_DELETION_COMPLETED_VERIFICATION_MESSAGE =
    "The service completed deletion. Training data stays locked until local cleanup and account status are verified. Retry to finish verification."
internal const val ACCOUNT_DELETION_CANCELLATION_SCOPE =
    "The service checks whether this or another device has started deletion. A confirmed cancellation preserves this device's data; it does not prevent another device starting deletion later."
internal const val ACCOUNT_DELETION_INTEGRITY_MESSAGE =
    "This saved deletion has no verified recovery receipt. Training data stays locked. Restore the original deletion service configuration or obtain recovery help. Signing in again is not proof that deletion completed."

internal fun AccountDeletionProgress.hasAcknowledgedReceipt(): Boolean =
    !serviceBinding.isNullOrBlank() &&
        !receiptSecret.isNullOrBlank() &&
        !subjectBinding.isNullOrBlank() &&
        !installationId.isNullOrBlank() &&
        receiptVersion > 0

internal fun AccountDeletionProgress.canCancelBeforeActivation(): Boolean =
    stage == AccountDeletionStage.PREPARED &&
        remoteState == AccountDeletionRemoteState.RESERVED &&
        hasAcknowledgedReceipt()

internal fun accountDeletionRecoveryMessage(
    progress: AccountDeletionProgress?,
    demoStorage: Boolean
): String =
    when {
        !demoStorage && progress != null && !progress.hasAcknowledgedReceipt() ->
            ACCOUNT_DELETION_INTEGRITY_MESSAGE
        !demoStorage && progress?.remoteState == AccountDeletionRemoteState.CANCELLED_NO_DELETE ->
            ACCOUNT_DELETION_CANCELLED_VERIFICATION_MESSAGE
        !demoStorage && progress?.remoteState == AccountDeletionRemoteState.COMPLETE ->
            ACCOUNT_DELETION_COMPLETED_VERIFICATION_MESSAGE
        !demoStorage && progress?.canCancelBeforeActivation() == true ->
            ACCOUNT_DELETION_RESERVED_MESSAGE
        else -> ACCOUNT_DELETION_PENDING_MESSAGE
    }

internal fun AccountDeletionUiState.blocksAccountActions(state: AccountState): Boolean =
    busy ||
        retryAvailable ||
        progress != null ||
        state == AccountState.DeletingAccount ||
        state is AccountState.AccountDeletionPending

@Composable
internal fun AccountDeletionAction(
    state: AccountState,
    manual: ManualBackupUiState,
    onOpenReview: () -> Unit,
    demoStorage: Boolean = false,
) {
    if (
        (state is AccountState.SignedIn && state.canDeleteAccount) ||
            (state is AccountState.AwaitingDataChoice && state.canDeleteUnclaimedData)
    ) {
        TextButton(
            onClick = onOpenReview,
            enabled = !manual.busy && !manual.signOutBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("DELETE ACCOUNT")
        }
    } else if (!demoStorage) {
        Text(
            "Account deletion is unavailable for this account or configuration.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The only account surface available once deletion starts, including after recreation. */
@Composable
internal fun AccountDeletionRecoveryScreen(
    state: AccountState,
    manual: ManualBackupUiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    demoStorage: Boolean = false,
    onCancelUnactivated: (() -> Unit)? = null,
) {
    val busy = manual.accountDeletion.busy || state == AccountState.DeletingAccount
    val progress =
        (state as? AccountState.AccountDeletionPending)?.progress ?: manual.accountDeletion.progress
    val pending = progress != null
    val canCancel =
        !demoStorage && progress?.canCancelBeforeActivation() == true && onCancelUnactivated != null
    val cancelling = manual.accountDeletion.cancelling
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when {
                cancelling -> "Checking cancellation"
                busy -> "Deleting account and data"
                progress?.canCancelBeforeActivation() == true && !demoStorage ->
                    "Deletion is waiting to start"
                else -> "Deletion needs retry"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        if (demoStorage) Text("Demo account. No Google or cloud connection.")
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            if (cancelling)
                "Checking whether the service can cancel this reservation. Training data remains locked until the result is verified."
            else if (pending) accountDeletionRecoveryMessage(progress, demoStorage)
            else if (demoStorage)
                "Deleting the demo IronPath account and all data. Keep IronPath open while this finishes."
            else
                "Confirming identity and deleting your IronPath account and data. Complete Google verification if prompted. Keep IronPath open while this finishes.",
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription =
                        if (cancelling) "Account cancellation in progress"
                        else if (busy) "Account deletion in progress"
                        else "Account deletion pending"
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (pending) {
            Button(
                onClick = onRetry,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("RETRY DELETION")
            }
        }
        if (canCancel) {
            Text(ACCOUNT_DELETION_CANCELLATION_SCOPE, style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = checkNotNull(onCancelUnactivated),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("CANCEL IF NOT STARTED")
            }
        }
        manual.feedback
            ?.takeIf { it != ACCOUNT_DELETION_PENDING_MESSAGE }
            ?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
internal fun AccountDeletionReviewDialog(
    deletion: AccountDeletionUiState,
    actions: ManualBackupActions,
    demoStorage: Boolean = false,
) {
    val target = deletion.target ?: return
    if (deletion.busy || deletion.retryAvailable || deletion.progress != null) return
    val accountScope = if (demoStorage) "demo IronPath account" else "IronPath account"
    val backupScope = if (demoStorage) "all of its demo backups" else "all of its cloud backups"
    val localScope =
        if (target.request.expectedLocalOwnerUid == null) "all unclaimed local training data"
        else "all current local training data"
    AlertDialog(
        onDismissRequest = actions.dismissDeleteReview,
        title = {
            Text(
                if (target.confirmingIdentity) "Confirm account deletion"
                else "Delete account and data?"
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Confirm you are signed in as ${target.displayName}.")
                Text(target.email)
                Text(
                    "This permanently deletes this $accountScope, $backupScope, $localScope, and any workout in progress. Restore undo and backup metadata are also removed."
                )
                Text(
                    if (demoStorage) "This cannot be undone. Your Google account is not affected."
                    else
                        "Once the service starts deletion, it cannot be cancelled or undone. If it is still waiting to start, you can ask the service to cancel. Another device may start deletion before cancellation is confirmed. Your Google account is not affected."
                )
                if (!demoStorage) {
                    Text(
                        "After the final confirmation, verify this same Google account. Cancelling Google verification before deletion starts leaves training data and backups unchanged."
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick =
                    if (target.confirmingIdentity) actions.confirmAccountDeletion
                    else actions.continueAccountDeletion,
            ) {
                Text(if (target.confirmingIdentity) "DELETE ACCOUNT AND ALL DATA" else "CONTINUE")
            }
        },
        dismissButton = { TextButton(onClick = actions.dismissDeleteReview) { Text("CANCEL") } },
    )
}
