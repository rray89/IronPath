package com.example.ironpath.domain.planner

import com.example.ironpath.domain.planner.OnDevicePlanPromptBuilder.Companion.MAX_INTAKE_TEXT_LENGTH
import com.example.ironpath.domain.planner.OnDevicePlanPromptBuilder.Companion.MAX_PREFERENCE_TEXT_LENGTH

internal enum class TrainingNoteField(
    val title: String,
    val promptLabel: String,
    val sendLimit: Int,
) {
    INJURY_NOTES("Injury Notes", "Injury notes", MAX_INTAKE_TEXT_LENGTH),
    EXERCISE_PREFERENCES(
        "Exercise Preferences",
        "Exercise preferences",
        MAX_PREFERENCE_TEXT_LENGTH
    ),
    EXERCISE_DISLIKES("Exercise Dislikes", "Exercise dislikes", MAX_PREFERENCE_TEXT_LENGTH),
}

internal data class TrainingNoteForSharing(
    val field: TrainingNoteField,
    val text: String,
    val wasTruncated: Boolean,
) {
    override fun toString() = "TrainingNoteForSharing(field=$field, wasTruncated=$wasTruncated)"
}

internal fun PlanningIntake.trainingNotesForSharing(): List<TrainingNoteForSharing> =
    listOf(
        TrainingNoteField.INJURY_NOTES.prepare(injuryNotes),
        TrainingNoteField.EXERCISE_PREFERENCES.prepare(exercisePreferences),
        TrainingNoteField.EXERCISE_DISLIKES.prepare(exerciseDislikes),
    )

private fun TrainingNoteField.prepare(raw: String): TrainingNoteForSharing {
    val cleaned = raw.normalizedPromptText().replace(UNPAIRED_SURROGATE, "\uFFFD")
    val text = cleaned.take(sendLimit).dropLastWhile(Char::isHighSurrogate).trimEnd()
    return TrainingNoteForSharing(this, text, wasTruncated = cleaned.length > sendLimit)
}

// A complete supplementary character is one Unicode code point and does not match this class.
private val UNPAIRED_SURROGATE = Regex("\\p{Cs}")
