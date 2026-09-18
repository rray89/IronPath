@file:Suppress("UNUSED_PARAMETER")

package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.PaddingValues
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

internal const val ACCOUNT_EXPERIENCE_PREVIEW_ENABLED = false

internal val accountExperienceEntryContent: AccountExperienceEntryContent? = null

internal val accountExperienceDrawerContent: AccountExperienceDrawerContent? = null

internal fun NavHostController.openAccountExperiencePreview() = Unit

internal fun openAccountExperiencePreview(onDestinationSelected: (String) -> Unit) = Unit

internal fun NavGraphBuilder.accountExperiencePreviewDestination(innerPadding: PaddingValues) = Unit

internal fun isAccountExperiencePreviewRoute(route: String?): Boolean = false

internal fun accountExperiencePreviewTopBarTitle(route: String?): String? = null
