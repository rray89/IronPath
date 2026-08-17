package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.PlanStatus
import java.time.DayOfWeek
import java.time.LocalDate

internal object BackupBundleValidator {
    fun validate(bundle: BackupBundle): ValidationResult {
        require(bundle.localChangeRevision >= 0) { "Snapshot revision cannot be negative" }
        requireUnique("weekly plan", bundle.weeklyPlans.map { it.id })
        requireUnique("planned workout", bundle.plannedWorkouts.map { it.id })
        requireUnique("planned exercise", bundle.plannedExercises.map { it.id })
        requireUnique("workout log", bundle.workoutLogs.map { it.id })
        requireUnique("logged exercise", bundle.loggedExercises.map { it.id })
        requireUnique("logged set", bundle.loggedSets.map { it.id })
        requireUnique("personal record", bundle.personalRecords.map { it.id })
        requireNonBlankIds(bundle)

        bundle.weeklyPlans.forEach { plan ->
            val start = requireIsoDate("weekly plan start date", plan.startDate)
            val end = requireIsoDate("weekly plan end date", plan.endDate)
            require(start.dayOfWeek == DayOfWeek.MONDAY) { "Weekly plan must start on Monday" }
            require(end == start.plusDays(6)) { "Weekly plan must span exactly Monday to Sunday" }
            require(plan.createdAt >= 0) { "Weekly plan creation time cannot be negative" }
        }
        bundle.plannedWorkouts.forEach { workout ->
            require(workout.dayOfWeek in 1..7) { "Planned workout weekday must be 1 through 7" }
            requireIsoDate("planned workout date", workout.scheduledDate)
            require(workout.title.isNotBlank()) { "Planned workout title cannot be blank" }
        }
        bundle.plannedExercises.forEach { exercise ->
            require(exercise.name.isNotBlank()) { "Planned exercise name cannot be blank" }
            require(exercise.sets > 0) { "Planned exercise sets must be positive" }
            require(exercise.reps > 0) { "Planned exercise reps must be positive" }
            requireValidWeight("planned exercise", exercise.weightKg, allowZero = true)
            require(exercise.orderIndex >= 0) { "Planned exercise order cannot be negative" }
        }
        bundle.workoutLogs.forEach { log ->
            require(log.title.isNotBlank()) { "Workout log title cannot be blank" }
            require(log.startedAt >= 0 && log.completedAt >= log.startedAt) {
                "Workout log timestamps are invalid"
            }
            require(log.durationMinutes >= 0) { "Workout duration cannot be negative" }
            require(log.exerciseCount >= 0) { "Workout exercise count cannot be negative" }
        }
        bundle.loggedExercises.forEach { exercise ->
            require(exercise.name.isNotBlank()) { "Logged exercise name cannot be blank" }
            require(exercise.plannedSets > 0) { "Logged exercise sets must be positive" }
            require(exercise.plannedReps > 0) { "Logged exercise reps must be positive" }
            requireValidWeight("logged exercise", exercise.plannedWeightKg, allowZero = true)
            require(exercise.orderIndex >= 0) { "Logged exercise order cannot be negative" }
        }
        bundle.loggedSets.forEach { set ->
            require(set.setNumber > 0) { "Logged set number must be positive" }
            require(set.reps == null || set.reps >= 0) { "Logged set reps cannot be negative" }
            set.weightKg?.let { requireValidWeight("logged set", it, allowZero = true) }
            require(set.completedAt == null || set.completedAt >= 0) {
                "Logged set completion time cannot be negative"
            }
        }
        bundle.personalRecords.forEach { record ->
            require(record.exerciseName.isNotBlank()) { "Record exercise name cannot be blank" }
            require(record.normalizedExerciseName.isNotBlank()) {
                "Normalized record exercise name cannot be blank"
            }
            require(record.normalizedExerciseName == record.exerciseName.trim().lowercase()) {
                "Normalized record exercise name does not match its display name"
            }
            requireValidWeight("personal record", record.weightKg, allowZero = false)
            requireIsoDate("personal record date", record.achievedOn)
            require(record.createdAt >= 0) { "Record creation time cannot be negative" }
        }

        require(bundle.weeklyPlans.count { it.status == PlanStatus.Active } <= 1) {
            "Snapshot contains more than one active weekly plan"
        }

        val planIds = bundle.weeklyPlans.mapTo(mutableSetOf()) { it.id }
        require(bundle.plannedWorkouts.all { it.weeklyPlanId in planIds }) {
            "Snapshot contains a planned workout without its weekly plan"
        }
        val plansById = bundle.weeklyPlans.associateBy { it.id }
        bundle.plannedWorkouts.forEach { workout ->
            val plan = checkNotNull(plansById[workout.weeklyPlanId])
            val scheduledDate = LocalDate.parse(workout.scheduledDate)
            require(
                scheduledDate == LocalDate.parse(plan.startDate).plusDays(workout.dayOfWeek - 1L)
            ) {
                "Planned workout date must match its weekday in the weekly plan"
            }
        }
        requireUniqueWithinParent(
            "planned workout weekday",
            bundle.plannedWorkouts,
            parent = { it.weeklyPlanId },
            value = { it.dayOfWeek },
        )
        val workoutIds = bundle.plannedWorkouts.mapTo(mutableSetOf()) { it.id }
        require(bundle.plannedExercises.all { it.plannedWorkoutId in workoutIds }) {
            "Snapshot contains a planned exercise without its workout"
        }
        requireUniqueWithinParent(
            "planned exercise order",
            bundle.plannedExercises,
            parent = { it.plannedWorkoutId },
            value = { it.orderIndex },
        )
        val logIds = bundle.workoutLogs.mapTo(mutableSetOf()) { it.id }
        require(bundle.loggedExercises.all { it.workoutLogId in logIds }) {
            "Snapshot contains a logged exercise without its workout log"
        }
        requireUniqueWithinParent(
            "logged exercise order",
            bundle.loggedExercises,
            parent = { it.workoutLogId },
            value = { it.orderIndex },
        )
        val restoredExerciseCounts =
            bundle.loggedExercises.groupingBy { it.workoutLogId }.eachCount()
        bundle.workoutLogs.forEach { log ->
            val restoredExerciseCount = restoredExerciseCounts[log.id] ?: 0
            require(restoredExerciseCount == 0 || restoredExerciseCount == log.exerciseCount) {
                "Workout log exercise count does not match its restored exercises"
            }
        }
        val loggedExerciseIds = bundle.loggedExercises.mapTo(mutableSetOf()) { it.id }
        require(bundle.loggedSets.all { it.loggedExerciseId in loggedExerciseIds }) {
            "Snapshot contains a logged set without its exercise"
        }
        requireUniqueWithinParent(
            "logged set number",
            bundle.loggedSets,
            parent = { it.loggedExerciseId },
            value = { it.setNumber },
        )
        requireUnique(
            "personal record identity",
            bundle.personalRecords.map {
                "${it.normalizedExerciseName}\u0000${it.achievedOn}\u0000${it.weightKg}"
            },
        )

        val nulledFields = mutableSetOf<String>()
        val sanitizedLogs =
            bundle.workoutLogs.map { log ->
                if (
                    log.sourcePlannedWorkoutId != null && log.sourcePlannedWorkoutId !in workoutIds
                ) {
                    nulledFields += "sourcePlannedWorkoutId"
                    log.copy(sourcePlannedWorkoutId = null)
                } else {
                    log
                }
            }
        val sanitizedRecords =
            bundle.personalRecords.map { record ->
                if (record.sourceWorkoutLogId != null && record.sourceWorkoutLogId !in logIds) {
                    nulledFields += "sourceWorkoutLogId"
                    record.copy(sourceWorkoutLogId = null)
                } else {
                    record
                }
            }
        return ValidationResult(
            bundle =
                bundle.copy(
                    workoutLogs = sanitizedLogs,
                    personalRecords = sanitizedRecords,
                ),
            nulledProvenanceFields = nulledFields,
        )
    }

