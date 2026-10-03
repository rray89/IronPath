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
import com.example.ironpath.domain.account.AccountState

internal const val ACCOUNT_DELETION_PENDING_MESSAGE =
    "Deletion is pending. Training data is locked until cleanup is verified. Retry when connected."

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
) {
    val busy = manual.accountDeletion.busy || state == AccountState.DeletingAccount
    val pending =
        state is AccountState.AccountDeletionPending || manual.accountDeletion.progress != null
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (busy) "Deleting account and data" else "Deletion needs retry",
            style = MaterialTheme.typography.headlineMedium,
        )
        if (demoStorage) Text("Demo account. No Google or cloud connection.")
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            if (pending) ACCOUNT_DELETION_PENDING_MESSAGE
            else if (demoStorage)
                "Deleting the demo IronPath account and all data. Keep IronPath open while this finishes."
            else
                "Confirming identity and deleting your IronPath account and data. Complete Google verification if prompted. Keep IronPath open while this finishes.",
            modifier =
                Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                    stateDescription =
                        if (busy) "Account deletion in progress" else "Account deletion pending"
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
                Text("This cannot be undone. Your Google account is not affected.")
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
