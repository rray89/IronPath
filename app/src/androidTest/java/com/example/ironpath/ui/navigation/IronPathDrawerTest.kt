package com.example.ironpath.ui.navigation

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
                "Your training data is already saved on this device.",
                "IronPath cloud backup is not available in this version. " +
                    "Android device-to-device transfer may copy it to a new phone during setup.",
                "Manual",
                "AI & Privacy",
                "About IronPath",
            )

        orderedLabels.forEach { label -> composeRule.onNodeWithText(label).assertIsDisplayed() }
        val verticalPositions =
            listOf(
                    composeRule.onNodeWithContentDescription(
                        "Local profile. Training data already saved on this device. " +
                            "IronPath cloud backup unavailable. Android device transfer available."
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
    fun drawer_exposesNonInteractiveAccountStateAndSelectedDestinationSemantics() {
        setDrawer(selectedRoute = Route.AI_PRIVACY)

        composeRule
            .onNodeWithContentDescription(
                "Local profile. Training data already saved on this device. " +
                    "IronPath cloud backup unavailable. Android device transfer available."
            )
            .assertIsDisplayed()
            .assertHasNoClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Saved locally. Cloud backup unavailable. Device transfer available.",
                )
            )
        composeRule.onNodeWithText("Manual").assertHasClickAction().assertIsNotSelected()
        composeRule.onNodeWithText("AI & Privacy").assertHasClickAction().assertIsSelected()
        composeRule.onNodeWithText("About IronPath").assertHasClickAction().assertIsNotSelected()
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
    ) {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    IronPathDrawer(
                        selectedRoute = selectedRoute,
                        onDestinationSelected = onDestinationSelected,
                    )
                }
            }
        }
    }
}
