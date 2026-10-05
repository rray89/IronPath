package com.example.ironpath.data.repository

import com.example.ironpath.data.local.entity.*
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LoggedRecordCandidateTest {
    private val zone = ZoneId.of("America/Vancouver")
    private val exercise = LoggedExercise("ex", "log", "  DEADLIFT  ", 3, 8, 100.0, 0)
    private val set = LoggedSet("set", "ex", 1, reps = 8, weightKg = 102.5)

    private fun detail(sets: List<LoggedSet> = listOf(set)) =
        WorkoutLogDetail(
            WorkoutLog(
                "log",
                title = "Workout",
                startedAt = 1L,
                completedAt = Instant.parse("2026-07-17T01:00:00Z").toEpochMilli(),
                durationMinutes = 1,
                exerciseCount = 1
            ),
            listOf(LoggedExerciseDetail(exercise, sets)),
        )

    @Test
    fun `candidate uses local workout completion date and complete positive finite sets`() {
        val result =
            detail(
                    listOf(
                        set,
                        set.copy(id = "missing", reps = null),
                        set.copy(id = "zero", weightKg = 0.0),
                        set.copy(id = "infinity", weightKg = Double.POSITIVE_INFINITY)
                    )
                )
                .recordCandidates(zone)
        assertEquals(1, result.size)
        assertEquals("2026-07-16", result.single().achievedOn)
        assertEquals("deadlift", result.single().normalizedExerciseName)
        assertEquals(102.5, result.single().weightKg, 0.0)
    }

    @Test
    fun `equal performances share saved state but manual and other source do not`() {
        val snapshot =
            detail(
                listOf(
                    set,
                    set.copy(id = "second", reps = 5),
                    set.copy(id = "heavier", weightKg = 105.0)
                )
            )
        val record =
            PersonalRecord(
                "r",
                "Deadlift",
                "deadlift",
                102.5,
                "2026-07-16",
                sourceType = RecordSource.Logged,
                sourceWorkoutLogId = "log",
                createdAt = 99L
            )
        assertEquals(setOf("set", "second"), snapshot.savedRecordSetIds(listOf(record), zone))
        assertEquals(
            setOf("set", "second"),
            snapshot.savedRecordSetIds(listOf(record), ZoneId.of("UTC"))
        )
        assertTrue(
            snapshot
                .savedRecordSetIds(
                    listOf(
                        record.copy(sourceType = RecordSource.Manual),
                        record.copy(sourceWorkoutLogId = "other")
                    ),
                    zone
                )
                .isEmpty()
        )
    }
}
