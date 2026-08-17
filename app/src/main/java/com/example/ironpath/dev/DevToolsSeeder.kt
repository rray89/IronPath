package com.example.ironpath.dev

import androidx.room.withTransaction
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.onboarding.OnboardingRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevToolsSeeder
@Inject
constructor(
    private val database: IronPathDatabase,
    private val onboardingRepository: OnboardingRepository,
    private val backupStore: RoomBackupStore,
    private val planRepository: PlanRepository,
    private val recordRepository: RecordRepository,
    private val timeProvider: TimeProvider,
    private val idProvider: IdProvider,
) {

    /** Seed a 3-day Strength plan where today is one of the workout days. */
    suspend fun seedPlanForToday() {
        val today = timeProvider.today()
        val todayDow = today.dayOfWeek.value
        val existing = planRepository.getActivePlan()
        if (existing != null) {
            val workouts = planRepository.getWorkoutsForPlan(existing.id)
            if (workouts.any { it.dayOfWeek == todayDow }) {
                throw IllegalStateException("Today's workout already exists")
            }
        }
        seedPlanWithAnchorDay(todayDow, today)
    }

    /** Seed a 3-day Strength plan where tomorrow is one of the workout days. */
    suspend fun seedPlanForTomorrow() {
        val tomorrow = timeProvider.today().plusDays(1)
        val tomorrowDow = tomorrow.dayOfWeek.value
        val existing = planRepository.getActivePlan()
        if (existing != null) {
            val workouts = planRepository.getWorkoutsForPlan(existing.id)
            if (workouts.any { it.dayOfWeek == tomorrowDow }) {
                throw IllegalStateException("Tomorrow's workout already exists")
            }
        }
        seedPlanWithAnchorDay(tomorrowDow, tomorrow)
    }

    /** Insert 5 workout log entries spread over the past 2 weeks. */
    suspend fun seedHistoryLogs() {
        val now = timeProvider.epochMillis()
        val dayMs = 24 * 60 * 60 * 1000L
        val entries =
            listOf(
                Triple("Push A", 3, 45),
                Triple("Pull A", 3, 38),
                Triple("Legs", 3, 52),
                Triple("Push B", 3, 40),
                Triple("Back/Bis", 3, 35),
            )
        val historyDao = database.historyDao()
        database.withTransaction {
            if (historyDao.countLogsWithSourcePlannedWorkoutId(DEV_HISTORY_SOURCE_ID) > 0) {
                throw IllegalStateException("History logs already seeded")
            }
            entries.forEachIndexed { i, (title, exerciseCount, durationMinutes) ->
                val completedAt = now - (i * 2 + 1) * dayMs
                val startedAt = completedAt - durationMinutes * 60_000L
                val log =
                    WorkoutLog(
                        id = idProvider.newId(),
                        title = title,
                        sourcePlannedWorkoutId = DEV_HISTORY_SOURCE_ID,
                        startedAt = startedAt,
                        completedAt = completedAt,
                        durationMinutes = durationMinutes,
                        exerciseCount = exerciseCount,
                    )
                val exercises = sampleLoggedExercises(log.id, title, exerciseCount)
                val sets = sampleLoggedSets(exercises, completedAt)
                historyDao.insertLog(log)
                historyDao.insertLoggedExercises(exercises)
                historyDao.insertLoggedSets(sets)
            }
            backupStore.markIncludedDataChanged()
        }
    }

    /** Insert 5 personal records for common exercises. */
    suspend fun seedRecords() {
        val today = timeProvider.today()
        val createdAt = timeProvider.epochMillis()
        val entries =
            listOf(
                Triple("Barbell Bench Press", 80.0, today.minusDays(1).toString()),
                Triple("Barbell Squats", 100.0, today.minusDays(3).toString()),
                Triple("Deadlift", 120.0, today.minusDays(7).toString()),
                Triple("Overhead Press", 50.0, today.minusDays(10).toString()),
                Triple("Barbell Rows", 70.0, today.minusDays(14).toString()),
            )
        try {
            database.withTransaction {
                entries.forEach { (name, weightKg, achievedOn) ->
                    recordRepository.insertRecord(
                        PersonalRecord(
                            id = idProvider.newId(),
                            exerciseName = name,
                            normalizedExerciseName = name.lowercase().trim(),
                            weightKg = weightKg,
                            achievedOn = achievedOn,
                            sourceType = RecordSource.Manual,
                            createdAt = createdAt,
                        )
                    )
                }
            }
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            throw IllegalStateException("Records already seeded")
        }
    }

    /** Wipe all local data. */
    suspend fun clearAllData() {
        check(onboardingRepository.reset()) { "Failed to reset onboarding" }
        backupStore.resetLocalProfile()
    }

    // -- Helpers --

    /**
     * Build a 3-day plan for the current Mon-Sun week that contains [anchorDate], where [anchorDow]
     * (1=Mon..7=Sun) is the first of the three workout days. This is a dev-only bypass of the
     * "generate next Monday" product rule.
     */
    private suspend fun seedPlanWithAnchorDay(anchorDow: Int, anchorDate: LocalDate) {
        val thisMonday = anchorDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisSunday = thisMonday.plusDays(6)

        // Pick 3 days: anchor + 2 more spaced 2 apart (wrap within 1-7)
        val days =
            listOf(
                    anchorDow,
                    wrapDow(anchorDow + 2),
                    wrapDow(anchorDow + 4),
                )
                .distinct()
                .sorted()

        val planId = idProvider.newId()
        val plan =
            WeeklyPlan(
                id = planId,
                startDate = thisMonday.toString(),
                endDate = thisSunday.toString(),
                createdAt = timeProvider.epochMillis(),
            )

        val workouts = mutableListOf<PlannedWorkout>()
        val exercises = mutableListOf<PlannedExercise>()

        days.forEachIndexed { index, dow ->
            val template = strengthTemplates[index % strengthTemplates.size]
            val workoutId = idProvider.newId()
            workouts.add(
                PlannedWorkout(
                    id = workoutId,
                    weeklyPlanId = planId,
                    dayOfWeek = dow,
                    scheduledDate = thisMonday.plusDays((dow - 1).toLong()).toString(),
                    title = template.title,
                )
            )
            template.exercises.forEachIndexed { exIndex, ex ->
                exercises.add(
                    PlannedExercise(
                        id = idProvider.newId(),
                        plannedWorkoutId = workoutId,
                        name = ex.name,
                        sets = ex.sets,
                        reps = ex.reps,
                        weightKg = ex.weightKg,
                        orderIndex = exIndex,
                    )
                )
            }
        }

        planRepository.createPlan(plan, workouts, exercises)
    }

    private fun wrapDow(d: Int): Int = ((d - 1) % 7) + 1

    private fun sampleLoggedExercises(
        logId: String,
        title: String,
        exerciseCount: Int,
    ): List<LoggedExercise> {
        val names =
            when {
                title.contains("Pull") || title.contains("Back") ->
                    listOf("Pull-Up", "Barbell Row", "Face Pull")
                title.contains("Legs") -> listOf("Back Squat", "Romanian Deadlift", "Leg Press")
                else -> listOf("Bench Press", "Incline Dumbbell Press", "Triceps Pressdown")
            }
        return names.take(exerciseCount).mapIndexed { index, name ->
            LoggedExercise(
                id = idProvider.newId(),
                workoutLogId = logId,
                name = name,
                plannedSets = 3,
                plannedReps = if (index == 0) 8 else 10,
                plannedWeightKg = 60.0 + index * 10.0,
                orderIndex = index,
            )
        }
    }

    private fun sampleLoggedSets(
        exercises: List<LoggedExercise>,
        completedAt: Long,
    ): List<LoggedSet> =
        exercises.flatMapIndexed { exerciseIndex, exercise ->
            (1..exercise.plannedSets).map { setNumber ->
                LoggedSet(
                    id = idProvider.newId(),
                    loggedExerciseId = exercise.id,
                    setNumber = setNumber,
                    reps = exercise.plannedReps,
                    weightKg = exercise.plannedWeightKg + setNumber - 1 + exerciseIndex,
                    completedAt = completedAt - (exercise.plannedSets - setNumber) * 60_000L,
                )
            }
        }
}

