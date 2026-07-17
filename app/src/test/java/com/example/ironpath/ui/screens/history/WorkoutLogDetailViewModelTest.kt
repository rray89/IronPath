package com.example.ironpath.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutLogDetailViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val zoneId = ZoneId.of("Asia/Tokyo")
    private val timeProvider = FakeTimeProvider(zoneId = zoneId)

    @Test
    fun `uiState emits Ready with the immutable detail snapshot`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        coEvery { historyRepository.getLogDetail("log-1") } returns detail

        val viewModel = viewModel("log-1", historyRepository)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutLogDetailUiState.Ready) state = awaitItem()
            assertEquals(detail, state.detail)
            assertEquals(zoneId, viewModel.zoneId)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) { historyRepository.getLogDetail("log-1") }
    }

    @Test
    fun `uiState emits NotFound when detail is missing`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        coEvery { historyRepository.getLogDetail("missing") } returns null

        val viewModel = viewModel("missing", historyRepository)

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutLogDetailUiState.NotFound) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun missingWorkoutLogRouteArgument_loadsNotFound() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        coEvery { historyRepository.getLogDetail("") } returns null
        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = SavedStateHandle(),
                historyRepository = historyRepository,
                timeProvider = timeProvider,
            )

        advanceUntilIdle()

        assertEquals(WorkoutLogDetailUiState.NotFound, viewModel.uiState.value)
    }

    private fun viewModel(
        logId: String,
        historyRepository: HistoryRepository,
    ) =
        WorkoutLogDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf(Route.WORKOUT_LOG_ID_ARG to logId)),
            historyRepository = historyRepository,
            timeProvider = timeProvider,
        )

    private val detail =
        WorkoutLogDetail(
            log =
                WorkoutLog(
                    id = "log-1",
                    title = "Push A",
                    startedAt = 1_000L,
                    completedAt = 2_000L,
                    durationMinutes = 1,
                    exerciseCount = 1,
                ),
            exercises =
                listOf(
                    LoggedExerciseDetail(
                        exercise =
                            LoggedExercise(
                                id = "exercise-1",
                                workoutLogId = "log-1",
                                name = "Bench Press",
                                plannedSets = 1,
                                plannedReps = 10,
                                plannedWeightKg = 60.0,
                                orderIndex = 0,
                            ),
                        sets =
                            listOf(
                                LoggedSet(
                                    id = "set-1",
                                    loggedExerciseId = "exercise-1",
                                    setNumber = 1,
                                    reps = 10,
                                    weightKg = 60.0,
                                ),
                            ),
                    ),
                ),
        )
}
