package com.example.ironpath.data.repository

import com.example.ironpath.domain.planner.PlanningHistoryProvider
import com.example.ironpath.domain.planner.RecentLoggedExerciseSnapshot
import com.example.ironpath.domain.planner.RecentRecordSnapshot
import com.example.ironpath.domain.planner.RecentTrainingSummary
import com.example.ironpath.domain.planner.RecentWorkoutSnapshot
import com.example.ironpath.domain.planner.TrainingHistorySummarizer
import com.example.ironpath.domain.time.TimeProvider
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class LocalPlanningHistoryProvider
@Inject
constructor(
    private val historyRepository: HistoryRepository,
    private val recordRepository: RecordRepository,
    private val summarizer: TrainingHistorySummarizer,
    private val timeProvider: TimeProvider,
) : PlanningHistoryProvider {
    override suspend fun loadRecent(today: LocalDate): RecentTrainingSummary {
        val firstIncludedDay = today.minusDays(TrainingHistorySummarizer.RECENT_WINDOW_DAYS - 1L)
        val firstIncludedInstant =
            firstIncludedDay.atStartOfDay(timeProvider.zoneId).toInstant().toEpochMilli()
        val firstExcludedInstant =
            today.plusDays(1).atStartOfDay(timeProvider.zoneId).toInstant().toEpochMilli()
        val workoutDetails =
            historyRepository
                .observeAllLogs()
                .first()
                .filter { it.completedAt in firstIncludedInstant until firstExcludedInstant }
                .mapNotNull { historyRepository.getLogDetail(it.id) }
        val recordSnapshots =
            recordRepository.observeAllRecords().first().mapNotNull { record ->
                val achievedOn =
                    runCatching { LocalDate.parse(record.achievedOn) }.getOrNull()
                        ?: return@mapNotNull null
                RecentRecordSnapshot(record.exerciseName, record.weightKg, achievedOn)
            }
        val workoutSnapshots =
            workoutDetails.map { detail ->
                RecentWorkoutSnapshot(
                    title = detail.log.title,
                    completedOn =
                        detail.log.completedAt
                            .let(java.time.Instant::ofEpochMilli)
                            .atZone(timeProvider.zoneId)
                            .toLocalDate(),
                    exercises =
                        detail.exercises.map { exerciseDetail ->
                            RecentLoggedExerciseSnapshot(
                                name = exerciseDetail.exercise.name,
                                completedSetWeightsKg =
                                    exerciseDetail.sets
                                        .filter { it.completedAt != null }
                                        .map { it.weightKg },
                            )
                        },
                )
            }

        return summarizer.summarize(today, workoutSnapshots, recordSnapshots)
    }
}
