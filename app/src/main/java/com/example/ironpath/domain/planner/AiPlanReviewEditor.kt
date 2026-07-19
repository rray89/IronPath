package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton

sealed interface AiPlanDraftReviewState {
    val draft: PlanDraft
    val context: PlanValidationContext
    val violations: List<PlanViolation>
    val canAccept: Boolean

    data class Valid(
        val validatedPlan: ValidatedPlanDraft,
        override val context: PlanValidationContext = validatedPlan.context,
    ) : AiPlanDraftReviewState {
        override val draft: PlanDraft
            get() = validatedPlan.draft

        override val violations: List<PlanViolation> = emptyList()
        override val canAccept: Boolean = true
    }

    data class Invalid(
        override val draft: PlanDraft,
        override val context: PlanValidationContext,
        override val violations: List<PlanViolation>,
    ) : AiPlanDraftReviewState {
        override val canAccept: Boolean = false
    }
}

@Singleton
class AiPlanReviewEditor
@Inject
constructor(
    private val planValidator: PlanValidator,
    private val exerciseEligibilityPolicy: ExerciseEligibilityPolicy,
) {
    fun start(validatedPlan: ValidatedPlanDraft): AiPlanDraftReviewState =
        AiPlanDraftReviewState.Valid(validatedPlan)

    fun eligibleEntries(state: AiPlanDraftReviewState): List<ExerciseCatalogEntry> =
        exerciseEligibilityPolicy.eligibleEntries(state.context)

    fun addExercise(
        state: AiPlanDraftReviewState,
        workoutDay: Int,
        exercise: ExerciseDraft,
    ): AiPlanDraftReviewState {
        if (!exercise.isEligible(state.context)) return state

        val workoutIndex = state.draft.workouts.indexOfFirst { it.dayOfWeek == workoutDay }
        if (workoutIndex < 0) return state
        val workout = state.draft.workouts[workoutIndex]
        if (
            workout.exercises.size >= PlanValidationLimits.MAX_EXERCISES_PER_DAY ||
                workout.exercises.any { it.catalogId == exercise.catalogId }
        ) {
            return state
        }

        return revalidate(
            state,
            state.draft.replaceWorkout(
                workoutIndex,
                workout.copy(exercises = workout.exercises + exercise),
            ),
        )
    }

    fun replaceExercise(
        state: AiPlanDraftReviewState,
        workoutDay: Int,
        originalId: ExerciseCatalogId,
        replacement: ExerciseDraft,
    ): AiPlanDraftReviewState {
        if (!replacement.isEligible(state.context)) return state

        val workoutIndex = state.draft.workouts.indexOfFirst { it.dayOfWeek == workoutDay }
        if (workoutIndex < 0) return state
        val workout = state.draft.workouts[workoutIndex]
        val exerciseIndex = workout.exercises.indexOfFirst { it.catalogId == originalId }
        if (exerciseIndex < 0) return state
        if (
            replacement.catalogId != originalId &&
                workout.exercises.any { it.catalogId == replacement.catalogId }
        ) {
            return state
        }

        val exercises =
            workout.exercises.toMutableList().apply { this[exerciseIndex] = replacement }
        return revalidate(
            state,
            state.draft.replaceWorkout(workoutIndex, workout.copy(exercises = exercises)),
        )
    }

    private fun ExerciseDraft.isEligible(context: PlanValidationContext): Boolean =
        exerciseEligibilityPolicy.evaluate(catalogId, context)?.isEligible == true

    private fun revalidate(
        previous: AiPlanDraftReviewState,
        editedDraft: PlanDraft,
    ): AiPlanDraftReviewState =
        when (val result = planValidator.validate(editedDraft, previous.context)) {
            is PlanValidationResult.Valid ->
                AiPlanDraftReviewState.Valid(
                    validatedPlan = result.validatedPlan,
                    context = previous.context,
                )
            is PlanValidationResult.Invalid ->
                AiPlanDraftReviewState.Invalid(
                    draft = editedDraft,
                    context = previous.context,
                    violations = result.violations,
                )
        }

    private fun PlanDraft.replaceWorkout(
        index: Int,
        workout: WorkoutDraft,
    ): PlanDraft = copy(workouts = workouts.toMutableList().apply { this[index] = workout })
}
