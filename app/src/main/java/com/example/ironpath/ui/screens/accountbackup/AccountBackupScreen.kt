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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.AccountFailureReason
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.account.LocalOwnership
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
) {
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
            AccountState.CancellingDataChoice -> {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text(accountStatusLabel(state))
                }
            }
            is AccountState.RecoverableError ->
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("TRY AGAIN") }
            else -> Unit
        }
        Text(
            "Signing in identifies your account. Your training data stays local until you choose a manual backup or restore.",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "Manual backup, sync, and restore are not available in this build.",
            style = MaterialTheme.typography.bodyMedium
        )
        listOf("BACK UP NOW", "REVIEW MANUAL SYNC", "PREVIEW WHOLE-BACKUP RESTORE").forEach { label
            ->
            OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(label)
            }
        }
        if (
            state == AccountState.SigningIn ||
                state is AccountState.AwaitingDataChoice ||
                (state is AccountState.RecoverableError && state.canCancelDataChoice)
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("CANCEL ACCOUNT SETUP")
            }
        }
        TextButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
            Text("EXPLORE BACKUP PREVIEW")
        }
    }
}

internal fun accountStatusLabel(state: AccountState): String =
    when (state) {
        AccountState.Loading -> "Checking account"
        AccountState.LocalOnly -> "Local only"
        AccountState.SigningIn -> "Signing in"
        AccountState.CancellingDataChoice -> "Cancelling account setup"
        is AccountState.AwaitingDataChoice -> "Data choice required"
        is AccountState.SignedIn -> "Signed in — manual operations unavailable"
        is AccountState.RecoverableError -> "Account needs attention"
        AccountState.NeedsReauthentication -> "Needs sign-in"
        AccountState.SigningOut,
        AccountState.DeletingAccount -> "Account unavailable"
    }

internal fun accountStatusDetail(state: AccountState): String =
    when (state) {
        AccountState.Loading -> "Checking this installation before enabling account actions."
        AccountState.LocalOnly ->
            "Your training data is stored on this device. No account is connected."
        AccountState.SigningIn -> "Selecting the demo account. No training data moves."
        AccountState.CancellingDataChoice ->
            "Removing the pending demo session. Your training data stays unchanged."
        is AccountState.AwaitingDataChoice ->
            when {
                state.context.ownership is LocalOwnership.Account &&
                    state.context.ownership.accountId != state.accountId ->
                    "This device is linked to another account. Backup, sync, and restore are unavailable for this account."
                state.context.localDataIsEmpty ->
                    "No training data is saved here yet. Sign-in has not linked local data or created a backup."
                else ->
                    "Your training data remains local. Sign-in has not linked, uploaded, merged, or replaced it."
            }
        is AccountState.SignedIn ->
            "This account matches the existing local owner. No manual operation has run."
        is AccountState.RecoverableError ->
            when (state.reason) {
                AccountFailureReason.LocalStateUnavailable ->
                    "Account state could not be saved or verified. Try again. Local workouts remain available."
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
private fun AccountSection(
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
