package com.example.ironpath.ui.screens.history

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutLogDetailViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private fun savedStateHandle(logId: String): SavedStateHandle =
        SavedStateHandle(mapOf(Route.WORKOUT_LOG_ID_ARG to logId))

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
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        coEvery { historyRepository.getLogDetail("log1") } returns detail

        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = savedStateHandle("log1"),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )

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
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        coEvery { historyRepository.getLogDetail("missing") } returns null

        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = savedStateHandle("missing"),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutLogDetailUiState.NotFound) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun missingWorkoutLogRouteArgument_loadsNotFound() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        coEvery { historyRepository.getLogDetail("") } returns null
        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = SavedStateHandle(),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )

        advanceUntilIdle()

        assertEquals(WorkoutLogDetailUiState.NotFound, viewModel.uiState.value)
    }

    @Test
    fun `saveSetAsRecord inserts logged source record for completed weighted set`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        val insertedRecord = slot<PersonalRecord>()
        val achievedOn = log.completedAt.expectedRecordDate()
        coEvery { historyRepository.getLogDetail("log1") } returns detail
        coEvery {
            recordRepository.isDuplicateExcluding("bench press", achievedOn, 62.5, "")
        } returns false
        coEvery { recordRepository.insertRecord(capture(insertedRecord)) } returns Unit
        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = savedStateHandle("log1"),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )
        advanceUntilIdle()

        viewModel.saveSetAsRecord(detail.exercises.first(), detail.exercises.first().sets.first())
        advanceUntilIdle()

        val record = insertedRecord.captured
        assertEquals("Bench Press", record.exerciseName)
        assertEquals("bench press", record.normalizedExerciseName)
        assertEquals(62.5, record.weightKg, 0.0)
        assertEquals(achievedOn, record.achievedOn)
        assertEquals(RecordSource.Logged, record.sourceType)
        assertEquals("log1", record.sourceWorkoutLogId)
        val state = viewModel.uiState.value as WorkoutLogDetailUiState.Ready
        assertTrue(state.savedSetIds.contains("lset1"))
        assertEquals("Record saved from this workout.", state.recordMessage)
    }

    @Test
    fun `uiState marks sets saved from persisted logged records`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        val achievedOn = log.completedAt.expectedRecordDate()
        coEvery { historyRepository.getLogDetail("log1") } returns detail
        coEvery { recordRepository.getLoggedRecordsForWorkoutLog("log1") } returns
            listOf(
                PersonalRecord(
                    exerciseName = "Bench Press",
                    normalizedExerciseName = "bench press",
                    weightKg = 62.5,
                    achievedOn = achievedOn,
                    sourceType = RecordSource.Logged,
                    sourceWorkoutLogId = "log1",
                ),
            )

        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = savedStateHandle("log1"),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )
        advanceUntilIdle()

        val state = viewModel.uiState.value as WorkoutLogDetailUiState.Ready
        assertTrue(state.savedSetIds.contains("lset1"))
    }

    @Test
    fun `saveSetAsRecord treats matching logged duplicate as already saved`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        val achievedOn = log.completedAt.expectedRecordDate()
        coEvery { historyRepository.getLogDetail("log1") } returns detail
        coEvery { recordRepository.getLoggedRecordsForWorkoutLog("log1") } returnsMany
            listOf(
                emptyList(),
                listOf(
                    PersonalRecord(
                        exerciseName = "Bench Press",
                        normalizedExerciseName = "bench press",
                        weightKg = 62.5,
                        achievedOn = achievedOn,
                        sourceType = RecordSource.Logged,
                        sourceWorkoutLogId = "log1",
                    ),
                ),
            )
        coEvery { recordRepository.isDuplicateExcluding(any(), any(), any(), any()) } returns true
        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = savedStateHandle("log1"),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )
        advanceUntilIdle()

        viewModel.saveSetAsRecord(detail.exercises.first(), detail.exercises.first().sets.first())
        advanceUntilIdle()

        coVerify(exactly = 0) { recordRepository.insertRecord(any()) }
        val state = viewModel.uiState.value as WorkoutLogDetailUiState.Ready
        assertTrue(state.savedSetIds.contains("lset1"))
        assertEquals("Record already saved from this workout.", state.recordMessage)
    }

    @Test
    fun `saveSetAsRecord treats insert constraint failure from same log as already saved`() =
        runTest {
            val historyRepository = mockk<HistoryRepository>()
            val recordRepository = mockk<RecordRepository>(relaxed = true)
            val achievedOn = log.completedAt.expectedRecordDate()
            val savedRecord =
                PersonalRecord(
                    exerciseName = "Bench Press",
                    normalizedExerciseName = "bench press",
                    weightKg = 62.5,
                    achievedOn = achievedOn,
                    sourceType = RecordSource.Logged,
                    sourceWorkoutLogId = "log1",
                )
            coEvery { historyRepository.getLogDetail("log1") } returns detail
            coEvery { recordRepository.getLoggedRecordsForWorkoutLog("log1") } returnsMany
                listOf(emptyList(), listOf(savedRecord))
            coEvery { recordRepository.isDuplicateExcluding(any(), any(), any(), any()) } returns
                false
            coEvery { recordRepository.insertRecord(any()) } throws
                SQLiteConstraintException("duplicate")
            val viewModel =
                WorkoutLogDetailViewModel(
                    savedStateHandle = savedStateHandle("log1"),
                    historyRepository = historyRepository,
                    recordRepository = recordRepository,
                )
            advanceUntilIdle()

            viewModel.saveSetAsRecord(
                detail.exercises.first(),
                detail.exercises.first().sets.first()
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value as WorkoutLogDetailUiState.Ready
            assertTrue(state.savedSetIds.contains("lset1"))
            assertEquals("Record already saved from this workout.", state.recordMessage)
        }

    @Test
    fun `saveSetAsRecord does not insert duplicate record`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        coEvery { historyRepository.getLogDetail("log1") } returns detail
        coEvery { recordRepository.isDuplicateExcluding(any(), any(), any(), any()) } returns true
        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = savedStateHandle("log1"),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )
        advanceUntilIdle()

        viewModel.saveSetAsRecord(detail.exercises.first(), detail.exercises.first().sets.first())
        advanceUntilIdle()

        coVerify(exactly = 0) { recordRepository.insertRecord(any()) }
        val state = viewModel.uiState.value as WorkoutLogDetailUiState.Ready
        assertFalse(state.savedSetIds.contains("lset1"))
        assertEquals(
            "A record with this exercise, date, and weight already exists.",
            state.recordMessage,
        )
    }

    @Test
    fun `saveSetAsRecord ignores incomplete sets`() = runTest {
        val historyRepository = mockk<HistoryRepository>()
        val recordRepository = mockk<RecordRepository>(relaxed = true)
        val incompleteSet = detail.exercises.first().sets.first().copy(weightKg = null)
        coEvery { historyRepository.getLogDetail("log1") } returns detail
        val viewModel =
            WorkoutLogDetailViewModel(
                savedStateHandle = savedStateHandle("log1"),
                historyRepository = historyRepository,
                recordRepository = recordRepository,
            )
        advanceUntilIdle()

        viewModel.saveSetAsRecord(detail.exercises.first(), incompleteSet)
        advanceUntilIdle()

        coVerify(exactly = 0) { recordRepository.insertRecord(any()) }
        val state = viewModel.uiState.value as WorkoutLogDetailUiState.Ready
        assertEquals("Only completed weighted sets can become records.", state.recordMessage)
    }

    private fun Long.expectedRecordDate(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}
