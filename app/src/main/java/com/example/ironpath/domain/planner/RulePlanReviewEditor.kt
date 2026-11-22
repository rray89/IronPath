package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.domain.identity.IdProvider
import java.time.DayOfWeek
import java.time.LocalDate
import javax.inject.Inject

data class RuleExerciseValues(val name: String, val sets: Int, val reps: Int, val weightKg: Double)

data class RuleExerciseValidation(
    val values: RuleExerciseValues?,
    val nameError: String? = null,
    val setsError: String? = null,
    val repsError: String? = null,
    val weightError: String? = null,
)

/** The V2 free-text prescription rules belong only to legacy rule review. */
data class RuleExerciseForm(
    val name: String,
    val sets: String,
    val reps: String,
    val weight: String
) {
    fun validate(): RuleExerciseValidation {
        val cleanName = name.trim()
        val parsedSets = sets.trim().toIntOrNull()?.takeIf { it in 1..20 }
        val parsedReps = reps.trim().toIntOrNull()?.takeIf { it in 1..100 }
        val parsedWeight = weight.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        return RuleExerciseValidation(
            values =
                if (
                    cleanName.isNotEmpty() &&
                        parsedSets != null &&
                        parsedReps != null &&
                        parsedWeight != null
                )
                    RuleExerciseValues(cleanName, parsedSets, parsedReps, parsedWeight)
                else null,
            nameError = if (cleanName.isEmpty()) "Enter an exercise name." else null,
            setsError = if (parsedSets == null) "Enter 1–20 whole sets." else null,
            repsError = if (parsedReps == null) "Enter 1–100 whole reps." else null,
            weightError =
                if (parsedWeight == null) "Enter a finite weight of 0 kg or more." else null,
        )
    }
}

data class RuleExerciseRemoval(
    val before: GeneratedPlan,
    val after: GeneratedPlan,
    val exerciseName: String
)

/** Immutable, UUID-preserving transformations; persistence remains the acceptance command's job. */
class RulePlanReviewEditor @Inject constructor(private val idProvider: IdProvider) {
    fun moveWorkout(plan: GeneratedPlan, workoutId: String, day: Int): GeneratedPlan {
        if (day !in 1..7) return plan
        val workout = plan.workouts.find { it.id == workoutId } ?: return plan
        if (workout.dayOfWeek == day) return plan
        val start = runCatching { LocalDate.parse(plan.plan.startDate) }.getOrNull() ?: return plan
        if (
            start.dayOfWeek != DayOfWeek.MONDAY || plan.plan.endDate != start.plusDays(6).toString()
        )
            return plan
        val occupied = plan.workouts.find { it.dayOfWeek == day }
        return plan.copy(
            workouts =
                plan.workouts
                    .map {
                        when (it.id) {
                            workoutId ->
                                it.copy(
                                    dayOfWeek = day,
                                    scheduledDate = start.plusDays((day - 1).toLong()).toString()
                                )
                            occupied?.id ->
                                it.copy(
                                    dayOfWeek = workout.dayOfWeek,
                                    scheduledDate = workout.scheduledDate
                                )
                            else -> it
                        }
                    }
                    .sortedBy { it.dayOfWeek }
        )
    }

    fun editExercise(
        plan: GeneratedPlan,
        exerciseId: String,
        form: RuleExerciseForm
    ): GeneratedPlan {
        val values = form.validate().values ?: return plan
        val current = plan.exercises.find { it.id == exerciseId } ?: return plan
        val updated =
            current.copy(
                name = values.name,
                sets = values.sets,
                reps = values.reps,
                weightKg = values.weightKg
            )
        if (updated == current) return plan
        return plan.copy(
            exercises = plan.exercises.map { if (it.id == exerciseId) updated else it }
        )
    }

    fun addExercise(plan: GeneratedPlan, workoutId: String, form: RuleExerciseForm): GeneratedPlan {
        val values = form.validate().values ?: return plan
        if (plan.workouts.none { it.id == workoutId }) return plan
        val exercises = plan.children(workoutId)
        val added =
            PlannedExercise(
                idProvider.newId(),
                workoutId,
                values.name,
                values.sets,
                values.reps,
                values.weightKg,
                exercises.size
            )
        return plan.replaceChildren(workoutId, exercises + added)
    }

    fun moveExercise(
        plan: GeneratedPlan,
        workoutId: String,
        exerciseId: String,
        index: Int
    ): GeneratedPlan {
        val exercises = plan.children(workoutId)
        val source = exercises.indexOfFirst { it.id == exerciseId }
        if (source < 0 || index !in exercises.indices || source == index) return plan
        val reordered = exercises.toMutableList().apply { add(index, removeAt(source)) }
        return plan.replaceChildren(workoutId, reordered)
    }

    fun removeExercise(plan: GeneratedPlan, exerciseId: String): RuleExerciseRemoval? {
        val exercise = plan.exercises.find { it.id == exerciseId } ?: return null
        val remaining = plan.children(exercise.plannedWorkoutId).filterNot { it.id == exerciseId }
        val after =
            plan.replaceChildren(exercise.plannedWorkoutId, remaining).let {
                if (remaining.isEmpty())
                    it.copy(
                        workouts =
                            it.workouts.filterNot { workout ->
                                workout.id == exercise.plannedWorkoutId
                            }
                    )
                else it
            }
        return RuleExerciseRemoval(plan, after, exercise.name)
    }

    private fun GeneratedPlan.children(workoutId: String) =
        exercises.filter { it.plannedWorkoutId == workoutId }.sortedBy { it.orderIndex }

    private fun GeneratedPlan.replaceChildren(workoutId: String, children: List<PlannedExercise>) =
        copy(
            exercises =
                exercises.filterNot { it.plannedWorkoutId == workoutId } +
                    children.mapIndexed { index, exercise -> exercise.copy(orderIndex = index) }
        )
}
