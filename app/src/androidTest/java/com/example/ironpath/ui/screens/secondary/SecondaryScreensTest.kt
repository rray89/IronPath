package com.example.ironpath.ui.screens.secondary

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.ui.screens.about.AboutScreen
import com.example.ironpath.ui.screens.aiprivacy.AiPrivacyContent
import com.example.ironpath.ui.screens.aiprivacy.AiPrivacyUiState
import com.example.ironpath.ui.screens.manual.ManualScreen
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecondaryScreensTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun manual_exposesEachRequiredOfflineTopic() {
        setThemedContent { ManualScreen() }

        listOf(
                "Getting started",
                "Planning a week",
                "Reviewing and accepting a plan",
                "Starting and completing a workout",
                "History and personal records",
                "AI planning and validation",
                "On-device availability and rule-based fallback",
                "Backup, restore, and local-only behavior",
            )
            .forEach { topic ->
                composeRule
                    .onNodeWithText(topic)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
            }
    }

    @Test
    fun manual_selectingTopic_expandsItsGuidanceAndCollapsesPreviousGuidance() {
        setThemedContent { ManualScreen() }

        composeRule
            .onNodeWithText("Getting started")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Collapsed",
                )
            )
            .performClick()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Expanded",
                )
            )
        composeRule
            .onNodeWithText("Create a plan when you are ready. IronPath works without an account.")
            .assertIsDisplayed()

        composeRule.onNodeWithText("Planning a week").performClick()

        composeRule
            .onNodeWithText(
                "Choose a goal, training days, experience, equipment, and any preferences."
            )
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Create a plan when you are ready. IronPath works without an account.")
            .assertDoesNotExist()
    }

    @Test
    fun manual_eachTopicRevealsItsOfflineGuidance() {
        setThemedContent { ManualScreen() }

        listOf(
                "Getting started" to
                    "Create a plan when you are ready. IronPath works without an account.",
                "Planning a week" to
                    "Choose a goal, training days, experience, equipment, and any preferences.",
                "Reviewing and accepting a plan" to
                    "Review each workout before accepting it. Accepted plans are saved on this device.",
                "Starting and completing a workout" to
                    "Start a planned workout, log sets, and complete it when finished.",
                "History and personal records" to
                    "Completed workouts appear in History. Personal records are added separately.",
                "AI planning and validation" to
                    "AI suggestions are validated before they can become a plan.",
                "On-device availability and rule-based fallback" to
                    "On-device planning is used when available. Rule-based planning remains available offline.",
                "Backup, restore, and local-only behavior" to
                    "Your durable workout data is saved locally in Room. IronPath cloud backup " +
                        "and restore are not available in this version, and Android cloud backup " +
                        "is turned off. On Android 12 and higher, Android device-to-device " +
                        "transfer can copy your workout database when you set up a new phone.",
            )
            .forEach { (topic, guidance) ->
                composeRule.onNodeWithText(topic).performScrollTo().performClick()
                composeRule.onNodeWithText(guidance).performScrollTo().assertIsDisplayed()
            }
    }

    @Test
    fun aiPrivacy_describesLocalPersistenceAiBoundariesAndUnavailableCloudBackup() {
        setThemedContent {
            AiPrivacyContent(uiState = AiPrivacyUiState(availability = "Available on this device"))
        }

        listOf(
                "Room saves durable workout data locally on this device.",
                "Accepted structured plans persist locally. Unaccepted AI drafts do not persist.",
                "Available on this device",
                "On-device planning is tried first. Rule-based planning remains available offline.",
                "Release planning history stays local on this device.",
                "Debug Remote AI can send summarized planning context only when explicitly enabled.",
                "IronPath cloud backup and restore are not available in this version, and " +
                    "Android cloud backup is turned off. On Android 12 and higher, Android " +
                    "device-to-device transfer can copy your workout database when you set up a " +
                    "new phone.",
            )
            .forEach { disclosure ->
                composeRule.onNodeWithText(disclosure).performScrollTo().assertIsDisplayed()
            }
    }

    @Test
    fun about_explainsTheAppIsLocalFirstAndPortfolioScoped() {
        setThemedContent { AboutScreen() }

        composeRule.onNodeWithText("ABOUT IRONPATH").assertIsDisplayed()
        composeRule
            .onNodeWithText("Local-first workout planning for a private portfolio project.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("IronPath is fully usable without an account.")
            .assertIsDisplayed()
    }

    @Test
    fun manual_compactTwoHundredPercentFontScale_keepsFinalTopicReachable() {
        setAdaptiveContent(DpSize(320.dp, 640.dp)) { ManualScreen() }

        composeRule
            .onNodeWithText("Backup, restore, and local-only behavior")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun manual_compactLandscapeAtTwoHundredPercent_keepsFinalGuidanceReachable() {
        setAdaptiveContent(DpSize(640.dp, 320.dp)) { ManualScreen() }

        composeRule
            .onNodeWithText("Backup, restore, and local-only behavior")
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithText(
                "Your durable workout data is saved locally in Room. IronPath cloud backup " +
                    "and restore are not available in this version, and Android cloud backup " +
                    "is turned off. On Android 12 and higher, Android device-to-device transfer " +
                    "can copy your workout database when you set up a new phone."
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun aiPrivacy_compactLandscapeAtTwoHundredPercent_keepsTransferDisclosureReachable() {
        setAdaptiveContent(DpSize(640.dp, 320.dp)) {
            AiPrivacyContent(uiState = AiPrivacyUiState(availability = "Available on this device"))
        }

        composeRule.onNodeWithText("Backup and transfer").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "IronPath cloud backup and restore are not available in this version, and " +
                    "Android cloud backup is turned off. On Android 12 and higher, Android " +
                    "device-to-device transfer can copy your workout database when you set up a " +
                    "new phone."
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun about_compactLandscapeAtTwoHundredPercent_keepsOfflineDisclosureReachable() {
        setAdaptiveContent(DpSize(640.dp, 320.dp)) { AboutScreen() }

        composeRule
            .onNodeWithText("Workout planning and tracking remain available offline.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setAdaptiveContent(
        size: DpSize,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(2f) then
                    DeviceConfigurationOverride.ForcedSize(size)
            ) {
                IronPathTheme { Surface(Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun setThemedContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            IronPathTheme { Surface(modifier = Modifier.fillMaxSize()) { content() } }
        }
    }
}
