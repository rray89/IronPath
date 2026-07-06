package com.example.ironpath.ui.screens.history

import app.cash.turbine.test
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutLogDetailViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val log =
        WorkoutLog(
            id = "log1",
            title = "Push A",
            startedAt = 1_000L,
            completedAt = 4_600L,
            durationMinutes = 1,
            exerciseCount = 1,
        )
    private val detail =
        WorkoutLogDetail(
            log = log,
            exercises =
                listOf(
                    LoggedExerciseDetail(
                        exercise =
                            LoggedExercise(
                                id = "lex1",
                                workoutLogId = "log1",
                                name = "Bench Press",
                                plannedSets = 3,
                                plannedReps = 10,
                                plannedWeightKg = 60.0,
                                orderIndex = 0,
                            ),
                        sets =
                            listOf(
                                LoggedSet(
                                    id = "lset1",
                                    loggedExerciseId = "lex1",
                                    setNumber = 1,
                                    reps = 10,
                                    weightKg = 62.5,
                                    completedAt = 4_000L,
                                ),
                            ),
                    ),
                ),
        )

    @Test
    fun `uiState emits Ready when detail exists`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        coEvery { historyRepository.getLogDetail("log1") } returns detail

        val viewModel = WorkoutLogDetailViewModel("log1", historyRepository)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutLogDetailUiState.Ready) state = awaitItem()
            assertEquals(detail, state.detail)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits NotFound when detail is missing`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        coEvery { historyRepository.getLogDetail("missing") } returns null

        val viewModel = WorkoutLogDetailViewModel("missing", historyRepository)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutLogDetailUiState.NotFound) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
