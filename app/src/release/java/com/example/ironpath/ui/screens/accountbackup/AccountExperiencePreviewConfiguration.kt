@file:Suppress("UNUSED_PARAMETER")

package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import com.example.ironpath.domain.account.AccountState

internal const val ANDROID_TRAINING_BACKUP_POLICY =
    "IronPath cloud backup and restore are not available in this version, and Android cloud backup is turned off. On Android 12 and higher, Android device-to-device transfer can copy your workout database when you set up a new phone."

internal const val ACCOUNT_EXPERIENCE_PREVIEW_ENABLED = false

internal val accountExperienceEntryContent: AccountExperienceEntryContent? = null

internal val accountExperienceDrawerContent: AccountExperienceDrawerContent? = null

internal fun NavHostController.openAccountExperiencePreview() = Unit

internal fun openAccountExperiencePreview(onDestinationSelected: (String) -> Unit) = Unit

internal fun NavGraphBuilder.accountExperiencePreviewDestination(
    innerPadding: PaddingValues,
    navController: NavHostController,
    state: AccountState,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    manual: ManualBackupUiState,
    manualActions: ManualBackupActions,
    accountSignInAvailable: Boolean = true,
) = Unit

internal fun isAccountExperiencePreviewRoute(route: String?): Boolean = false

internal fun isAccountBackupRoute(route: String?): Boolean = false

internal fun accountExperiencePreviewTopBarTitle(route: String?): String? = null
