package com.example.ironpath.ui.navigation

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IronPathDrawerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun drawer_showsLocalAccountTruthAndOrderedSecondaryDestinations() {
        setDrawer()

        val orderedLabels =
            listOf(
                "LOCAL PROFILE",
                "Stored on this device",
                "Back up your training data",
                "Manual",
                "AI & Privacy",
                "About IronPath",
            )

        orderedLabels.forEach { label -> composeRule.onNodeWithText(label).assertIsDisplayed() }
        val verticalPositions =
            listOf(
                    composeRule.onNodeWithContentDescription(
                        "Local profile. Training data stays on this device until you manually " +
                            "back it up. Open the Account and Backup experience preview."
                    ),
                    composeRule.onNodeWithText("Manual"),
                    composeRule.onNodeWithText("AI & Privacy"),
                    composeRule.onNodeWithText("About IronPath"),
                )
                .map { node -> node.fetchSemanticsNode().boundsInRoot.top }
        assertTrue(
            "Drawer content was not in the expected visual order: $verticalPositions",
            verticalPositions.zipWithNext().all { (first, second) -> first < second },
        )
    }

    @Test
    fun drawer_destinationClickEmitsItsExactRouteOnce() {
        val selectedRoutes = mutableListOf<String>()
        setDrawer(onDestinationSelected = selectedRoutes::add)

        composeRule.onNodeWithText("Manual").performClick()
        composeRule.onNodeWithText("AI & Privacy").performClick()
        composeRule.onNodeWithText("About IronPath").performClick()

        assertEquals(
            listOf(Route.MANUAL, Route.AI_PRIVACY, Route.ABOUT),
            selectedRoutes,
        )
    }

    @Test
    fun drawer_doesNotDuplicatePrimaryNavigationOrExposeInternalDestinations() {
        setDrawer()

        listOf("Home", "Plan", "Active", "History", "Settings", "DevTools").forEach { label ->
            composeRule.onNodeWithText(label).assertDoesNotExist()
        }
    }

    @Test
    fun drawer_accountPreviewEntryEmitsItsRouteAndExposesLocalState() {
        val selectedRoutes = mutableListOf<String>()
        setDrawer(
            selectedRoute = Route.AI_PRIVACY,
            onDestinationSelected = selectedRoutes::add,
        )

        composeRule
            .onNodeWithContentDescription(
                "Local profile. Training data stays on this device until you manually back it up. " +
                    "Open the Account and Backup experience preview."
            )
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Local only. No account connected. Manual backup available in preview.",
                )
            )
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        assertEquals(listOf("account_backup"), selectedRoutes)
        composeRule.onNodeWithText("Manual").assertHasClickAction().assertIsNotSelected()
        composeRule.onNodeWithText("AI & Privacy").assertHasClickAction().assertIsSelected()
        composeRule.onNodeWithText("About IronPath").assertHasClickAction().assertIsNotSelected()
    }

    @Test
    fun drawer_whenExperiencePreviewIsDisabled_keepsAccountStateNonInteractive() {
        setDrawer(accountExperiencePreviewEnabled = false)

        composeRule
            .onNodeWithContentDescription(
                "Local profile. Training data already saved on this device. " +
                    "IronPath cloud backup unavailable. Android device transfer available."
            )
            .assertIsDisplayed()
            .assertHasNoClickAction()
        composeRule.onNodeWithText("Back up your training data").assertDoesNotExist()
    }

    @Test
    fun drawer_compactPortraitAt200Percent_keepsEveryDestinationReachable() {
        val selectedRoutes = mutableListOf<String>()
        setAdaptiveDrawer(
            size = DpSize(320.dp, 640.dp),
            onDestinationSelected = selectedRoutes::add,
        )

        assertFinalDestinationReachable(selectedRoutes)
    }

    @Test
    fun drawer_compactLandscapeAt200Percent_keepsEveryDestinationReachable() {
        val selectedRoutes = mutableListOf<String>()
        setAdaptiveDrawer(
            size = DpSize(640.dp, 320.dp),
            onDestinationSelected = selectedRoutes::add,
        )

        assertFinalDestinationReachable(selectedRoutes)
    }

    private fun setAdaptiveDrawer(
        size: DpSize,
        onDestinationSelected: (String) -> Unit,
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(2f) then
                    DeviceConfigurationOverride.ForcedSize(size)
            ) {
                IronPathTheme {
                    Surface(modifier = Modifier) {
                        IronPathDrawer(onDestinationSelected = onDestinationSelected)
                    }
                }
            }
        }
    }

    private fun assertFinalDestinationReachable(selectedRoutes: List<String>) {
        composeRule.onNodeWithText("Manual").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("AI & Privacy").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("About IronPath")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf(Route.ABOUT), selectedRoutes)
    }

    private fun setDrawer(
        selectedRoute: String? = null,
        onDestinationSelected: (String) -> Unit = {},
        accountExperiencePreviewEnabled: Boolean = true,
    ) {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    IronPathDrawer(
                        selectedRoute = selectedRoute,
                        onDestinationSelected = onDestinationSelected,
                        accountExperiencePreviewEnabled = accountExperiencePreviewEnabled,
                    )
                }
            }
        }
    }
}
