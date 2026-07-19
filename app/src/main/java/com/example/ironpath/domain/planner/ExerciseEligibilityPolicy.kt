package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton

enum class ExerciseIneligibilityReason {
    MISSING_EQUIPMENT,
    AI_NOT_ALLOWED,
    BEGINNER_NOT_SUITABLE,
    FORBIDDEN_MOVEMENT,
}

data class ExerciseEligibility(
    val entry: ExerciseCatalogEntry,
    val reasons: Set<ExerciseIneligibilityReason>,
) {
    val isEligible: Boolean
        get() = reasons.isEmpty()
}

@Singleton
class ExerciseEligibilityPolicy @Inject constructor(private val exerciseCatalog: ExerciseCatalog) {
    fun evaluate(
        entry: ExerciseCatalogEntry,
        context: PlanValidationContext,
    ): ExerciseEligibility =
        ExerciseEligibility(
            entry = entry,
            reasons =
                buildSet {
                    if (!context.availableEquipment.containsAll(entry.requiredEquipment)) {
                        add(ExerciseIneligibilityReason.MISSING_EQUIPMENT)
                    }
                    if (
                        context.invokedEngineType != PlanningEngineType.RULE_BASED &&
                            !entry.allowedInAiDraft
                    ) {
                        add(ExerciseIneligibilityReason.AI_NOT_ALLOWED)
                    }
                    if (
                        context.experience == TrainingExperience.BEGINNER && !entry.beginnerSuitable
                    ) {
                        add(ExerciseIneligibilityReason.BEGINNER_NOT_SUITABLE)
                    }
                    if (entry.cautionTags.any(context.forbiddenCautionTags::contains)) {
                        add(ExerciseIneligibilityReason.FORBIDDEN_MOVEMENT)
                    }
                },
        )

    fun evaluate(
        id: ExerciseCatalogId,
        context: PlanValidationContext,
    ): ExerciseEligibility? = exerciseCatalog.find(id)?.let { evaluate(it, context) }

    fun eligibleEntries(context: PlanValidationContext): List<ExerciseCatalogEntry> =
        exerciseCatalog.entries.filter { evaluate(it, context).isEligible }
}
