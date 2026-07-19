package com.example.ironpath.data.repository

import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.domain.planner.DefaultExerciseCatalog
import com.example.ironpath.domain.planner.ExerciseCatalogIds
import com.example.ironpath.domain.planner.TrainingHistorySummarizer
import com.example.ironpath.testutil.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPlanningHistoryProviderTest {
    private val historyRepository = mockk<HistoryRepository>()
    private val recordRepository = mockk<RecordRepository>()
    private val timeProvider = FakeTimeProvider()
    private val today = LocalDate.parse("2026-07-19")

    @Test
    fun `loadRecent maps completed details and records into a bounded catalog summary`() = runTest {
        val completedAt =
            today.minusDays(2).atStartOfDay(timeProvider.zoneId).toInstant().toEpochMilli()
        val log =
            WorkoutLog(
                id = "log-1",
                title = "Push day",
                startedAt = completedAt - 3_600_000,
                completedAt = completedAt,
                durationMinutes = 60,
                exerciseCount = 1,
            )
        val exercise =
            LoggedExercise(
                id = "exercise-1",
                workoutLogId = log.id,
                name = "Barbell Bench Press",
                plannedSets = 2,
                plannedReps = 5,
                plannedWeightKg = 60.0,
                orderIndex = 0,
            )
        val record =
            PersonalRecord(
                id = "record-1",
                exerciseName = "Barbell Bench Press",
                normalizedExerciseName = "barbell bench press",
                weightKg = 70.0,
                achievedOn = today.minusDays(1).toString(),
                sourceType = RecordSource.Logged,
                createdAt = completedAt,
            )
        every { historyRepository.observeAllLogs() } returns flowOf(listOf(log))
        coEvery { historyRepository.getLogDetail(log.id) } returns
            WorkoutLogDetail(
                log,
                listOf(
                    LoggedExerciseDetail(
                        exercise,
                        listOf(
                            LoggedSet("set-1", exercise.id, 1, 5, 60.0, completedAt = completedAt),
                            LoggedSet("set-2", exercise.id, 2, 5, 65.0, completedAt = completedAt),
                        ),
                    )
                ),
            )
        every { recordRepository.observeAllRecords() } returns flowOf(listOf(record))
        val provider =
            LocalPlanningHistoryProvider(
                historyRepository,
                recordRepository,
                TrainingHistorySummarizer(DefaultExerciseCatalog()),
                timeProvider,
            )

        val summary = provider.loadRecent(today)

        assertEquals(listOf("Push day"), summary.workouts.map { it.title })
        assertEquals(listOf(70.0), summary.records.map { it.weightKg })
        assertEquals(
            ExerciseCatalogIds.BARBELL_BENCH_PRESS,
            summary.exerciseLoads.single().catalogId
        )
        assertEquals(65.0, summary.exerciseLoads.single().maxWeightKg, 0.0)
    }
}
