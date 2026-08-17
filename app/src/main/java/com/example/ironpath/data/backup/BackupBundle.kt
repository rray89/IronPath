package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog

data class BackupBundle(
    val localChangeRevision: Long,
    val weeklyPlans: List<WeeklyPlan>,
    val plannedWorkouts: List<PlannedWorkout>,
    val plannedExercises: List<PlannedExercise>,
    val workoutLogs: List<WorkoutLog>,
    val loggedExercises: List<LoggedExercise>,
    val loggedSets: List<LoggedSet>,
    val personalRecords: List<PersonalRecord>,
) {
    fun allStableIds(): Set<String> = buildSet {
        weeklyPlans.mapTo(this) { it.id }
        plannedWorkouts.mapTo(this) { it.id }
        plannedExercises.mapTo(this) { it.id }
        workoutLogs.mapTo(this) { it.id }
        loggedExercises.mapTo(this) { it.id }
        loggedSets.mapTo(this) { it.id }
        personalRecords.mapTo(this) { it.id }
    }
}
