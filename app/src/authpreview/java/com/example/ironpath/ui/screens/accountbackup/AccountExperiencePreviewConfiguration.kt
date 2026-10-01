package com.example.ironpath.ui.screens.accountbackup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.example.ironpath.domain.account.AccountState

internal const val ACCOUNT_EXPERIENCE_PREVIEW_ENABLED = true

private const val ACCOUNT_BACKUP_ROUTE = "account_backup"

internal val accountExperienceEntryContent =
    AccountExperienceEntryContent(
        privacyCopy =
            "Google sign-in identifies your account in this preview. Training data stays on this " +
                "device and is not uploaded or associated. Cloud backup is unavailable.",
        signInLabel = "SIGN IN WITH GOOGLE",
        signInNotice = "Signing in does not upload or associate local training data.",
    )

internal val accountExperienceDrawerContent =
    AccountExperienceDrawerContent(
        contentDescription =
            "Google account preview. Training data stays on this device and is not uploaded or " +
                "associated. Cloud backup is unavailable in this preview. Open Google Account.",
        stateDescription = "Google account preview. Cloud backup unavailable.",
        title = "Google account preview",
        actionLabel = "Cloud backup unavailable",
    )

internal fun NavHostController.openAccountExperiencePreview() {
    navigate(ACCOUNT_BACKUP_ROUTE) { launchSingleTop = true }
}

internal fun openAccountExperiencePreview(onDestinationSelected: (String) -> Unit) {
    onDestinationSelected(ACCOUNT_BACKUP_ROUTE)
}

internal fun NavGraphBuilder.accountExperiencePreviewDestination(
    innerPadding: PaddingValues,
    navController: NavHostController,
    state: AccountState,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    manual: ManualBackupUiState,
    manualActions: ManualBackupActions,
    accountSignInAvailable: Boolean,
) {
    @Suppress("UNUSED_VARIABLE") val retainedController = navController
    composable(ACCOUNT_BACKUP_ROUTE) {
        LaunchedEffect(Unit) { onRetry() }
        BackHandler(onBack = onCancel)
        AuthPreviewAccountBackupScreen(
            state = state,
            signInAvailable = accountSignInAvailable,
            manual = manual,
            manualActions = manualActions,
            onSignIn = onSignIn,
            onRetry = onRetry,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

internal fun isAccountExperiencePreviewRoute(route: String?): Boolean =
    route == ACCOUNT_BACKUP_ROUTE

internal fun isAccountBackupRoute(route: String?): Boolean = route == ACCOUNT_BACKUP_ROUTE

internal fun accountExperiencePreviewTopBarTitle(route: String?): String? =
    if (route == ACCOUNT_BACKUP_ROUTE) "GOOGLE ACCOUNT" else null
