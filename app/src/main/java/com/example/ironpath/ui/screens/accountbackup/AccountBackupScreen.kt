package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.AccountFailureReason
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.account.LocalOwnership
import com.example.ironpath.domain.account.SignOutDataChoice
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow

@Composable
fun AccountBackupScreen(
    state: AccountState,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onPreview: () -> Unit,
    modifier: Modifier = Modifier,
    manual: ManualBackupUiState = ManualBackupUiState(),
    manualActions: ManualBackupActions = ManualBackupActions(),
) {
    if (manual.review != null) {
        ManualBackupReviewScreen(manual, manualActions, onCancel, modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("YOUR ACCOUNT", style = MaterialTheme.typography.headlineMedium)
        AccountSection("DEMO ACCOUNT", "Demo account. No Google or cloud connection.")
        AccountSection(
            accountStatusLabel(state),
            accountStatusDetail(state),
            modifier = Modifier.testTag(TestTags.ACCOUNT_STATUS)
        )
        val profile =
            when (state) {
                is AccountState.AwaitingDataChoice -> state.profile
                is AccountState.SignedIn -> state.profile
                else -> null
            }
        if (profile != null)
            AccountSection(
                profile.displayName,
                profile.email,
                initials =
                    profile.displayName
                        .split(" ")
                        .filter { it.isNotBlank() }
                        .take(2)
                        .map { it.first().uppercaseChar() }
                        .joinToString("")
                        .ifEmpty { "IP" },
            )
        when (state) {
            AccountState.LocalOnly ->
                Button(onClick = onSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text("SIGN IN WITH GOOGLE")
                }
            AccountState.Loading,
            AccountState.SigningIn,
            AccountState.SavingSignIn,
            AccountState.CancellingDataChoice,
            AccountState.SigningOut -> {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(accountStatusLabel(state))
                }
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
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("TRY AGAIN") }
            else -> Unit
        }
        if (state is AccountState.RecoverableError && state.canRecoverUnreadableSession) {
            TextButton(
                onClick = manualActions.recoverUnreadableSession,
                enabled = !manual.busy && !manual.signOutBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("CLEAR INVALID DEMO SESSION")
            }
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
        Text(
            "Signing in identifies your account. Your training data stays local until you choose a manual backup or restore.",
            style = MaterialTheme.typography.bodyMedium
        )
        ManualBackupOverview(
            state,
            manual,
            manualActions,
            onRetry,
            onDecideLater = onCancel,
        )
        if (
            state == AccountState.SigningIn ||
                (state is AccountState.RecoverableError && state.canCancelDataChoice)
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !manual.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CANCEL ACCOUNT SETUP")
            }
        } else if (
            state is AccountState.AwaitingDataChoice && !shouldOfferEmptyBackupChoice(state)
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !manual.busy && !manual.signOutBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("DECIDE LATER")
            }
        }
        TextButton(
            onClick = onPreview,
            enabled = !manual.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("EXPLORE BACKUP PREVIEW")
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
                            "Your remote backup will not be deleted."
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Choose what happens to training data on this device.")
                        SignOutChoiceRow(
                            label = "Keep data on this device",
                            selected = review.choice == SignOutDataChoice.KeepData,
                            enabled = !manual.signOutBusy,
                            onClick = {
                                manualActions.chooseSignOutChoice(SignOutDataChoice.KeepData)
                            },
                        )
                        SignOutChoiceRow(
                            label = "Remove data from this device",
                            selected = review.choice == SignOutDataChoice.RemoveData,
                            enabled = !manual.signOutBusy,
                            onClick = {
                                manualActions.chooseSignOutChoice(SignOutDataChoice.RemoveData)
                            },
                        )
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
private fun SignOutChoiceRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier.fillMaxWidth()
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    enabled = enabled,
                    role = Role.RadioButton,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

internal fun accountStatusLabel(state: AccountState): String =
    when (state) {
        AccountState.Loading -> "Checking account"
        AccountState.LocalOnly -> "Local only"
        AccountState.SigningIn -> "Signing in"
        AccountState.SavingSignIn -> "Finishing sign in"
        AccountState.CancellingDataChoice -> "Cancelling account setup"
        is AccountState.AwaitingDataChoice ->
            if (
                state.context.ownership is LocalOwnership.Account &&
                    state.context.ownership.accountId == state.accountId
            )
                "Backup review required"
            else "Data choice required"
        is AccountState.SignedIn -> "Signed in"
        is AccountState.RecoverableError -> "Account needs attention"
        AccountState.NeedsReauthentication -> "Needs sign-in"
        is AccountState.SignOutPending -> "Sign-out needs completion"
        AccountState.SigningOut -> "Signing out"
        AccountState.DeletingAccount -> "Account unavailable"
    }

internal fun accountStatusDetail(state: AccountState): String =
    when (state) {
        AccountState.Loading -> "Checking this installation before enabling account actions."
        AccountState.LocalOnly ->
            "Your training data is stored on this device. No account is connected."
        AccountState.SigningIn -> "Selecting the demo account. No training data moves."
        AccountState.SavingSignIn ->
            "The demo account is selected. Saving the signed-in session; no training data moves."
        AccountState.CancellingDataChoice ->
            "Removing the pending demo session. Your training data stays unchanged."
        is AccountState.AwaitingDataChoice ->
            when {
                state.context.ownership is LocalOwnership.Account &&
                    state.context.ownership.accountId != state.accountId ->
                    "This device is linked to another account. Backup, sync, and restore are unavailable for this account."
                state.context.ownership is LocalOwnership.Account &&
                    state.context.ownership.accountId == state.accountId &&
                    state.context.activeWorkoutPresent ->
                    "This local profile is associated with the signed-in demo account. An active workout is present, and backup lineage still needs review."
                state.context.ownership is LocalOwnership.Account &&
                    state.context.ownership.accountId == state.accountId ->
                    "This local profile is associated with the signed-in demo account. Its backup lineage still needs review; no training data was uploaded or replaced."
                state.context.activeWorkoutPresent ->
                    "An active workout is present. Keep this device empty is unavailable until the workout is finished or discarded."
                state.context.localDataIsEmpty ->
                    "The demo account is signed in. This empty device stays unlinked until you choose an option; no backup is changed."
                else ->
                    "The demo account is signed in. Training data remains local; sign-in has not linked, uploaded, merged, or replaced it."
            }
        is AccountState.SignedIn ->
            "This local profile is associated with the signed-in demo account. Training data remains on this device; sign-in does not imply a shared backup."
        is AccountState.SignOutPending ->
            "Training data removal is complete. Finish sign-out to clear this account session."
        is AccountState.RecoverableError ->
            when (state.reason) {
                AccountFailureReason.LocalStateUnavailable ->
                    if (state.canRecoverUnreadableSession)
                        "The stored demo session is unreadable. Clear this invalid session record to return to local-only; training data stays unchanged."
                    else
                        "Account and training data status could not be verified. Check again before continuing."
                AccountFailureReason.Offline ->
                    "Sign-in is unavailable offline. Try again when connected; local workouts remain available."
                AccountFailureReason.ReauthenticationRequired ->
                    "This account needs sign-in again. Local workouts remain available."
                AccountFailureReason.ServiceUnavailable,
                AccountFailureReason.Unknown ->
                    "Sign-in is temporarily unavailable. Try again; local workouts remain available."
            }
        else -> "Local workouts remain available."
    }

@Composable
internal fun AccountSection(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    initials: String? = null,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .semantics(mergeDescendants = true) { stateDescription = title }
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (initials != null)
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
                        .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(initials, style = MaterialTheme.typography.titleMedium)
            }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}
