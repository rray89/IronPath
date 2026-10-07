package com.example.ironpath.accessibility

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.data.local.entity.*
import com.example.ironpath.domain.planner.RuleExerciseForm
import com.example.ironpath.ui.screens.plan.RuleDayPickerContent
import com.example.ironpath.ui.screens.plan.RuleExerciseEditorContent
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RuleReviewAccessibilityTest {
    @get:Rule val composeRule = createComposeRule()
    private val workout = PlannedWorkout("w", "p", 1, "2026-12-28", "Upper Body")
    private val exercise =
        PlannedExercise("e", workout.id, "Long custom exercise name", 20, 100, 0.5, 0)

    @Test
    fun editorAt200PercentKeepsLabelsErrorsSuggestionsAndDecisionsReachable() {
        var saved: RuleExerciseForm? = null
        var cancels = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    RuleExerciseEditorContent(
                        exercise,
                        listOf("Recorded Press"),
                        { cancels++ },
                        { saved = it }
                    )
                }
            }
        }
        composeRule
            .onNodeWithTag("rule_name")
            .performScrollTo()
            .assertContentDescriptionContains("Exercise name")
            .performTextReplacement("Recorded")
        composeRule.onNodeWithText("Recorded Press").performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_sets").performScrollTo().performTextReplacement("21")
        composeRule.onNodeWithTag("rule_save").performScrollTo().assertIsDisplayed().performClick()
        assertNull(saved)
        composeRule.onNodeWithText("Enter 1–20 whole sets.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("rule_sets").performScrollTo().performTextReplacement("20")
        composeRule
            .onNodeWithTag("rule_reps")
            .performScrollTo()
            .assertContentDescriptionContains("Reps (1–100)")
        composeRule
            .onNodeWithTag("rule_weight")
            .performScrollTo()
            .assertContentDescriptionContains("Weight (kg)")
        composeRule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, cancels)
        composeRule.onNodeWithTag("rule_save").performScrollTo().performClick()
        assertEquals("Recorded Press", saved!!.validate().values!!.name)
        assertEquals(100, saved!!.validate().values!!.reps)
    }

    @Test
    fun dayPickerAt200PercentLandscapeKeepsSundayAndCancelReachable() {
        var selected = 0
        var cancels = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(640.dp, 320.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    RuleDayPickerContent(workout, listOf(workout), { selected = it }, { cancels++ })
                }
            }
        }
        composeRule.onNodeWithTag("rule_day_1").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("rule_day_7").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(7, selected)
        composeRule.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, cancels)
    }
}
