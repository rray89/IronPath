package com.example.ironpath.domain.planner

import com.example.ironpath.domain.planner.OnDevicePlanPromptBuilder.Companion.MAX_INTAKE_TEXT_LENGTH
import com.example.ironpath.domain.planner.OnDevicePlanPromptBuilder.Companion.MAX_PREFERENCE_TEXT_LENGTH
import java.util.Locale
import javax.inject.Inject

class SharedTrainingNotesEvidence
@Inject
constructor(
    private val catalog: ExerciseCatalog,
    private val eligibilityPolicy: ExerciseEligibilityPolicy,
) {
    fun summarize(draft: PlanDraft, request: PlanningRequest): List<String> {
        val allExercises = draft.workouts.flatMap(WorkoutDraft::exercises)
        val setsById =
            allExercises.groupBy(ExerciseDraft::catalogId).mapValues { (_, exercises) ->
                exercises.sumOf(ExerciseDraft::sets)
            }
        val context = request.validationContext(PlanningEngineType.DEBUG_REMOTE_AI)
        val intake = request.intake
        return listOfNotNull(
            describe(
                "Injury Notes",
                intake.injuryNotes.boundedPromptText(MAX_INTAKE_TEXT_LENGTH),
                setsById,
                context,
                matchCautionTags = true,
            ),
            describe(
                "Exercise Preferences",
                intake.exercisePreferences.boundedPromptText(MAX_PREFERENCE_TEXT_LENGTH),
                setsById,
                context,
            ),
            describe(
                "Exercise Dislikes",
                intake.exerciseDislikes.boundedPromptText(MAX_PREFERENCE_TEXT_LENGTH),
                setsById,
                context,
            ),
        )
    }

    private fun describe(
        label: String,
        note: String,
        setsById: Map<ExerciseCatalogId, Int>,
        context: PlanValidationContext,
        matchCautionTags: Boolean = false,
    ): String? {
        if (note.isBlank()) return null
        val words = note.catalogWords()
        val matches =
            catalog.entries.filter { entry ->
                words.contains(entry.displayName.catalogWords()) ||
                    movementTerms.any { (term, ids) -> entry.id in ids && words.contains(term) } ||
                    (matchCautionTags &&
                        entry.cautionTags.any { tag -> words.contains(tag.name.catalogWords()) })
            }
        if (matches.isEmpty()) return "$label — No catalog match to verify in this draft."

        val included = matches.filter { it.id in setsById }
        val omitted =
            matches.filterNot { it.id in setsById }.map { eligibilityPolicy.evaluate(it, context) }
        val eligibleOmitted = omitted.filter(ExerciseEligibility::isEligible)
        val excluded = omitted.firstOrNull { !it.isEligible }
        val findings =
            listOfNotNull(
                included.takeIf(List<*>::isNotEmpty)?.let { entries ->
                    "Includes " +
                        entries.take(MAX_EXAMPLES).joinToString(" and ") { entry ->
                            val sets = setsById.getValue(entry.id)
                            "${entry.displayName} ($sets ${if (sets == 1) "set" else "sets"})"
                        } +
                        "."
                },
                eligibleOmitted.takeIf(List<*>::isNotEmpty)?.let { entries ->
                    "Omits " +
                        entries.take(MAX_EXAMPLES).joinToString(" and ") { it.entry.displayName } +
                        " from the eligible options."
                },
                excluded?.let {
                    val limits =
                        it.reasons.joinToString(" and ") { reason ->
                            when (reason) {
                                ExerciseIneligibilityReason.FORBIDDEN_MOVEMENT -> "Movement Limits"
                                ExerciseIneligibilityReason.MISSING_EQUIPMENT ->
                                    "available equipment"
                                ExerciseIneligibilityReason.BEGINNER_NOT_SUITABLE ->
                                    "experience level"
                                ExerciseIneligibilityReason.AI_NOT_ALLOWED ->
                                    "AI catalog restrictions"
                            }
                        }
                    "${it.entry.displayName} was ineligible under $limits."
                },
            )
        return "$label — ${findings.joinToString(" ")}"
    }

    private fun String.catalogWords(): String =
        " " + lowercase(Locale.ROOT).replace(nonWord, " ").trim() + " "

    private companion object {
        const val MAX_EXAMPLES = 2
        val nonWord = Regex("[^\\p{L}\\p{N}]+")
        val movementTerms =
            mapOf(
                " squat " to
                    setOf(ExerciseCatalogIds.BODYWEIGHT_SQUATS, ExerciseCatalogIds.BARBELL_SQUATS),
                " squats " to
                    setOf(ExerciseCatalogIds.BODYWEIGHT_SQUATS, ExerciseCatalogIds.BARBELL_SQUATS),
                " lunge " to setOf(ExerciseCatalogIds.WALKING_LUNGES),
                " lunges " to setOf(ExerciseCatalogIds.WALKING_LUNGES),
                " push up " to setOf(ExerciseCatalogIds.PUSH_UPS),
                " push ups " to setOf(ExerciseCatalogIds.PUSH_UPS),
                " pushups " to setOf(ExerciseCatalogIds.PUSH_UPS),
            )
    }
}
