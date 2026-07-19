package com.example.ironpath.domain.planner

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

data class RecentWorkoutSnapshot(
    val title: String,
    val completedOn: LocalDate,
    val exercises: List<RecentLoggedExerciseSnapshot>,
)

data class RecentLoggedExerciseSnapshot(
    val name: String,
    val completedSetWeightsKg: List<Double?>,
)

data class RecentRecordSnapshot(
    val exerciseName: String,
    val weightKg: Double,
    val achievedOn: LocalDate,
)

data class RecentWorkoutSummary(
    val title: String,
    val completedOn: LocalDate,
    val exerciseCount: Int,
)

data class RecentRecordSummary(
    val exerciseName: String,
    val weightKg: Double,
    val achievedOn: LocalDate,
)

data class RecentTrainingSummary(
    val workouts: List<RecentWorkoutSummary>,
    val records: List<RecentRecordSummary>,
    val exerciseLoads: List<RecentExerciseLoad>,
    val unresolvedExerciseNames: Set<String>,
) {
    companion object {
        val EMPTY = RecentTrainingSummary(emptyList(), emptyList(), emptyList(), emptySet())
    }
}

interface PlanningHistoryProvider {
    suspend fun loadRecent(today: LocalDate): RecentTrainingSummary
}

@Singleton
class TrainingHistorySummarizer @Inject constructor(private val exerciseCatalog: ExerciseCatalog) {
    fun summarize(
        today: LocalDate,
        workouts: List<RecentWorkoutSnapshot>,
        records: List<RecentRecordSnapshot>,
    ): RecentTrainingSummary {
        val firstIncludedDay = today.minusDays(RECENT_WINDOW_DAYS - 1L)
        val recentWorkouts =
            workouts
                .filter { it.completedOn in firstIncludedDay..today }
                .sortedByDescending(RecentWorkoutSnapshot::completedOn)
        val recentRecords =
            records
                .filter { it.achievedOn in firstIncludedDay..today }
                .sortedByDescending(RecentRecordSnapshot::achievedOn)
        val unresolvedNames = linkedSetOf<String>()
        val maximumLoads = linkedMapOf<ExerciseCatalogId, Double>()

        recentWorkouts.flatMap(RecentWorkoutSnapshot::exercises).forEach { exercise ->
            val entry = exerciseCatalog.findByNormalizedName(exercise.name)
            if (entry == null) {
                unresolvedNames += exercise.name
            } else {
                exercise.completedSetWeightsKg.filterNotNull().maxOrNull()?.let { maximum ->
                    maximumLoads[entry.id] = maxOf(maximumLoads[entry.id] ?: 0.0, maximum)
                }
            }
        }
        recentRecords.forEach { record ->
            if (exerciseCatalog.findByNormalizedName(record.exerciseName) == null) {
                unresolvedNames += record.exerciseName
            }
        }

        return RecentTrainingSummary(
            workouts =
                recentWorkouts.take(MAX_RECENT_WORKOUTS).map { workout ->
                    RecentWorkoutSummary(
                        title = workout.title.take(MAX_SUMMARY_TEXT_LENGTH),
                        completedOn = workout.completedOn,
                        exerciseCount = workout.exercises.size,
                    )
                },
            records =
                recentRecords.take(MAX_RECENT_RECORDS).map { record ->
                    RecentRecordSummary(
                        exerciseName = record.exerciseName.take(MAX_SUMMARY_TEXT_LENGTH),
                        weightKg = record.weightKg,
                        achievedOn = record.achievedOn,
                    )
                },
            exerciseLoads =
                maximumLoads.map { (catalogId, maxWeightKg) ->
                    RecentExerciseLoad(catalogId, maxWeightKg)
                },
            unresolvedExerciseNames = unresolvedNames,
        )
    }

    companion object {
        const val RECENT_WINDOW_DAYS = 28
        private const val MAX_RECENT_WORKOUTS = 8
        private const val MAX_RECENT_RECORDS = 12
        private const val MAX_SUMMARY_TEXT_LENGTH = 80
    }
}
