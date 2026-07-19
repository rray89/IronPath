package com.example.ironpath.domain.validation

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDraftValidatorTest {

    private val validator = RecordDraftValidator()
    private val today = LocalDate.parse("2026-07-16")

    @Test
    fun `all invalid fields are returned with field-specific messages`() {
        val result =
            validator.validate(
                exerciseName = "   ",
                weightText = "not-a-number",
                dateText = "not-a-date",
                note = "",
                today = today,
            )

        val errors = (result as RecordDraftResult.Invalid).errors
        assertEquals(
            setOf(RecordField.ExerciseName, RecordField.Weight, RecordField.Date),
            errors.keys,
        )
        assertEquals("Exercise name is required", errors[RecordField.ExerciseName])
        assertEquals("Weight must be a positive number", errors[RecordField.Weight])
        assertEquals("Invalid date format (use YYYY-MM-DD)", errors[RecordField.Date])
    }

    @Test
    fun `zero negative NaN and infinite record weights attach errors to weight field`() {
        listOf("0", "-1", "NaN", "Infinity", "-Infinity").forEach { weightText ->
            val result =
                validator.validate(
                    exerciseName = "Deadlift",
                    weightText = weightText,
                    dateText = today.toString(),
                    note = "",
                    today = today,
                )

            val errors = (result as RecordDraftResult.Invalid).errors
            assertEquals(
                "Expected weight error for $weightText",
                "Weight must be a positive number",
                errors[RecordField.Weight],
            )
            assertEquals(setOf(RecordField.Weight), errors.keys)
        }
    }

    @Test
    fun `malformed and future dates attach errors to date field`() {
        val malformed =
            validator.validate(
                exerciseName = "Deadlift",
                weightText = "180.5",
                dateText = "07/16/2026",
                note = "",
                today = today,
            ) as RecordDraftResult.Invalid
        val future =
            validator.validate(
                exerciseName = "Deadlift",
                weightText = "180.5",
                dateText = today.plusDays(1).toString(),
                note = "",
                today = today,
            ) as RecordDraftResult.Invalid

        assertEquals("Invalid date format (use YYYY-MM-DD)", malformed.errors[RecordField.Date])
        assertEquals("Date cannot be in the future", future.errors[RecordField.Date])
        assertFalse(malformed.errors.containsKey(RecordField.Weight))
        assertFalse(future.errors.containsKey(RecordField.Weight))
    }

    @Test
    fun `valid record draft is trimmed normalized and mapped`() {
        val result =
            validator.validate(
                exerciseName = "  DeadLift  ",
                weightText = "180.5",
                dateText = today.toString(),
                note = "Felt strong",
                today = today,
            ) as RecordDraftResult.Valid

        assertEquals("DeadLift", result.draft.exerciseName)
        assertEquals("deadlift", result.draft.normalizedExerciseName)
        assertEquals(180.5, result.draft.weightKg, 0.0)
        assertEquals("2026-07-16", result.draft.achievedOn)
        assertEquals("Felt strong", result.draft.note)
    }

    @Test
    fun `normalization is stable when the device locale changes`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))

            val result =
                validator.validate(
                    exerciseName = "INDIGO",
                    weightText = "80",
                    dateText = today.toString(),
                    note = "",
                    today = today,
                ) as RecordDraftResult.Valid

            assertEquals("indigo", result.draft.normalizedExerciseName)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `blank note maps to null`() {
        val result =
            validator.validate(
                exerciseName = "Deadlift",
                weightText = "180.5",
                dateText = today.toString(),
                note = "   ",
                today = today,
            ) as RecordDraftResult.Valid

        assertNull(result.draft.note)
        assertTrue(result.draft.achievedOn <= today.toString())
    }
}
