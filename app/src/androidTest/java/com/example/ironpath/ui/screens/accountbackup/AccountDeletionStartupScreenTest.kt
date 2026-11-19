package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.AccountDeletionStartupScreen
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccountDeletionStartupScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun coldStartDeletionRetryIsReachableAtTwoHundredPercentLandscape() {
        var retries = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(640.dp, 320.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    AccountDeletionStartupScreen(
                        title = "Finishing account deletion",
                        detail =
                            "IronPath will open after the saved deletion steps finish. " +
                                "Training data stays unavailable while cleanup is pending.",
                        onRetry = { retries++ },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Finishing account deletion").assertIsDisplayed()
        composeRule
            .onNodeWithText("Training data stays unavailable", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Finishing account deletion",
                )
            )
            .assertExists()
        val retry =
            composeRule
                .onNodeWithText("RETRY DELETION")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsEnabled()
                .assertHasClickAction()
        val node = retry.fetchSemanticsNode()
        val minimumTouchSize = with(node.layoutInfo.density) { 48.dp.toPx() }
        assertTrue(node.touchBoundsInRoot.height >= minimumTouchSize)
        assertTrue(node.touchBoundsInRoot.width >= minimumTouchSize)
        retry.performClick()
        assertEquals(1, retries)
        listOf("CANCEL", "SIGN OUT", "CONTINUE ON THIS DEVICE", "No workout plan yet").forEach {
            composeRule.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test
    fun startupVerificationWithoutRetryExposesStatusAndNoBypassAction() {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(640.dp, 320.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    AccountDeletionStartupScreen(
                        title = "Checking local profile",
                        detail = "Verifying the current training profile and onboarding status.",
                    )
                }
            }
        }

        composeRule.onNodeWithText("Checking local profile").assertIsDisplayed()
        composeRule
            .onNodeWithText("Verifying the current training profile and onboarding status.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodes(hasClickAction()).assertCountEquals(0)
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
    }
}
