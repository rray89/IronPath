package com.example.ironpath.benchmark

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deterministic data setup available only in the generated release-like test targets.
 *
 * The activity deliberately remains visible after seeding so an out-of-process Macrobenchmark can
 * observe [SEED_READY] before force-stopping the target process. It is absent from debug and
 * release builds.
 */
@AndroidEntryPoint
class BenchmarkSeedActivity : ComponentActivity() {
    @Inject lateinit var database: IronPathDatabase

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status =
            TextView(this).apply {
                text = SEEDING
                contentDescription = SEEDING
                gravity = Gravity.CENTER
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }
        setContentView(status)

        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    when (intent.getStringExtra(SEED_SCENARIO_EXTRA)) {
                        SEED_HISTORY -> seedHistory(database)
                        SEED_ACTIVE -> seedActiveSession(database)
                        else -> error("Unknown benchmark seed scenario")
                    }
                }
            }

            val marker =
                result.fold(
                    onSuccess = { SEED_READY },
                    onFailure = { failure -> "$SEED_FAILED: ${failure.javaClass.simpleName}" },
                )
            status.text = marker
            status.contentDescription = marker
        }
    }
}

private suspend fun seedHistory(database: IronPathDatabase) {
    database.clearAllTables()
    database.withTransaction {
        val historyDao = database.historyDao()
        val recordDao = database.recordDao()

        val logs =
            List(HISTORY_ITEM_COUNT) { index ->
                WorkoutLog(
                    id = historyLogId(index),
                    title = "Benchmark Workout ${index.padded(3)}",
                    startedAt = HISTORY_BASE_EPOCH_MS - index * DAY_MS - 45 * MINUTE_MS,
                    completedAt = HISTORY_BASE_EPOCH_MS - index * DAY_MS,
                    durationMinutes = 45,
                    exerciseCount = 1,
                )
            }
        logs.forEach { historyDao.insertLog(it) }

        historyDao.insertLoggedExercises(
            logs.mapIndexed { index, log ->
                LoggedExercise(
                    id = "benchmark-logged-exercise-${index.padded(3)}",
                    workoutLogId = log.id,
                    name = "Benchmark Exercise ${index.padded(3)}",
                    plannedSets = 1,
                    plannedReps = 8,
                    plannedWeightKg = 100.0 + index,
                    orderIndex = 0,
                )
            },
        )
        historyDao.insertLoggedSets(
            logs.indices.map { index ->
                LoggedSet(
                    id = "benchmark-logged-set-${index.padded(3)}",
                    loggedExerciseId = "benchmark-logged-exercise-${index.padded(3)}",
                    setNumber = 1,
                    reps = 8,
                    weightKg = 100.0 + index,
                    completedAt = HISTORY_BASE_EPOCH_MS - index * DAY_MS,
                )
            },
        )

        repeat(HISTORY_ITEM_COUNT) { index ->
            val exercise = "Benchmark Exercise ${index.padded(3)}"
            recordDao.insertRecord(
                PersonalRecord(
                    id = "benchmark-record-${index.padded(3)}",
                    exerciseName = exercise,
                    normalizedExerciseName = exercise.lowercase(),
                    weightKg = 100.0 + index,
                    achievedOn = HISTORY_BASE_DATE.minusDays(index.toLong()).toString(),
                    note = "Benchmark seed",
                    sourceType = RecordSource.Manual,
                    createdAt = HISTORY_BASE_EPOCH_MS - index * DAY_MS,
                ),
            )
        }
    }
}

