package com.example.ironpath.ui.screens.plan

import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.*
import com.example.ironpath.domain.planner.*
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RulePlanReviewScreenTest {
    @get:Rule val composeRule = createComposeRule()
    private val week =
        WeeklyPlan("week", startDate = "2026-12-28", endDate = "2027-01-03", createdAt = 1)
    private val monday = PlannedWorkout("mon", week.id, 1, "2026-12-28", "Push A")
    private val wednesday = PlannedWorkout("wed", week.id, 3, "2026-12-30", "Pull A")
    private val first = PlannedExercise("first", monday.id, "Bench", 3, 10, 20.5, 0)
    private val second = PlannedExercise("second", monday.id, "Dips", 3, 8, 0.0, 1)
    private val last = PlannedExercise("last", wednesday.id, "Rows", 1, 1, 5.0, 0)
    private val initial =
        GeneratedPlan(week, listOf(monday, wednesday), listOf(first, second, last))
    private val editor = RulePlanReviewEditor(SequenceIdProvider("added"))
    private lateinit var current: GeneratedPlan

    private fun children() =
        current.exercises.filter { it.plannedWorkoutId == monday.id }.sortedBy { it.orderIndex }

    private fun render(isSaving: Boolean = false) {
        composeRule.setContent {
            var draft by remember { mutableStateOf(initial) }
            var undo by remember { mutableStateOf<RuleExerciseRemoval?>(null) }
            current = draft
            IronPathTheme {
                Surface {
                    RulePlanReviewScreen(
                        draft,
                        isSaving,
                        {},
                        {},
                        {},
                        onMoveWorkout = { id, day ->
                            draft = editor.moveWorkout(draft, id, day)
                            undo = null
                        },
                        onEditExercise = { id, form ->
                            draft = editor.editExercise(draft, id, form)
                            undo = null
                        },
                        onAddExercise = { id, form ->
                            draft = editor.addExercise(draft, id, form)
                            undo = null
                        },
                        onRemoveExercise = { id ->
                            editor.removeExercise(draft, id)?.let {
                                draft = it.after
                                undo = it
                            }
                        },
                        onMoveExercise = { workout, exercise, index ->
                            draft = editor.moveExercise(draft, workout, exercise, index)
                            undo = null
                        },
                        suggestions = listOf("Bench Press", "Recorded Press"),
                        undo = undo,
                        onUndo = {
                            undo?.let { draft = it.before }
                            undo = null
                        },
                        onUndoExpired = { if (undo === it) undo = null },
                    )
                }
            }
        }
    }

    @Test
    fun dayPickerDistinguishesEmptyAndOccupiedDaysAndMoveSwapPreserveIdentity() {
        render()
        composeRule.onNodeWithTag(TestTags.planReviewDay(monday.id)).performClick()
        composeRule.onNodeWithTag("rule_day_1").assertIsSelected()
        composeRule
            .onNodeWithTag("rule_day_3")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Swap with Pull A"
                )
            )
        composeRule
            .onNodeWithTag("rule_day_7")
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Empty day"))
            .performClick()
        composeRule.runOnIdle {
            assertEquals(
                monday.copy(dayOfWeek = 7, scheduledDate = "2027-01-03"),
                current.workouts.last()
            )
            assertEquals(initial.exercises, current.exercises)
        }
        composeRule
            .onNodeWithTag(TestTags.planReviewDay(monday.id))
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("rule_day_3").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(3, 7), current.workouts.map { it.dayOfWeek })
            assertEquals(monday.id, current.workouts.first().id)
            assertEquals(wednesday.id, current.workouts.last().id)
            assertEquals("2027-01-03", current.workouts.last().scheduledDate)
        }
    }

    @Test
    fun cancelDayAndExerciseEditorsLeaveDraftUnchanged() {
        render()
        composeRule.onNodeWithTag(TestTags.planReviewDay(monday.id)).performClick()
        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()
        composeRule.onNodeWithTag(TestTags.planExercise(first.id)).performClick()
        composeRule.onNodeWithTag("rule_name").performTextReplacement("Changed but cancelled")
        composeRule.onNodeWithTag("rule_sets").performScrollTo().performTextReplacement("20")
        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(initial, current) }
    }

    @Test
    fun invalidFieldsStayOpenThenLegacyBoundsSaveWithSameIdAndFractionalWeight() {
        render()
        composeRule.onNodeWithTag(TestTags.planExercise(first.id)).performClick()
        composeRule.onNodeWithTag("rule_name").performTextReplacement("")
        composeRule.onNodeWithTag("rule_sets").performScrollTo().performTextReplacement("0")
        composeRule.onNodeWithTag("rule_reps").performScrollTo().performTextReplacement("101")
        composeRule.onNodeWithTag("rule_weight").performScrollTo().performTextReplacement("NaN")
        composeRule.onNodeWithTag("rule_save").performScrollTo().performClick()
        composeRule
            .onNodeWithTag("rule_name")
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(SemanticsProperties.Error, "Enter an exercise name.")
            )
        composeRule.runOnIdle { assertEquals(initial, current) }
        composeRule.onNodeWithTag("rule_name").performTextReplacement(" Custom lift ")
        composeRule.onNodeWithTag("rule_sets").performScrollTo().performTextReplacement("20")
        composeRule.onNodeWithTag("rule_reps").performScrollTo().performTextReplacement("100")
        composeRule.onNodeWithTag("rule_weight").performScrollTo().performTextReplacement("0.5")
        composeRule.onNodeWithTag("rule_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_exercise_editor").assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(
                first.copy(name = "Custom lift", sets = 20, reps = 100, weightKg = 0.5),
                children().first()
            )
        }
    }

    @Test
    fun addAllowsDuplicateNameAndAccessibleReorderMovesOnlyWithinWorkout() {
        render()
        composeRule.onNodeWithTag("rule_add_${monday.id}").performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_name").performTextReplacement("Bench")
        composeRule.onNodeWithTag("rule_save").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(listOf("Bench", "Dips", "Bench"), children().map { it.name })
        }
        val node = composeRule.onNodeWithTag("rule_drag_first")
        node.performScrollTo().assertHasClickAction()
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Move down"), actions.map { it.label })
        composeRule.runOnIdle { assertTrue(actions.single().action()) }
        composeRule.runOnIdle {
            assertEquals(listOf(second.id, first.id, "added-1"), children().map { it.id })
            assertEquals(last, current.exercises.find { it.id == last.id })
        }
        composeRule.onNodeWithTag("rule_drag_first").performScrollTo().performClick()
        composeRule.onNodeWithText("Move up").performClick()
        composeRule.runOnIdle { assertEquals(first.id, children().first().id) }
    }

    @Test
    fun draggingHandleChangesOrderAndKeepsOtherWorkoutUntouched() {
        render()
        val handle = composeRule.onNodeWithTag("rule_drag_first").performScrollTo()
        val delta =
            composeRule
                .onNodeWithTag("rule_drag_second")
                .fetchSemanticsNode()
                .boundsInRoot
                .center
                .y - handle.fetchSemanticsNode().boundsInRoot.center.y
        handle.performTouchInput { swipe(center, Offset(center.x, center.y + delta), 500) }
        composeRule.runOnIdle {
            assertEquals(listOf(second.id, first.id), children().map { it.id })
            assertEquals(listOf(0, 1), children().map { it.orderIndex })
            assertEquals(last, current.exercises.find { it.id == last.id })
        }
    }

    @Test
    fun removingLastExerciseRemovesDayAndUndoRestoresItsIdentity() {
        render()
        composeRule
            .onNodeWithContentDescription("Remove Rows from Pull A")
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle { assertEquals(listOf(monday), current.workouts) }
        composeRule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(initial, current) }
        composeRule
            .onNodeWithTag(TestTags.workout(wednesday.id))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun savingDisablesAllEditingAndAcceptControls() {
        render(isSaving = true)
        composeRule.onNodeWithTag(TestTags.planReviewDay(monday.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag(TestTags.planExercise(first.id)).assertIsNotEnabled()
        composeRule.onNodeWithTag("rule_drag_first").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Remove Bench from Push A").assertIsNotEnabled()
        composeRule.onNodeWithTag("rule_add_${monday.id}").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("ACCEPT PLAN").performScrollTo().assertIsNotEnabled()
    }
}
