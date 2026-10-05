package com.example.ironpath.ui.screens.workoutpreview

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.ui.screens.WorkoutStartUiState
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutPreviewScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val workout =
        PlannedWorkout(
            id = "workout-a",
            weeklyPlanId = "plan-a",
            dayOfWeek = 1,
            scheduledDate = "2026-07-13",
            title = "Strength A",
        )

    private val squat =
        PlannedExercise(
            id = "exercise-squat",
            plannedWorkoutId = workout.id,
            name = "Squat",
            sets = 3,
            reps = 5,
            weightKg = 100.0,
            orderIndex = 0,
        )

    @Test
    fun loading_showsProgressState() {
        setContent(WorkoutPreviewUiState.Loading)

        composeRule.onNodeWithTag(TestTags.WORKOUT_PREVIEW_LOADING).assertIsDisplayed()
    }

    @Test
    fun notFound_showsBackAction() {
        var backCalls = 0
        setContent(WorkoutPreviewUiState.NotFound, onBack = { backCalls++ })

        composeRule.onNodeWithText("WORKOUT NOT FOUND").assertIsDisplayed()
        composeRule.onNodeWithText("GO BACK").performClick()

        composeRule.runOnIdle { assertEquals(1, backCalls) }
    }

    @Test
    fun ready_ordersExercises_andInvokesStartExactlyOnceWithoutDuplicateBackAction() {
        val bench =
            squat.copy(
                id = "exercise-bench",
                name = "Bench Press",
                weightKg = 0.0,
                orderIndex = 1,
            )
        var startCalls = 0
        setContent(
            WorkoutPreviewUiState.Ready(
                workout = workout,
                exercises = listOf(squat, bench),
                canStart = true,
                hasActiveSession = false,
            ),
            onStart = { startCalls++ },
        )

        composeRule.onNodeWithTag(TestTags.planExercise(squat.id)).assertIsDisplayed()
        composeRule.onNodeWithText("3 sets · 5 reps · 100 kg").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.planExercise(bench.id)).assertIsDisplayed()
        composeRule.onNodeWithText("3 sets · 5 reps · bodyweight").assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").assertIsEnabled().performClick()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()

        composeRule.runOnIdle { assertEquals(1, startCalls) }
    }

    @Test
    fun futureOrCompletedWorkout_disablesStart() {
        setContent(
            WorkoutPreviewUiState.Ready(
                workout = workout.copy(status = WorkoutStatus.Completed),
                exercises = listOf(squat),
                canStart = false,
                hasActiveSession = false,
            )
        )

        composeRule.onNodeWithText("START WORKOUT").assertIsNotEnabled()
    }

    @Test
    fun activeSession_offersContinuation_andExplainsPreservedProgress() {
        setContent(
            WorkoutPreviewUiState.Ready(
                workout = workout,
                exercises = listOf(squat),
                canStart = false,
                hasActiveSession = true,
            )
        )

        composeRule.onNodeWithText("CONTINUE ACTIVE WORKOUT").assertIsEnabled()
        composeRule.onNodeWithText("START WORKOUT").assertDoesNotExist()
        composeRule
            .onNodeWithText(
                "Your active workout was kept. Continue it before starting another workout."
            )
            .assertIsDisplayed()
    }

    @Test
    fun readyWithNoExercises_showsEmptySnapshotCopy() {
        setContent(
            WorkoutPreviewUiState.Ready(
                workout = workout,
                exercises = emptyList(),
                canStart = true,
                hasActiveSession = false,
            )
        )

        composeRule.onNodeWithText("0 EXERCISES").assertIsDisplayed()
        composeRule.onNodeWithText("No exercises are attached to this workout.").assertIsDisplayed()
    }

    @Test
    fun busyStateDisablesStart_andFailureShowsRetryAction() {
        val state = androidx.compose.runtime.mutableStateOf(WorkoutStartUiState(isStarting = true))
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    WorkoutPreviewContent(
                        uiState = WorkoutPreviewUiState.Ready(workout, listOf(squat), true, false),
                        startState = state.value,
                        onBack = {},
                        onStart = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("STARTING WORKOUT…").assertIsNotEnabled()
        composeRule.runOnIdle {
            state.value = WorkoutStartUiState(error = "Could not start. Try again.")
        }
        composeRule.onNodeWithText("Could not start. Try again.").assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").assertIsEnabled()
    }

    private fun setContent(
        state: WorkoutPreviewUiState,
        startState: WorkoutStartUiState = WorkoutStartUiState(),
        onBack: () -> Unit = {},
        onStart: () -> Unit = {},
    ) {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    WorkoutPreviewContent(
                        uiState = state,
                        startState = startState,
                        onBack = onBack,
                        onStart = onStart,
                    )
                }
            }
        }
    }
}
