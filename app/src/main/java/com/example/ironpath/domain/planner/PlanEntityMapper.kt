package com.example.ironpath.domain.planner

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class PlanEntityMapper
@Inject
internal constructor(
    private val idProvider: IdProvider,
    private val timeProvider: TimeProvider,
    private val exerciseCatalog: ExerciseCatalog,
) {
    fun map(validatedPlan: ValidatedPlanDraft): GeneratedPlan = mapDraft(validatedPlan.draft)

    fun mapLegacyRuleBasedDraft(draft: PlanDraft): GeneratedPlan = mapDraft(draft)

    private fun mapDraft(draft: PlanDraft): GeneratedPlan {
        val planId = idProvider.newId()
        val plan =
            WeeklyPlan(
                id = planId,
                startDate = draft.targetWeekStart.toString(),
                endDate = draft.targetWeekStart.plusDays(6).toString(),
                createdAt = timeProvider.epochMillis(),
            )
        val workouts = mutableListOf<PlannedWorkout>()
        val exercises = mutableListOf<PlannedExercise>()

        draft.workouts.forEach { workoutDraft ->
            val workoutId = idProvider.newId()
            workouts.add(
                PlannedWorkout(
                    id = workoutId,
                    weeklyPlanId = planId,
                    dayOfWeek = workoutDraft.dayOfWeek,
                    scheduledDate = workoutDraft.scheduledDate.toString(),
                    title = workoutDraft.title,
                )
            )
            workoutDraft.exercises.forEachIndexed { exerciseIndex, exerciseDraft ->
                exercises.add(
                    PlannedExercise(
                        id = idProvider.newId(),
                        plannedWorkoutId = workoutId,
                        name = exerciseCatalog.require(exerciseDraft.catalogId).displayName,
                        sets = exerciseDraft.sets,
                        reps = exerciseDraft.reps,
                        weightKg = exerciseDraft.targetWeightKg,
                        orderIndex = exerciseIndex,
                    )
                )
            }
        }

        return GeneratedPlan(plan, workouts, exercises)
    }
}

@Singleton
class ValidatedPlanDraftMapper
@Inject
internal constructor(private val entityMapper: PlanEntityMapper) {
    fun map(validatedPlan: ValidatedPlanDraft): GeneratedPlan = entityMapper.map(validatedPlan)
}
