package com.example.ironpath.data.backup

internal data class SyncMergeAnalysis(
    val localChanges: Map<String, Int>,
    val cloudChanges: Map<String, Int>,
    val conflicts: Map<String, Int>,
    val localResult: BackupBundle?,
    val cloudResult: BackupBundle?,
)

internal object ManualSyncMerger {
    fun analyze(base: BackupBundle?, local: BackupBundle, cloud: BackupBundle): SyncMergeAnalysis {
        val localChanges = linkedMapOf<String, Int>()
        val cloudChanges = linkedMapOf<String, Int>()
        val conflicts = linkedMapOf<String, Int>()
        fun <T> merge(
            type: String,
            old: List<T>?,
            left: List<T>,
            right: List<T>,
            id: (T) -> String
        ): Pair<List<T>, List<T>> {
            val before = old.orEmpty().associateBy(id)
            val locals = left.associateBy(id)
            val remotes = right.associateBy(id)
            val keys = (before.keys + locals.keys + remotes.keys).sorted()
            val keepLocal = mutableListOf<T>()
            val keepCloud = mutableListOf<T>()
            var localCount = 0
            var cloudCount = 0
            var conflictCount = 0
            keys.forEach { key ->
                val original = before[key]
                val localValue = locals[key]
                val cloudValue = remotes[key]
                val changedLocal = localValue != original
                val changedCloud = cloudValue != original
                if (changedLocal) localCount++
                if (changedCloud) cloudCount++
                if (changedLocal && changedCloud && localValue != cloudValue) {
                    conflictCount++
                    localValue?.let(keepLocal::add)
                    cloudValue?.let(keepCloud::add)
                } else {
                    val selected = if (changedLocal) localValue else cloudValue
                    selected?.let {
                        keepLocal += it
                        keepCloud += it
                    }
                }
            }
            localChanges[type] = localCount
            cloudChanges[type] = cloudCount
            conflicts[type] = conflictCount
            return keepLocal to keepCloud
        }
        val plans =
            merge("WeeklyPlan", base?.weeklyPlans, local.weeklyPlans, cloud.weeklyPlans) { it.id }
        val workouts =
            merge(
                "PlannedWorkout",
                base?.plannedWorkouts,
                local.plannedWorkouts,
                cloud.plannedWorkouts
            ) {
                it.id
            }
        val planned =
            merge(
                "PlannedExercise",
                base?.plannedExercises,
                local.plannedExercises,
                cloud.plannedExercises
            ) {
                it.id
            }
        val logs =
            merge("WorkoutLog", base?.workoutLogs, local.workoutLogs, cloud.workoutLogs) { it.id }
        val logged =
            merge(
                "LoggedExercise",
                base?.loggedExercises,
                local.loggedExercises,
                cloud.loggedExercises
            ) {
                it.id
            }
        val sets =
            merge("LoggedSet", base?.loggedSets, local.loggedSets, cloud.loggedSets) { it.id }
        val records =
            merge(
                "PersonalRecord",
                base?.personalRecords,
                local.personalRecords,
                cloud.personalRecords
            ) {
                it.id
            }
        fun candidate(cloudChoice: Boolean): BackupBundle? {
            fun <T> Pair<T, T>.select(): T = if (cloudChoice) second else first
            val bundle =
                BackupBundle(
                    local.localChangeRevision,
                    plans.select(),
                    workouts.select(),
                    planned.select(),
                    logs.select(),
                    logged.select(),
                    sets.select(),
                    records.select()
                )
            return try {
                BackupBundleValidator.validate(bundle).bundle
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return SyncMergeAnalysis(
            localChanges,
            cloudChanges,
            conflicts,
            candidate(false),
            candidate(true)
        )
    }
}