private const val DEV_HISTORY_SOURCE_ID = "__dev_seed_history__"

private data class SeedExercise(
    val name: String,
    val sets: Int,
    val reps: Int,
    val weightKg: Double
)

private data class SeedWorkout(val title: String, val exercises: List<SeedExercise>)

// Inline exercise data — mirrors PlanGenerator's strengthTemplates, avoids coupling to internal
// types
private val strengthTemplates =
    listOf(
        SeedWorkout(
            "Push A",
            listOf(
                SeedExercise("Barbell Bench Press", 5, 5, 60.0),
                SeedExercise("Overhead Press", 4, 6, 40.0),
                SeedExercise("Tricep Dips", 3, 8, 0.0),
            )
        ),
        SeedWorkout(
            "Pull A",
            listOf(
                SeedExercise("Barbell Rows", 5, 5, 60.0),
                SeedExercise("Weighted Pull-ups", 4, 6, 10.0),
                SeedExercise("Barbell Curls", 3, 8, 25.0),
            )
        ),
        SeedWorkout(
            "Legs",
            listOf(
                SeedExercise("Barbell Squats", 5, 5, 80.0),
                SeedExercise("Romanian Deadlift", 4, 6, 60.0),
                SeedExercise("Calf Raises", 3, 12, 40.0),
            )
        ),
    )
