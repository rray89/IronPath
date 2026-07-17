package com.example.ironpath.ui.screens.history

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class WorkoutLogDetailScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun loading_rendersProgress() {
        setContent(WorkoutLogDetailUiState.Loading)
        composeRule
            .onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .assertIsDisplayed()
    }

    @Test
    fun notFound_rendersProductionCopyAndBackAction() {
        var backCount = 0
        setContent(WorkoutLogDetailUiState.NotFound, onBack = { backCount++ })
        composeRule.onNodeWithText("LOG NOT FOUND").assertIsDisplayed()
        composeRule.onNodeWithText("This workout log is no longer available.").assertIsDisplayed()
        composeRule.onNodeWithText("GO BACK").performClick()
        assertEquals(1, backCount)
    }

    @Test
    fun readySnapshot_ordersSets_andNeverOffersRecordMutationActions() {
        val second = loggedSet(id = "set-2", number = 2, reps = 8, weightKg = 62.5)
        val first = loggedSet(id = "set-1", number = 1, reps = 10, weightKg = 60.0)
        setContent(readyState(sets = listOf(second, first)))

        composeRule.onNodeWithText("Push A").assertIsDisplayed()
        composeRule.onNodeWithText("Bench Press").assertIsDisplayed()
        composeRule.onNodeWithText("10 reps · 60 kg").assertIsDisplayed()
        composeRule.onNodeWithText("8 reps · 62.5 kg").assertIsDisplayed()

        val firstY = composeRule.onNodeWithText("SET 1").fetchSemanticsNode().positionInRoot.y
        val secondY = composeRule.onNodeWithText("SET 2").fetchSemanticsNode().positionInRoot.y
        assertTrue(firstY < secondY)
        composeRule.onNodeWithText("SAVE RECORD").assertDoesNotExist()
        composeRule.onNodeWithText("SAVED").assertDoesNotExist()
    }

    @Test
    fun readySnapshot_backActionInvokesOnce() {
        var backCount = 0
        setContent(readyState(sets = listOf(loggedSet("set-1", 1, 10, 60.0)))) { backCount++ }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backCount)
    }

    @Test
    fun emptyExerciseSnapshot_rendersImmutableEmptyCopy() {
        setContent(readyState(exercises = emptyList()))
        composeRule
            .onNodeWithText("No exercise snapshot was saved for this log.")
            .assertIsDisplayed()
    }

    @Test
    fun emptySetSnapshot_rendersImmutableEmptyCopy() {
        setContent(readyState(sets = emptyList()))
        composeRule.onNodeWithText("No sets were logged for this exercise.").assertIsDisplayed()
        composeRule.onNodeWithText("SAVE RECORD").assertDoesNotExist()
    }

    private fun setContent(
        state: WorkoutLogDetailUiState,
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            IronPathTheme {
                WorkoutLogDetailContent(
                    uiState = state,
                    onBack = onBack,
                    zoneId = ZoneOffset.UTC,
                )
            }
        }
    }

    private fun readyState(
        exercises: List<LoggedExerciseDetail>? = null,
        sets: List<LoggedSet> = listOf(loggedSet("set-1", 1, 10, 60.0)),
    ): WorkoutLogDetailUiState.Ready {
        val resolvedExercises =
            exercises
                ?: listOf(
                    LoggedExerciseDetail(
                        exercise =
                            LoggedExercise(
                                id = "exercise-1",
                                workoutLogId = "log-1",
                                name = "Bench Press",
                                plannedSets = 3,
                                plannedReps = 10,
                                plannedWeightKg = 60.0,
                                orderIndex = 0,
                            ),
                        sets = sets,
                    ),
                )
        return WorkoutLogDetailUiState.Ready(
            detail =
                WorkoutLogDetail(
                    log =
                        WorkoutLog(
                            id = "log-1",
                            title = "Push A",
                            startedAt = 1_000L,
                            completedAt = 2_000L,
                            durationMinutes = 1,
                            exerciseCount = resolvedExercises.size,
                        ),
                    exercises = resolvedExercises,
                ),
        )
    }

    private fun loggedSet(
        id: String,
        number: Int,
        reps: Int?,
        weightKg: Double?,
    ) =
        LoggedSet(
            id = id,
            loggedExerciseId = "exercise-1",
            setNumber = number,
            reps = reps,
            weightKg = weightKg,
        )
}