private suspend fun seedActiveSession(database: IronPathDatabase) {
    database.clearAllTables()
    val now = System.currentTimeMillis()
    val today = LocalDate.now()
    val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
    database.withTransaction {
        val planDao = database.planDao()
        val sessionDao = database.sessionDao()
        planDao.insertPlan(
            WeeklyPlan(
                id = ACTIVE_PLAN_ID,
                startDate = weekStart.toString(),
                endDate = weekStart.plusDays(6).toString(),
                createdAt = now,
            )
        )
        planDao.insertWorkouts(
            listOf(
                PlannedWorkout(
                    id = ACTIVE_WORKOUT_ID,
                    weeklyPlanId = ACTIVE_PLAN_ID,
                    dayOfWeek = today.dayOfWeek.value,
                    scheduledDate = today.toString(),
                    title = "Benchmark Capacity Session",
                )
            )
        )
        planDao.insertExercises(
            List(ACTIVE_EXERCISE_COUNT) { exerciseIndex ->
                PlannedExercise(
                    id = "benchmark-planned-exercise-${exerciseIndex.padded(3)}",
                    plannedWorkoutId = ACTIVE_WORKOUT_ID,
                    name = "Benchmark Movement ${exerciseIndex.padded(2)}",
                    sets = SETS_PER_EXERCISE,
                    reps = 8,
                    weightKg = 100.0,
                    orderIndex = exerciseIndex,
                )
            }
        )
        sessionDao.insertSession(
            ActiveSession(
                id = ACTIVE_SESSION_ID,
                sourcePlannedWorkoutId = ACTIVE_WORKOUT_ID,
                workoutTitle = "Benchmark Capacity Session",
                startedAt = now - 5 * MINUTE_MS,
                lastUpdatedAt = now,
            ),
        )

        val exercises =
            List(ACTIVE_EXERCISE_COUNT) { exerciseIndex ->
                SessionExercise(
                    id = activeExerciseId(exerciseIndex),
                    activeSessionId = ACTIVE_SESSION_ID,
                    name = "Benchmark Movement ${exerciseIndex.padded(2)}",
                    plannedSets = SETS_PER_EXERCISE,
                    plannedReps = 8,
                    plannedWeightKg = 100.0,
                    orderIndex = exerciseIndex,
                )
            }
        sessionDao.insertSessionExercises(exercises)

        exercises.indices.forEach { exerciseIndex ->
            repeat(SETS_PER_EXERCISE) { setIndex ->
                sessionDao.insertSet(
                    SessionSet(
                        id = activeSetId(exerciseIndex, setIndex),
                        sessionExerciseId = activeExerciseId(exerciseIndex),
                        setNumber = setIndex + 1,
                        weightKg = 100.0,
                    ),
                )
            }
        }
    }
}

private fun historyLogId(index: Int) = "benchmark-log-${index.padded(3)}"

private fun activeExerciseId(index: Int) = "benchmark-exercise-${index.padded(3)}"

private fun activeSetId(exerciseIndex: Int, setIndex: Int) =
    "benchmark-set-${exerciseIndex.padded(3)}-${setIndex.padded(2)}"

private fun Int.padded(length: Int) = toString().padStart(length, '0')

private const val SEED_SCENARIO_EXTRA = "benchmark_scenario"
private const val SEED_HISTORY = "history"
private const val SEED_ACTIVE = "active"
private const val SEEDING = "BENCHMARK_SEEDING"
private const val SEED_READY = "BENCHMARK_SEED_READY"
private const val SEED_FAILED = "BENCHMARK_SEED_FAILED"
private const val ACTIVE_PLAN_ID = "benchmark-active-plan"
private const val ACTIVE_WORKOUT_ID = "benchmark-planned-workout"
private const val ACTIVE_SESSION_ID = "benchmark-active-session"
private const val HISTORY_ITEM_COUNT = 250
private const val ACTIVE_EXERCISE_COUNT = 20
private const val SETS_PER_EXERCISE = 5
private const val MINUTE_MS = 60_000L
private const val DAY_MS = 86_400_000L
private const val HISTORY_BASE_EPOCH_MS = 1_784_160_000_000L
private val HISTORY_BASE_DATE: LocalDate = LocalDate.of(2026, 7, 16)