    private fun requireUnique(type: String, ids: List<String>) {
        require(ids.size == ids.toSet().size) { "Snapshot contains duplicate $type IDs" }
    }

    private fun <T, P, V> requireUniqueWithinParent(
        type: String,
        values: List<T>,
        parent: (T) -> P,
        value: (T) -> V,
    ) {
        values.groupBy(parent).values.forEach { siblings ->
            require(siblings.map(value).distinct().size == siblings.size) {
                "Snapshot contains duplicate $type values"
            }
        }
    }

    private fun requireNonBlankIds(bundle: BackupBundle) {
        val ids =
            bundle.weeklyPlans.map { it.id } +
                bundle.plannedWorkouts.flatMap { listOf(it.id, it.weeklyPlanId) } +
                bundle.plannedExercises.flatMap { listOf(it.id, it.plannedWorkoutId) } +
                bundle.workoutLogs.map { it.id } +
                bundle.loggedExercises.flatMap { listOf(it.id, it.workoutLogId) } +
                bundle.loggedSets.flatMap { listOf(it.id, it.loggedExerciseId) } +
                bundle.personalRecords.map { it.id }
        require(ids.all { it.isNotBlank() }) { "Snapshot identifiers cannot be blank" }
    }

    private fun requireIsoDate(label: String, value: String): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Snapshot $label is not a valid ISO date")
        }

    private fun requireValidWeight(label: String, value: Double, allowZero: Boolean) {
        require(value.isFinite() && if (allowZero) value >= 0 else value > 0) {
            "Snapshot $label weight is invalid"
        }
    }
}

internal data class ValidationResult(
    val bundle: BackupBundle,
    val nulledProvenanceFields: Set<String>,
)
