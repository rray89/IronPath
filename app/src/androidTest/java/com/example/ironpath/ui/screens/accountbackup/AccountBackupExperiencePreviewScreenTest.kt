package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountBackupExperiencePreviewScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun overview_explainsManualOnlyBehaviorAndOffersBothReviewPaths() {
        setPreview()

        composeRule.onNodeWithText("MANUAL ONLY").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "Signing in identifies the account. Nothing uploads until you confirm a manual " +
                    "backup or sync."
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("BACK UP NOW").assertHasClickAction()
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").assertHasClickAction()
        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").assertHasClickAction()
    }

    @Test
    fun manualBackup_isAnExplicitFixtureOnlyAction() {
        setPreview()

        composeRule.onNodeWithText("BACK UP NOW").performClick()

        composeRule
            .onNodeWithText("Preview complete — no backup ran and no data changed")
            .assertIsDisplayed()
    }

    @Test
    fun manualSync_previewsSafeChangesAndRequiresOneConflictOutcome() {
        setPreview()

        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performClick()

        composeRule.onNodeWithText("4 changes can merge safely").assertIsDisplayed()
        composeRule.onNodeWithText("2 records need your choice").assertIsDisplayed()
        composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Merge and keep local conflict versions")
            .performScrollTo()
            .assertIsNotSelected()
        composeRule
            .onNodeWithText("Overwrite this device from cloud")
            .performScrollTo()
            .assertHasClickAction()
            .assertIsNotSelected()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        composeRule
            .onNodeWithText("Merge and keep local conflict versions")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsEnabled()
    }

    @Test
    fun restorePreview_showsCategorizedImpactAndRequiresLongPress() {
        setPreview()

        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").performScrollTo().performClick()

        listOf(
                "Pixel 8 · Aug 18, 2026 at 9:42 PM",
                "Added · 3",
                "Updated · 4",
                "Replaced · 2",
                "Whole backup only. Individual records cannot be edited here.",
                "One pre-restore local snapshot will be kept for one undo. The next successful " +
                    "restore replaces it.",
            )
            .forEach { copy ->
                composeRule.onNodeWithText(copy).performScrollTo().assertIsDisplayed()
            }

        val restore =
            composeRule
                .onNodeWithTag(TestTags.ACCOUNT_PREVIEW_LONG_PRESS_RESTORE)
                .performScrollTo()
                .assertHasClickAction()
                .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        restore.performClick()
        composeRule
            .onNodeWithText("Keep holding Restore to confirm the whole-backup replacement")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("Preview complete — no data changed").assertDoesNotExist()
        restore.performTouchInput { longClick() }

        composeRule
            .onNodeWithText("Preview complete — no data changed")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun restorePreview_compactLandscapeAtTwoHundredPercent_keepsConfirmationReachable() {
        setPreview(size = DpSize(640.dp, 320.dp))

        composeRule.onNodeWithText("PREVIEW WHOLE-BACKUP RESTORE").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(TestTags.ACCOUNT_PREVIEW_LONG_PRESS_RESTORE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun compactLandscape_manualSyncStartsAtHeadingAndKeepsDecisionReachable() {
        setPreview(size = DpSize(640.dp, 320.dp))

        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("MANUAL SYNC PREVIEW").assertIsDisplayed()
        composeRule
            .onNodeWithText("Merge and keep local conflict versions")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Overwrite this device from cloud")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("CONFIRM MANUAL SYNC")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    private fun setPreview(size: DpSize? = null) {
        composeRule.setContent {
            val content =
                @androidx.compose.runtime.Composable {
                    IronPathTheme {
                        Surface(Modifier.fillMaxSize()) { AccountBackupExperiencePreviewScreen() }
                    }
                }
            if (size == null) {
                content()
            } else {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.FontScale(2f) then
                        DeviceConfigurationOverride.ForcedSize(size)
                ) {
                    content()
                }
            }
        }
    }
}
