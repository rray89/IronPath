package com.example.ironpath.domain.session

import com.example.ironpath.data.local.entity.SessionSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSetInputTest {

    private val nowMillis = 1_721_153_600_000L

    private fun set(
        reps: Int? = null,
        weightKg: Double? = null,
        completedAt: Long? = null,
    ) =
        SessionSet(
            id = "set-1",
            sessionExerciseId = "exercise-1",
            setNumber = 3,
            reps = reps,
            weightKg = weightKg,
            isExtra = true,
            completedAt = completedAt,
        )

    @Test
    fun `set is complete only when reps and weight are both present`() {
        val weightOnly = SessionSetInput.withWeight(set(), "60", nowMillis)
        val completed = SessionSetInput.withReps(weightOnly, "8", nowMillis)

        assertNull(weightOnly.completedAt)
        assertEquals(nowMillis, completed.completedAt)
        assertEquals(8, completed.reps)
        assertEquals(60.0, completed.weightKg ?: Double.NaN, 0.0)
    }

    @Test
    fun `zero weight is valid for a bodyweight exercise`() {
        val result = SessionSetInput.withWeight(set(reps = 12), "0", nowMillis)

        assertEquals(0.0, result.weightKg ?: Double.NaN, 0.0)
        assertEquals(nowMillis, result.completedAt)
    }

    @Test
    fun `negative or non-finite weight is cleared and cannot complete a set`() {
        listOf("-0.1", "NaN", "Infinity", "-Infinity").forEach { weightText ->
            val result =
                SessionSetInput.withWeight(
                    set(reps = 8, weightKg = 60.0, completedAt = nowMillis - 1),
                    weightText,
                    nowMillis,
                )

            assertNull("Expected cleared weight for $weightText", result.weightKg)
            assertNull("Expected cleared completion for $weightText", result.completedAt)
        }
    }

    @Test
    fun `zero or negative reps are cleared and cannot complete a set`() {
        listOf("0", "-1", "1.5", "not-a-number").forEach { repsText ->
            val result =
                SessionSetInput.withReps(
                    set(reps = 8, weightKg = 60.0, completedAt = nowMillis - 1),
                    repsText,
                    nowMillis,
                )

            assertNull("Expected cleared reps for $repsText", result.reps)
            assertNull("Expected cleared completion for $repsText", result.completedAt)
        }
    }

    @Test
    fun `completedAt is assigned from TimeProvider and cleared when an input is removed`() {
        val completed = SessionSetInput.withReps(set(weightKg = 40.0), "10", nowMillis)
        val changedWeight = SessionSetInput.withWeight(completed, "45", nowMillis + 1_000)
        val cleared = SessionSetInput.withReps(changedWeight, "", nowMillis + 2_000)

        assertEquals(nowMillis, completed.completedAt)
        assertEquals(
            "Existing completion time must be stable",
            nowMillis,
            changedWeight.completedAt
        )
        assertNull(cleared.completedAt)
    }

    @Test
    fun `input updates preserve set identity order and extra marker`() {
        val result = SessionSetInput.withWeight(set(reps = 8), "60", nowMillis)

        assertEquals("set-1", result.id)
        assertEquals("exercise-1", result.sessionExerciseId)
        assertEquals(3, result.setNumber)
        assertTrue(result.isExtra)
    }
}
