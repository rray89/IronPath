package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton

sealed interface OnDeviceDraftMapping {
    data class Success(val draft: PlanDraft) : OnDeviceDraftMapping

    data class Invalid(val message: String) : OnDeviceDraftMapping
}

@Singleton
class OnDevicePlanDraftMapper @Inject constructor(private val exerciseCatalog: ExerciseCatalog) {
    fun map(
        proposal: OnDevicePlanProposal,
        request: PlanningRequest,
        generationDurationMillis: Long,
    ): OnDeviceDraftMapping {
        val workouts = mutableListOf<WorkoutDraft>()
        proposal.workouts.forEach { workout ->
            if (workout.dayOfWeek !in 1..7) {
                return OnDeviceDraftMapping.Invalid(
                    "Workout day must be between Monday and Sunday."
                )
            }
            val exercises = mutableListOf<ExerciseDraft>()
            workout.exercises.forEach { exercise ->
                val catalogId = ExerciseCatalogId(exercise.catalogId)
                if (exerciseCatalog.find(catalogId) == null) {
                    return OnDeviceDraftMapping.Invalid(
                        "The draft contains an exercise that is not in the catalog."
                    )
                }
                exercises +=
                    ExerciseDraft(
                        catalogId = catalogId,
                        sets = exercise.sets,
                        reps = exercise.reps,
                        targetWeightKg = exercise.targetWeightKg,
                    )
            }
            workouts +=
                WorkoutDraft(
                    dayOfWeek = workout.dayOfWeek,
                    scheduledDate =
                        request.targetWeekStart.plusDays((workout.dayOfWeek - 1).toLong()),
                    title = workout.title.normalizedModelText(MAX_WORKOUT_TITLE_LENGTH),
                    exercises = exercises,
                )
        }
        return OnDeviceDraftMapping.Success(
            PlanDraft(
                targetWeekStart = request.targetWeekStart,
                workouts = workouts,
                rationale = proposal.rationale,
                warnings = proposal.warnings,
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = PlanningEngineType.ON_DEVICE_AI,
                        generationDurationMillis = generationDurationMillis,
                    ),
            )
        )
    }

    private companion object {
        const val MAX_WORKOUT_TITLE_LENGTH = 80
    }
}
