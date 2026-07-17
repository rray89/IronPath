package com.example.ironpath.ui.screens.plan

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.TrainingGoal
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val plan =
        WeeklyPlan(
            id = "plan-1",
            startDate = "2026-07-20",
            endDate = "2026-07-26",
            createdAt = 1L,
        )

    private val mondayWorkout =
        PlannedWorkout(
            id = "workout-monday",
            weeklyPlanId = plan.id,
            dayOfWeek = 1,
            scheduledDate = "2026-07-20",
            title = "Upper Body",
            status = WorkoutStatus.Upcoming,
        )

    private val mondayExercise =
        PlannedExercise(
            id = "exercise-bench",
            plannedWorkoutId = mondayWorkout.id,
            name = "Bench Press",
            sets = 3,
            reps = 10,
            weightKg = 20.0,
            orderIndex = 0,
        )

    private val generated =
        GeneratedPlan(
            plan = plan,
            workouts = listOf(mondayWorkout),
            exercises = listOf(mondayExercise),
        )

    private fun setPlanContent(
        uiState: PlanUiState,
        selectedGoal: TrainingGoal = TrainingGoal.Strength,
        selectedDays: Set<Int> = emptySet(),
        onGoalSelected: (TrainingGoal) -> Unit = {},
        onDayToggled: (Int) -> Unit = {},
        onGenerate: () -> Unit = {},
        onDeleteWorkout: (String) -> Unit = {},
        onBackToSetup: () -> Unit = {},
        onAccept: () -> Unit = {},
        onStartWorkout: () -> Unit = {},
        onOpenWorkoutPreview: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    PlanContent(
                        uiState = uiState,
                        selectedGoal = selectedGoal,
                        selectedDays = selectedDays,
                        onGoalSelected = onGoalSelected,
                        onDayToggled = onDayToggled,
                        onGenerate = onGenerate,
                        onDeleteWorkout = onDeleteWorkout,
                        onBackToSetup = onBackToSetup,
                        onAccept = onAccept,
                        onStartWorkout = onStartWorkout,
                        onOpenWorkoutPreview = onOpenWorkoutPreview,
                    )
                }
            }
        }
    }

    @Test
    fun loading_showsProgressState() {
        setPlanContent(PlanUiState.Loading)

        composeRule.onNodeWithTag(TestTags.PLAN_LOADING).assertIsDisplayed()
    }

    @Test
    fun setup_exposesSelectedGoalAndDays() {
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedGoal = TrainingGoal.Hypertrophy,
            selectedDays = setOf(1, 5),
        )

        composeRule
            .onNodeWithTag(TestTags.planGoal(TrainingGoal.Hypertrophy.name))
            .assertIsSelected()
        composeRule
            .onNodeWithTag(TestTags.planGoal(TrainingGoal.Strength.name))
            .assertIsNotSelected()
        composeRule.onNodeWithTag(TestTags.planDay(1)).assertIsSelected()
        composeRule.onNodeWithTag(TestTags.planDay(2)).assertIsNotSelected()
        composeRule.onNodeWithTag(TestTags.planDay(5)).assertIsSelected()
    }

    @Test
    fun setup_withoutDays_disablesGenerate() {
        var callbackCount = 0
        setPlanContent(
            uiState = PlanUiState.Setup,
            onGenerate = { callbackCount += 1 },
        )

        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).assertIsNotEnabled()

        assertEquals(0, callbackCount)
    }

    @Test
    fun setup_withDay_enablesGenerateAndForwardsSelections() {
        var selectedGoal: TrainingGoal? = null
        var selectedDay: Int? = null
        var generateCount = 0
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedDays = setOf(1),
            onGoalSelected = { selectedGoal = it },
            onDayToggled = { selectedDay = it },
            onGenerate = { generateCount += 1 },
        )

        composeRule.onNodeWithTag(TestTags.planGoal(TrainingGoal.Endurance.name)).performClick()
        composeRule.onNodeWithTag(TestTags.planDay(3)).performClick()
        composeRule
            .onNodeWithTag(TestTags.PLAN_GENERATE)
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()

        assertEquals(TrainingGoal.Endurance, selectedGoal)
        assertEquals(3, selectedDay)
        assertEquals(1, generateCount)
    }

    @Test
    fun review_isStaticAndOmitsForbiddenEditingControls() {
        setPlanContent(PlanUiState.Review(generated))

        composeRule.onNodeWithTag(TestTags.workout(mondayWorkout.id)).assertIsDisplayed()
        composeRule.onNodeWithText("Upper Body", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Bench Press").assertIsDisplayed()
        composeRule.onNodeWithText("3×10 · 20kg").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.planReviewDay(mondayWorkout.id)).assertHasNoClickAction()
        composeRule.onNodeWithTag(TestTags.planExercise(mondayExercise.id)).assertHasNoClickAction()
        composeRule.onNodeWithText("ADD EXERCISE").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Remove exercise").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Move up").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Move down").assertDoesNotExist()
    }

    @Test
    fun review_wholeWorkoutDeleteForwardsWorkoutId() {
        var deletedWorkoutId: String? = null
        setPlanContent(
            uiState = PlanUiState.Review(generated),
            onDeleteWorkout = { deletedWorkoutId = it },
        )

        composeRule.onNodeWithContentDescription("Remove workout").performClick()

        assertEquals(mondayWorkout.id, deletedWorkoutId)
    }

    @Test
    fun review_acceptAndRegenerateInvokeTheirCallbacks() {
        var acceptCount = 0
        var regenerateCount = 0
        setPlanContent(
            uiState = PlanUiState.Review(generated),
            onAccept = { acceptCount += 1 },
            onBackToSetup = { regenerateCount += 1 },
        )

        composeRule.onNodeWithText("ACCEPT PLAN").performScrollTo().performClick()
        composeRule.onNodeWithText("REGENERATE").performScrollTo().performClick()

        assertEquals(1, acceptCount)
        assertEquals(1, regenerateCount)
    }

    @Test
    fun review_withoutWorkouts_disablesAccept() {
        setPlanContent(
            PlanUiState.Review(generated.copy(workouts = emptyList(), exercises = emptyList()))
        )

        composeRule.onNodeWithText("ACCEPT PLAN").assertIsNotEnabled()
    }

    @Test
    fun acceptedWithActiveSession_showsResumeGuidanceWithoutStart() {
        setPlanContent(
            PlanUiState.Accepted(
                planned = 1,
                completed = 0,
                workouts = listOf(mondayWorkout),
                todayWorkout = mondayWorkout,
                nextWorkout = mondayWorkout,
                hasActiveSession = true,
            )
        )

        composeRule.onNodeWithText("SESSION IN PROGRESS").assertIsDisplayed()
        composeRule
            .onNodeWithText("Switch to the Active tab to continue your workout.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").assertDoesNotExist()
    }

    @Test
    fun acceptedRestDay_showsNextWorkoutAndCounts() {
        setPlanContent(
            PlanUiState.Accepted(
                planned = 3,
                completed = 1,
                workouts = listOf(mondayWorkout),
                todayWorkout = null,
                nextWorkout = mondayWorkout,
                hasActiveSession = false,
            )
        )

        composeRule.onNodeWithText("NO WORKOUT TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("Next workout: MON · Upper Body").assertIsDisplayed()
        composeRule.onNodeWithText("3 workouts planned · 1 completed").assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").assertDoesNotExist()
    }

    @Test
    fun acceptedWorkoutDay_startsAndOpensTheSelectedWorkout() {
        var startCount = 0
        var openedWorkoutId: String? = null
        setPlanContent(
            uiState =
                PlanUiState.Accepted(
                    planned = 1,
                    completed = 0,
                    workouts = listOf(mondayWorkout),
                    todayWorkout = mondayWorkout,
                    nextWorkout = mondayWorkout,
                    hasActiveSession = false,
                ),
            onStartWorkout = { startCount += 1 },
            onOpenWorkoutPreview = { openedWorkoutId = it },
        )

        composeRule.onNodeWithText("WORKOUT DAY TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").performClick()
        composeRule
            .onNodeWithTag(TestTags.workout(mondayWorkout.id))
            .performScrollTo()
            .performClick()

        assertEquals(1, startCount)
        assertEquals(mondayWorkout.id, openedWorkoutId)
    }
}
