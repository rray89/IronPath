package com.example.ironpath.domain.validation

import java.time.LocalDate

enum class RecordField {
    ExerciseName,
    Weight,
    Date,
}

data class ValidatedRecordDraft(
    val exerciseName: String,
    val normalizedExerciseName: String,
    val weightKg: Double,
    val achievedOn: String,
    val note: String?,
)

sealed interface RecordDraftResult {
    data class Valid(val draft: ValidatedRecordDraft) : RecordDraftResult

    data class Invalid(val errors: Map<RecordField, String>) : RecordDraftResult
}

class RecordDraftValidator {
    fun validate(
        exerciseName: String,
        weightText: String,
        dateText: String,
        note: String,
        today: LocalDate,
    ): RecordDraftResult {
        val name = exerciseName.trim()
        val weight = weightText.toDoubleOrNull()
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
        val errors = buildMap {
            if (name.isEmpty()) {
                put(RecordField.ExerciseName, "Exercise name is required")
            }
            if (weight == null || !weight.isFinite() || weight <= 0.0) {
                put(RecordField.Weight, "Weight must be a positive number")
            }
            when {
                date == null -> put(RecordField.Date, "Invalid date format (use YYYY-MM-DD)")
                date.isAfter(today) -> put(RecordField.Date, "Date cannot be in the future")
            }
        }

        if (errors.isNotEmpty()) return RecordDraftResult.Invalid(errors)

        return RecordDraftResult.Valid(
            ValidatedRecordDraft(
                exerciseName = name,
                normalizedExerciseName = name.lowercase(),
                weightKg = requireNotNull(weight),
                achievedOn = requireNotNull(date).toString(),
                note = note.ifBlank { null },
            ),
        )
    }
}
