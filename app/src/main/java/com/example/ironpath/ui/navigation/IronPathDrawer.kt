package com.example.ironpath.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.ironpath.ui.screens.accountbackup.ACCOUNT_EXPERIENCE_PREVIEW_ENABLED
import com.example.ironpath.ui.screens.accountbackup.accountExperienceDrawerContent
import com.example.ironpath.ui.screens.accountbackup.openAccountExperiencePreview
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow

@Composable
fun IronPathDrawer(
    onDestinationSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedRoute: String? = null,
    accountExperiencePreviewEnabled: Boolean = ACCOUNT_EXPERIENCE_PREVIEW_ENABLED,
) {
    ModalDrawerSheet(
        modifier = modifier,
        drawerShape = RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalProfileHeader(
                experiencePreviewEnabled = accountExperiencePreviewEnabled,
                onOpenExperiencePreview = { openAccountExperiencePreview(onDestinationSelected) },
            )
            Spacer(Modifier.height(12.dp))
            DrawerDestination(
                label = "Manual",
                route = Route.MANUAL,
                icon = Icons.AutoMirrored.Filled.MenuBook,
                selectedRoute = selectedRoute,
                onDestinationSelected = onDestinationSelected,
            )
            DrawerDestination(
                label = "AI & Privacy",
                route = Route.AI_PRIVACY,
                icon = Icons.Default.Security,
                selectedRoute = selectedRoute,
                onDestinationSelected = onDestinationSelected,
            )
            DrawerDestination(
                label = "About IronPath",
                route = Route.ABOUT,
                icon = Icons.Default.Info,
                selectedRoute = selectedRoute,
                onDestinationSelected = onDestinationSelected,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LocalProfileHeader(
    experiencePreviewEnabled: Boolean,
    onOpenExperiencePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewContent = accountExperienceDrawerContent.takeIf { experiencePreviewEnabled }
    val interactionModifier =
        if (previewContent != null) {
            Modifier.clickable(role = Role.Button, onClick = onOpenExperiencePreview)
        } else Modifier
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .then(interactionModifier)
                .semantics(mergeDescendants = true) {
                    if (previewContent != null) {
                        contentDescription = previewContent.contentDescription
                        stateDescription = previewContent.stateDescription
                    } else {
                        contentDescription =
                            "Local profile. Training data already saved on this device. " +
                                "IronPath cloud backup unavailable. Android device transfer available."
                        stateDescription =
                            "Saved locally. Cloud backup unavailable. Device transfer available."
                    }
                }
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "LOCAL PROFILE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = previewContent?.title ?: "Your training data is already saved on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text =
                previewContent?.actionLabel
                    ?: "IronPath cloud backup is not available in this version.",
            style = MaterialTheme.typography.bodySmall,
            color =
                if (previewContent != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@Composable
private fun DrawerDestination(
    label: String,
    route: String,
    icon: ImageVector,
    selectedRoute: String?,
    onDestinationSelected: (String) -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
            )
        },
        selected = selectedRoute == route,
        onClick = { onDestinationSelected(route) },
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
            )
        },
        shape = RoundedCornerShape(4.dp),
        colors =
            NavigationDrawerItemDefaults.colors(
                selectedContainerColor = SurfaceContainerHigh,
                selectedIconColor = MaterialTheme.colorScheme.primary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedContainerColor = MaterialTheme.colorScheme.surface,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
    )
}
