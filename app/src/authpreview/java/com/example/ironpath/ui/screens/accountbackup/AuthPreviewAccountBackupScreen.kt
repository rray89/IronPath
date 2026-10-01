package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.AccountFailureReason
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.account.SignOutDataChoice
import com.example.ironpath.ui.testing.TestTags

@Composable
internal fun AuthPreviewAccountBackupScreen(
    state: AccountState,
    signInAvailable: Boolean,
    manual: ManualBackupUiState,
    manualActions: ManualBackupActions,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile =
        when (state) {
            is AccountState.SignedIn -> state.profile
            is AccountState.AwaitingDataChoice -> state.profile
            is AccountState.SignOutPending -> state.profile
            else -> null
        }
    if (manual.review is ManualReview.Backup) {
        ManualBackupReviewScreen(
            manual,
            manualActions,
            manualActions.cancelReview,
            modifier,
            demoStorage = false
        )
        return
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("GOOGLE ACCOUNT", style = MaterialTheme.typography.headlineMedium)
        AccountSection(
            "GOOGLE IDENTITY",
            authPreviewAccountStateDetail(state, signInAvailable),
            modifier = Modifier.testTag(TestTags.ACCOUNT_STATUS),
        )
        profile?.let {
            AccountSection(
                it.displayName,
                it.email.takeIf(String::isNotBlank).orEmpty(),
                initials =
                    it.displayName
                        .split(" ")
                        .filter(String::isNotBlank)
                        .take(2)
                        .map { name -> name.first().uppercaseChar() }
                        .joinToString("")
                        .ifBlank { "G" },
            )
        }
        when (state) {
            AccountState.LocalOnly ->
                Button(
                    onClick = onSignIn,
                    enabled = signInAvailable && !manual.busy && !manual.signOutBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("SIGN IN WITH GOOGLE")
                }
            AccountState.Loading,
            AccountState.SigningIn,
            AccountState.SavingSignIn,
            AccountState.CancellingDataChoice,
            AccountState.SigningOut ->
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(authPreviewAccountStateLabel(state))
                }
            is AccountState.SignOutPending ->
                Button(
                    onClick = manualActions.retrySignOut,
                    enabled = !manual.busy && !manual.signOutBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("FINISH SIGN OUT")
                }
            is AccountState.RecoverableError ->
                Button(
                    onClick = onRetry,
                    enabled = !manual.busy && !manual.signOutBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("TRY AGAIN")
                }
            else -> Unit
        }
        if (state is AccountState.SignedIn || state is AccountState.AwaitingDataChoice) {
            TextButton(
                onClick = manualActions.openSignOutReview,
                enabled = !manual.busy && !manual.signOutBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("SIGN OUT")
            }
        }
        AccountSection(
            "CLOUD BACKUP",
            if (signInAvailable)
                "Training data stays local until you review and confirm a manual cloud backup."
            else
                "Cloud backup is unavailable until private Firebase preview configuration is supplied."
        )
        if (state is AccountState.SignedIn || state is AccountState.AwaitingDataChoice) {
            CloudManualBackupOverview(manual, signInAvailable, manualActions.previewBackup, onRetry)
        }
        Text(
            "Cloud restore, manual sync and account deletion are not available in this preview.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (state !is AccountState.SignedIn && state !is AccountState.AwaitingDataChoice) {
            if (manual.busy)
                Text(
                    "Checking account status",
                    modifier =
                        Modifier.semantics {
                            liveRegion = LiveRegionMode.Polite
                            stateDescription = "Busy"
                        },
                )
            manual.feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    manual.signOutReview?.let { review ->
        if (review.confirmingRemoval) {
            AlertDialog(
                onDismissRequest = manualActions.dismissRemoveConfirmation,
                title = { Text("Remove training data?") },
                text = {
                    Text(
                        "This removes training data from this device, including an active workout. " +
                            "It does not delete your Google account.",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = { manualActions.confirmSignOut(true) },
                        enabled = !manual.signOutBusy,
                    ) {
                        Text("REMOVE DATA AND SIGN OUT")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = manualActions.dismissRemoveConfirmation,
                        enabled = !manual.signOutBusy,
                    ) {
                        Text("BACK")
                    }
                },
            )
        } else {
            AlertDialog(
                onDismissRequest = manualActions.dismissSignOutReview,
                title = { Text("Sign out?") },
                text = {
                    Column(
                        modifier = Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Choose what happens to training data on this device.")
                        AuthPreviewSignOutChoice(
                            "Keep data on this device",
                            review.choice == SignOutDataChoice.KeepData,
                            !manual.signOutBusy,
                        ) {
                            manualActions.chooseSignOutChoice(SignOutDataChoice.KeepData)
                        }
                        AuthPreviewSignOutChoice(
                            "Remove data from this device",
                            review.choice == SignOutDataChoice.RemoveData,
                            !manual.signOutBusy,
                        ) {
                            manualActions.chooseSignOutChoice(SignOutDataChoice.RemoveData)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (review.choice == SignOutDataChoice.KeepData)
                                manualActions.confirmSignOut(false)
                            else manualActions.requestRemoveConfirmation()
                        },
                        enabled = !manual.signOutBusy,
                    ) {
                        Text(
                            if (review.choice == SignOutDataChoice.KeepData) "SIGN OUT"
                            else "CONTINUE"
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = manualActions.dismissSignOutReview,
                        enabled = !manual.signOutBusy,
                    ) {
                        Text("CANCEL")
                    }
                },
            )
        }
    }
}

@Composable
private fun AuthPreviewSignOutChoice(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    enabled = enabled,
                    role = Role.RadioButton
                )
                .semantics { stateDescription = if (selected) "Selected" else "Not selected" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun authPreviewAccountStateLabel(state: AccountState): String =
    when (state) {
        AccountState.Loading -> "Checking account"
        AccountState.SigningIn -> "Waiting for Google"
        AccountState.SavingSignIn -> "Finishing sign-in"
        AccountState.CancellingDataChoice -> "Cancelling sign-in"
        AccountState.SigningOut -> "Signing out"
        else -> "Google account"
    }

private fun authPreviewAccountStateDetail(state: AccountState, signInAvailable: Boolean): String =
    when (state) {
        AccountState.Loading -> "Checking the saved Google identity on this device."
        AccountState.LocalOnly ->
            if (signInAvailable) "No Google identity is connected to IronPath."
            else
                "Google sign-in is unavailable because private Firebase preview configuration was not supplied."
        AccountState.NeedsReauthentication -> "Sign in with Google again to continue."
        AccountState.SigningIn -> "Choose a Google account to identify yourself in this preview."
        AccountState.SavingSignIn -> "Finishing Google sign-in. Local training data is unchanged."
        AccountState.CancellingDataChoice ->
            "Cancelling Google sign-in. Local training data is unchanged."
        AccountState.SigningOut ->
            "Signing out. Local training data follows your Keep or Remove choice."
        is AccountState.SignedIn ->
            "Google identity connected. Account identity alone does not upload training data."
        is AccountState.AwaitingDataChoice ->
            "Google identity connected. Local training data remains local."
        is AccountState.SignOutPending ->
            "Local training data removal is committed. Finish sign-out to clear this Google identity."
        is AccountState.RecoverableError ->
            when (state.reason) {
                AccountFailureReason.LocalStateUnavailable ->
                    "Local account state could not be verified. Try again."
                AccountFailureReason.Offline ->
                    "Google sign-in is unavailable offline. Local training data remains available."
                AccountFailureReason.ReauthenticationRequired ->
                    "Sign in with Google again to continue."
                AccountFailureReason.ServiceUnavailable,
                AccountFailureReason.Unknown ->
                    "Google sign-in is temporarily unavailable. Try again."
            }
        is AccountState.AccountDeletionPending -> "Account deletion is unavailable in this preview."
        AccountState.DeletingAccount -> "Account deletion is unavailable in this preview."
    }
