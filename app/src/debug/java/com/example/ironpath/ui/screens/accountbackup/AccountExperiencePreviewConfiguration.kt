package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable

internal const val ACCOUNT_EXPERIENCE_PREVIEW_ENABLED = true

private const val ACCOUNT_EXPERIENCE_PREVIEW_ROUTE = "account_backup"

internal val accountExperienceEntryContent =
    AccountExperienceEntryContent(
        privacyCopy =
            "Signing in identifies your account. Your training data stays local until you " +
                "choose a manual backup or restore.",
        signInLabel = "SIGN IN WITH GOOGLE",
    )

internal val accountExperienceDrawerContent =
    AccountExperienceDrawerContent(
        contentDescription =
            "Local profile. Training data stays on this device until you manually back it up. " +
                "Open the Account and Backup experience preview.",
        stateDescription = "Local only. No account connected. Manual backup available in preview.",
        title = "Stored on this device",
        actionLabel = "Back up your training data",
    )

internal fun NavHostController.openAccountExperiencePreview() {
    navigate(ACCOUNT_EXPERIENCE_PREVIEW_ROUTE)
}

internal fun openAccountExperiencePreview(onDestinationSelected: (String) -> Unit) {
    onDestinationSelected(ACCOUNT_EXPERIENCE_PREVIEW_ROUTE)
}

internal fun NavGraphBuilder.accountExperiencePreviewDestination(innerPadding: PaddingValues) {
    composable(ACCOUNT_EXPERIENCE_PREVIEW_ROUTE) {
        AccountBackupExperiencePreviewScreen(modifier = Modifier.padding(innerPadding))
    }
}

internal fun isAccountExperiencePreviewRoute(route: String?): Boolean =
    route == ACCOUNT_EXPERIENCE_PREVIEW_ROUTE

internal fun accountExperiencePreviewTopBarTitle(route: String?): String? =
    if (isAccountExperiencePreviewRoute(route)) "ACCOUNT & BACKUP" else null
