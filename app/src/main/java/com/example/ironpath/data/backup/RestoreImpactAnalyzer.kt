package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.domain.backup.BackupCategoryImpact

/** Counts exact typed stable-ID changes for a whole-bundle replacement. */
internal object RestoreImpactAnalyzer {
    fun analyze(current: BackupBundle, target: BackupBundle): Map<String, BackupCategoryImpact> =
        linkedMapOf(
            "WeeklyPlan" to category(current.weeklyPlans, target.weeklyPlans, WeeklyPlan::id),
            "PlannedWorkout" to
                category(current.plannedWorkouts, target.plannedWorkouts, PlannedWorkout::id),
            "PlannedExercise" to
                category(current.plannedExercises, target.plannedExercises, PlannedExercise::id),
            "WorkoutLog" to category(current.workoutLogs, target.workoutLogs, WorkoutLog::id),
            "LoggedExercise" to
                category(current.loggedExercises, target.loggedExercises, LoggedExercise::id),
            "LoggedSet" to category(current.loggedSets, target.loggedSets, LoggedSet::id),
            "PersonalRecord" to
                category(current.personalRecords, target.personalRecords, PersonalRecord::id),
        )

    private fun <T> category(
        current: List<T>,
        target: List<T>,
        id: (T) -> String,
    ): BackupCategoryImpact {
        val currentById = current.associateBy(id)
        val targetById = target.associateBy(id)
        val sharedIds = currentById.keys intersect targetById.keys
        return BackupCategoryImpact(
            added = targetById.keys.count { it !in currentById },
            updated = sharedIds.count { currentById[it] != targetById[it] },
            replaced = currentById.keys.count { it !in targetById },
        )
    }
}
