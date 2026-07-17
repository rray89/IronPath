package com.example.ironpath.ui.screens.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AddRecordScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val today = LocalDate.parse("2026-07-16")

    @Test
    fun emptyAndInvalidFields_showEveryRelevantValidatorError() {
        setAddRecordContent()

        composeRule.onNodeWithText("SAVE").performClick()

        composeRule.onNodeWithText("Exercise name is required").assertIsDisplayed()
        composeRule.onNodeWithText("Weight must be a positive number").assertIsDisplayed()

        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("Squat")
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).performTextReplacement("100")
        composeRule.onNodeWithTag(TestTags.RECORD_DATE).performTextReplacement("not-a-date")
        composeRule.onNodeWithText("SAVE").performClick()

        composeRule.onNodeWithText("Invalid date format (use YYYY-MM-DD)").assertIsDisplayed()
    }

    @Test
    fun futureDate_showsDateSpecificError() {
        setAddRecordContent()
        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("Squat")
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).performTextReplacement("100")
        composeRule.onNodeWithTag(TestTags.RECORD_DATE).performTextReplacement("2026-07-17")

        composeRule.onNodeWithText("SAVE").performClick()

        composeRule.onNodeWithText("Date cannot be in the future").assertIsDisplayed()
    }

    @Test
    fun validInput_emitsTrimmedNormalizedDraft_andMapsOptionalNote() {
        var saved: ValidatedRecordDraft? = null
        setAddRecordContent(onSave = { saved = it })
        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("  Bench Press  ")
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).performTextReplacement("62.5")
        composeRule.onNodeWithTag(TestTags.RECORD_NOTE).performTextReplacement("Felt strong")

        composeRule.onNodeWithText("SAVE").performClick()

        composeRule.runOnIdle {
            assertEquals("Bench Press", saved?.exerciseName)
            assertEquals("bench press", saved?.normalizedExerciseName)
            assertEquals(62.5, saved?.weightKg ?: 0.0, 0.0)
            assertEquals("2026-07-16", saved?.achievedOn)
            assertEquals("Felt strong", saved?.note)
        }
    }

    @Test
    fun blankOptionalNote_mapsToNull_andCancelInvokesOnce() {
        var saved: ValidatedRecordDraft? = null
        var cancelCount = 0
        setAddRecordContent(onSave = { saved = it }, onCancel = { cancelCount++ })
        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("Deadlift")
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).performTextReplacement("180")

        composeRule.onNodeWithText("SAVE").performClick()
        composeRule.onNodeWithText("CANCEL").performClick()

        composeRule.runOnIdle {
            assertNull(saved?.note)
            assertEquals(1, cancelCount)
        }
    }

    @Test
    fun suggestionsAreVisibleFilteredAndSelectable() {
        setAddRecordContent(suggestions = listOf("Bench Press", "Squat", "Deadlift"))

        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("press")

        composeRule.onNodeWithText("Bench Press").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Squat").assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.RECORD_NAME).assertTextContains("Bench Press")
    }

    @Test
    fun duplicateError_isShownWithoutClearingOrClosingTheForm() {
        var consumed = false
        setAddRecordContent(
            externalError = "A record with this exercise, date, and weight already exists.",
            onExternalErrorConsumed = { consumed = true },
        )
        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("Bench Press")

        composeRule
            .onNodeWithText("A record with this exercise, date, and weight already exists.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("ADD RECORD").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.RECORD_NAME).assertTextContains("Bench Press")
        composeRule.runOnIdle { assertTrue(consumed) }
    }

    @Test
    fun formValuesSurviveSavedInstanceStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            IronPathTheme {
                AddRecordScreen(
                    suggestions = emptyList(),
                    today = today,
                    onSave = {},
                    onCancel = {},
                )
            }
        }
        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("Bench Press")
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).performTextReplacement("62.5")
        composeRule.onNodeWithTag(TestTags.RECORD_DATE).performTextReplacement("2026-07-15")
        composeRule.onNodeWithTag(TestTags.RECORD_NOTE).performTextReplacement("Strong day")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(TestTags.RECORD_NAME).assertTextContains("Bench Press")
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).assertTextContains("62.5")
        composeRule.onNodeWithTag(TestTags.RECORD_DATE).assertTextContains("2026-07-15")
        composeRule.onNodeWithTag(TestTags.RECORD_NOTE).assertTextContains("Strong day")
    }

    private fun setAddRecordContent(
        suggestions: List<String> = emptyList(),
        onSave: (ValidatedRecordDraft) -> Unit = {},
        onCancel: () -> Unit = {},
        externalError: String? = null,
        onExternalErrorConsumed: () -> Unit = {},
    ) {
        composeRule.setContent {
            IronPathTheme {
                AddRecordScreen(
                    suggestions = suggestions,
                    today = today,
                    onSave = onSave,
                    onCancel = onCancel,
                    externalError = externalError,
                    onExternalErrorConsumed = onExternalErrorConsumed,
                )
            }
        }
    }
}
