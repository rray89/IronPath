package com.example.ironpath.testutil

import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlanStatus
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.local.entity.WorkoutStatus

object TestData {
    const val BASE_TIME = 1_700_000_000_000L

    fun plan(
        id: String = "plan-a",
        status: PlanStatus = PlanStatus.Active,
        startDate: String = "2026-07-13",
        endDate: String = "2026-07-19",
        createdAt: Long = BASE_TIME,
    ) = WeeklyPlan(id, status, startDate, endDate, createdAt)

    fun workout(
        id: String = "workout-a",
        planId: String = "plan-a",
        dayOfWeek: Int = 1,
        scheduledDate: String = "2026-07-13",
        title: String = "Strength A",
        status: WorkoutStatus = WorkoutStatus.Upcoming,
    ) = PlannedWorkout(id, planId, dayOfWeek, scheduledDate, title, status)

    fun plannedExercise(
        id: String = "planned-exercise-a",
        workoutId: String = "workout-a",
        name: String = "Squat",
        sets: Int = 3,
        reps: Int = 5,
        weightKg: Double = 100.0,
        orderIndex: Int = 0,
    ) = PlannedExercise(id, workoutId, name, sets, reps, weightKg, orderIndex)

    fun session(
        id: String = "session-a",
        workoutId: String = "workout-a",
        title: String = "Strength A",
        startedAt: Long = BASE_TIME,
        lastUpdatedAt: Long = BASE_TIME,
    ) = ActiveSession(id, workoutId, title, startedAt, lastUpdatedAt)

    fun sessionExercise(
        id: String = "session-exercise-a",
        sessionId: String = "session-a",
        name: String = "Squat",
        plannedSets: Int = 3,
        plannedReps: Int = 5,
        plannedWeightKg: Double = 100.0,
        orderIndex: Int = 0,
    ) =
        SessionExercise(
            id,
            sessionId,
            name,
            plannedSets,
            plannedReps,
            plannedWeightKg,
            orderIndex,
        )

    fun sessionSet(
        id: String = "session-set-a",
        exerciseId: String = "session-exercise-a",
        setNumber: Int = 1,
        reps: Int? = null,
        weightKg: Double? = null,
        isExtra: Boolean = false,
        completedAt: Long? = null,
    ) = SessionSet(id, exerciseId, setNumber, reps, weightKg, isExtra, completedAt)

    fun log(
        id: String = "log-a",
        title: String = "Strength A",
        workoutId: String? = "workout-a",
        startedAt: Long = BASE_TIME,
        completedAt: Long = BASE_TIME + 3_600_000,
        durationMinutes: Int = 60,
        exerciseCount: Int = 1,
    ) = WorkoutLog(id, title, workoutId, startedAt, completedAt, durationMinutes, exerciseCount)

    fun loggedExercise(
        id: String = "logged-exercise-a",
        logId: String = "log-a",
        name: String = "Squat",
        plannedSets: Int = 3,
        plannedReps: Int = 5,
        plannedWeightKg: Double = 100.0,
        orderIndex: Int = 0,
    ) = LoggedExercise(id, logId, name, plannedSets, plannedReps, plannedWeightKg, orderIndex)

    fun loggedSet(
        id: String = "logged-set-a",
        exerciseId: String = "logged-exercise-a",
        setNumber: Int = 1,
        reps: Int? = null,
        weightKg: Double? = null,
        isExtra: Boolean = false,
        completedAt: Long? = null,
    ) = LoggedSet(id, exerciseId, setNumber, reps, weightKg, isExtra, completedAt)

    fun record(
        id: String = "record-a",
        exerciseName: String = "Deadlift",
        normalizedExerciseName: String = "deadlift",
        weightKg: Double = 180.5,
        achievedOn: String = "2026-07-16",
        note: String? = null,
        sourceType: RecordSource = RecordSource.Manual,
        sourceWorkoutLogId: String? = null,
        createdAt: Long = BASE_TIME,
    ) =
        PersonalRecord(
            id,
            exerciseName,
            normalizedExerciseName,
            weightKg,
            achievedOn,
            note,
            sourceType,
            sourceWorkoutLogId,
            createdAt,
        )
}
