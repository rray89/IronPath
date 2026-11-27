package com.example.ironpath.domain.planner

import com.example.ironpath.domain.planner.OnDevicePlanPromptBuilder.Companion.MAX_INTAKE_TEXT_LENGTH
import com.example.ironpath.domain.planner.OnDevicePlanPromptBuilder.Companion.MAX_PREFERENCE_TEXT_LENGTH
import java.util.Locale
import javax.inject.Inject

class SharedTrainingNotesEvidence
@Inject
constructor(
    catalog: ExerciseCatalog,
    private val eligibilityPolicy: ExerciseEligibilityPolicy,
) {
    private val catalogPhrases =
        catalog.entries
            .map { it to it.displayName.catalogWords() }
            .sortedByDescending { it.second.length }

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
        val matches = catalogMatches(note, matchCautionTags)
        if (matches.isEmpty()) return "$label — No catalog match to verify in this draft."

        val included = matches.filter { it.id in setsById }
        val omitted =
            matches.filterNot { it.id in setsById }.map { eligibilityPolicy.evaluate(it, context) }
        val eligibleOmitted = omitted.filter(ExerciseEligibility::isEligible)
        val excludedExamples =
            omitted
                .filterNot(ExerciseEligibility::isEligible)
                .distinctBy(ExerciseEligibility::reasons)
                .take(MAX_EXAMPLES)
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
            ) +
                excludedExamples.map { exclusion ->
                    val limits =
                        exclusion.reasons.joinToString(" and ") { reason ->
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
                    "${exclusion.entry.displayName} was ineligible under $limits."
                }
        return "$label — ${findings.joinToString(" ")}"
    }

    private fun catalogMatches(note: String, matchCautionTags: Boolean): Set<ExerciseCatalogEntry> =
        buildSet {
            var remaining = note.catalogWords()
            // Consume specific names before considering shorter names and bare family terms.
            catalogPhrases.forEach { (entry, phrase) ->
                if (phrase in remaining) {
                    add(entry)
                    remaining = remaining.replace(phrase, "  ")
                }
            }
            val families = movementFamilies.filter { " $it " in remaining }
            catalogPhrases.forEach { (entry, phrase) ->
                if (
                    families.any { " $it " in phrase } ||
                        (matchCautionTags &&
                            entry.cautionTags.any { it.name.catalogWords() in remaining })
                )
                    add(entry)
            }
        }

    private fun String.catalogWords(): String =
        lowercase(Locale.ROOT).split(nonWord).filter(String::isNotEmpty).joinToString(
            separator = " ",
            prefix = " ",
            postfix = " "
        ) { word ->
            val singular = if (word == "presses") "press" else word.removeSuffix("s")
            when {
                word == "pushups" || word == "pushup" -> "push up"
                word == "ups" -> "up"
                singular in movementFamilies -> singular
                else -> word
            }
        }

    private companion object {
        const val MAX_EXAMPLES = 2
        val nonWord = Regex("[^\\p{L}\\p{N}]+")
        val movementFamilies = setOf("squat", "lunge", "row", "deadlift", "curl", "press", "raise")
    }
}
