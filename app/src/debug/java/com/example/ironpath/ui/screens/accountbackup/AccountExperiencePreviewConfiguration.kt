package com.example.ironpath.ui.screens.accountbackup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.example.ironpath.domain.account.AccountState

internal const val ACCOUNT_EXPERIENCE_PREVIEW_ENABLED = true

private const val ACCOUNT_BACKUP_ROUTE = "account_backup"
private const val ACCOUNT_EXPERIENCE_PREVIEW_ROUTE = "account_experience_preview"

internal val accountExperienceEntryContent =
    AccountExperienceEntryContent(
        privacyCopy =
            "Signing in identifies your account. Your training data stays local until you " +
                "choose a manual backup or restore.",
        signInLabel = "SIGN IN WITH GOOGLE",
        signInNotice = "Demo account. No Google or cloud connection.",
    )

internal val accountExperienceDrawerContent =
    AccountExperienceDrawerContent(
        contentDescription =
            "Local profile. Training data stays on this device until you manually back it up. " +
                "Open Account and Backup. Manual operations are unavailable in this build.",
        stateDescription = "Local only. No account connected. Manual operations unavailable.",
        title = "Stored on this device",
        actionLabel = "Back up your training data",
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
) {
    composable(ACCOUNT_BACKUP_ROUTE) {
        BackHandler(onBack = onCancel)
        AccountBackupScreen(
            state = state,
            onSignIn = onSignIn,
            onRetry = onRetry,
            onCancel = onCancel,
            onPreview = {
                navController.navigate(ACCOUNT_EXPERIENCE_PREVIEW_ROUTE) { launchSingleTop = true }
            },
            modifier = Modifier.padding(innerPadding),
        )
    }
    composable(ACCOUNT_EXPERIENCE_PREVIEW_ROUTE) {
        AccountBackupExperiencePreviewScreen(modifier = Modifier.padding(innerPadding))
    }
}

internal fun isAccountExperiencePreviewRoute(route: String?): Boolean =
    route == ACCOUNT_EXPERIENCE_PREVIEW_ROUTE || route == ACCOUNT_BACKUP_ROUTE

internal fun isAccountBackupRoute(route: String?): Boolean = route == ACCOUNT_BACKUP_ROUTE

internal fun accountExperiencePreviewTopBarTitle(route: String?): String? =
    when (route) {
        ACCOUNT_BACKUP_ROUTE -> "ACCOUNT & BACKUP"
        ACCOUNT_EXPERIENCE_PREVIEW_ROUTE -> "EXPERIENCE PREVIEW"
        else -> null
    }
