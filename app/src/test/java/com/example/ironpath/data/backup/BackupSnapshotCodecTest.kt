package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSnapshotCodecTest {
    @Test
    fun encode_isDeterministicAndRoundTripsEveryIncludedEntityType() {
        val bundle = representativeBundle()
        val reordered =
            bundle.copy(
                plannedWorkouts = bundle.plannedWorkouts.reversed(),
                plannedExercises = bundle.plannedExercises.reversed(),
            )
        val codec = BackupSnapshotCodec()

        val first = codec.encode(bundle)
        val second = codec.encode(reordered)
        val newerRevision = codec.encode(bundle.copy(localChangeRevision = 10))

        assertEquals(1, first.formatVersion)
        assertEquals(first.contentDigest, second.contentDigest)
        assertEquals(first.contentDigest, newerRevision.contentDigest)
        assertEquals(first.chunks.map { it.payload }, second.chunks.map { it.payload })
        assertNotEquals(first.chunks.map { it.payload }, newerRevision.chunks.map { it.payload })
        assertEquals(
            mapOf(
                "WeeklyPlan" to 1,
                "PlannedWorkout" to 2,
                "PlannedExercise" to 2,
                "WorkoutLog" to 1,
                "LoggedExercise" to 1,
                "LoggedSet" to 1,
                "PersonalRecord" to 1,
            ),
            first.entityCounts,
        )
        assertEquals(bundle, codec.decode(first))
    }

    @Test
    fun encode_usesBoundedChunksAndRejectsSnapshotsBeyondTheConfiguredLimit() {
        val bundle =
            representativeBundle()
                .copy(
                    personalRecords =
                        (1..6).map { index ->
                            record(
                                id = "record-$index",
                                exerciseName = "Exercise ${"x".repeat(100)} $index",
                                normalizedExerciseName =
                                    "exercise ${"x".repeat(100)} $index".lowercase(),
                                sourceWorkoutLogId = null,
                            )
                        }
                )

        val bounded = BackupSnapshotCodec(maxChunkBytes = 1_400, maxChunks = 6).encode(bundle)

        assertTrue(bounded.chunks.size in 2..6)
        assertTrue(bounded.chunks.all { it.encodedByteCount <= 1_400 })
        assertEquals(bounded.chunks.indices.toList(), bounded.chunks.map { it.index })
        assertThrows(BackupSnapshotTooLargeException::class.java) {
            BackupSnapshotCodec(maxChunkBytes = 1_400, maxChunks = 1).encode(bundle)
        }
    }

    @Test
    fun decode_rejectsPayloadWhoseDigestNoLongerMatches() {
        val codec = BackupSnapshotCodec()
        val encoded = codec.encode(representativeBundle())
        val first = encoded.chunks.first()
        val tampered =
            encoded.copy(
                chunks =
                    listOf(first.copy(payload = first.payload.replace("Strength A", "Tampered"))) +
                        encoded.chunks.drop(1)
            )

        assertThrows(InvalidBackupSnapshotException::class.java) { codec.decode(tampered) }
    }

    @Test
    fun decode_rejectsWrongJsonFieldTypesEvenWhenIntegrityMetadataMatches() {
        val codec = BackupSnapshotCodec()
        val encoded = codec.encode(representativeBundle())
        val first = encoded.chunks.first()
        val payload = first.payload.replaceFirst("\"title\":\"Strength A\"", "\"title\":123")
        assertNotEquals(first.payload, payload)
        val chunks =
            listOf(
                first.copy(
                    payload = payload,
                    encodedByteCount = payload.toByteArray(Charsets.UTF_8).size,
                    digest = digest(payload),
                )
            ) + encoded.chunks.drop(1)
        val entities =
            chunks.flatMap { chunk ->
                Json.parseToJsonElement(chunk.payload).jsonObject.getValue("entities").jsonArray
            }
        val tampered =
            encoded.copy(
                chunks = chunks,
                encodedByteCount = chunks.sumOf { it.encodedByteCount },
                contentDigest = digest(JsonArray(entities).toString()),
            )

        assertThrows(InvalidBackupSnapshotException::class.java) { codec.decode(tampered) }
    }

    @Test
    fun decode_rejectsEnvelopeWhoseParentDoesNotMatchItsPayload() {
        val codec = BackupSnapshotCodec()
        val encoded = codec.encode(representativeBundle())
        val first = encoded.chunks.first()
        val payload =
            first.payload.replaceFirst(
                "\"parentId\":\"plan-a\"",
                "\"parentId\":\"different-plan\"",
            )
        assertNotEquals(first.payload, payload)
        val chunks =
            listOf(
                first.copy(
                    payload = payload,
                    encodedByteCount = payload.toByteArray(Charsets.UTF_8).size,
                    digest = digest(payload),
                )
            ) + encoded.chunks.drop(1)
        val entities =
            chunks.flatMap { chunk ->
                Json.parseToJsonElement(chunk.payload).jsonObject.getValue("entities").jsonArray
            }
        val tampered =
            encoded.copy(
                chunks = chunks,
                encodedByteCount = chunks.sumOf { it.encodedByteCount },
                contentDigest = digest(JsonArray(entities).toString()),
            )

        assertThrows(InvalidBackupSnapshotException::class.java) { codec.decode(tampered) }
    }

    @Test
    fun decodeForRestore_rejectsLineageFromADifferentSnapshot() {
        val codec = BackupSnapshotCodec()
        val encoded = codec.encode(representativeBundle())

        assertThrows(IllegalArgumentException::class.java) {
            codec.decodeForRestore(
                encoded,
                RestoreLineage(
                    ownerUid = "owner-a",
                    remoteBackupId = "backup-a",
                    remoteGeneration = 1,
                    remoteDigest = "different-digest",
                    sourceInstallationId = "installation-a",
                    completedAt = 1,
                ),
            )
        }
    }

    private fun representativeBundle(): BackupBundle {
        val plan = plan()
        val firstWorkout = workout()
        val secondWorkout =
            workout(
                id = "workout-b",
                dayOfWeek = 3,
                scheduledDate = "2026-07-15",
                title = "Strength B",
            )
        return BackupBundle(
            localChangeRevision = 9,
            weeklyPlans = listOf(plan),
            plannedWorkouts = listOf(firstWorkout, secondWorkout),
            plannedExercises =
                listOf(
                    plannedExercise(),
                    plannedExercise(
                        id = "planned-exercise-b",
                        workoutId = secondWorkout.id,
                        name = "Bench Press",
                    ),
                ),
            workoutLogs = listOf(log()),
            loggedExercises = listOf(loggedExercise()),
            loggedSets = listOf(loggedSet()),
            personalRecords = listOf(record(sourceWorkoutLogId = "log-a")),
        )
    }

    private fun plan() =
        WeeklyPlan(
            id = "plan-a",
            startDate = "2026-07-13",
            endDate = "2026-07-19",
            createdAt = 1_700_000_000_000,
        )

    private fun workout(
        id: String = "workout-a",
        dayOfWeek: Int = 1,
        scheduledDate: String = "2026-07-13",
        title: String = "Strength A",
    ) = PlannedWorkout(id, "plan-a", dayOfWeek, scheduledDate, title)

    private fun plannedExercise(
        id: String = "planned-exercise-a",
        workoutId: String = "workout-a",
        name: String = "Squat",
    ) = PlannedExercise(id, workoutId, name, 3, 5, 100.0, 0)

    private fun log() =
        WorkoutLog(
            id = "log-a",
            title = "Strength A",
            sourcePlannedWorkoutId = "workout-a",
            startedAt = 1_700_000_000_000,
            completedAt = 1_700_003_600_000,
            durationMinutes = 60,
            exerciseCount = 1,
        )

    private fun loggedExercise() =
        LoggedExercise("logged-exercise-a", "log-a", "Squat", 3, 5, 100.0, 0)

    private fun loggedSet() =
        LoggedSet(
            id = "logged-set-a",
            loggedExerciseId = "logged-exercise-a",
            setNumber = 1,
            reps = 5,
            weightKg = 100.0,
            completedAt = 1_700_003_600_000,
        )

    private fun record(
        id: String = "record-a",
        exerciseName: String = "Deadlift",
        normalizedExerciseName: String = "deadlift",
        sourceWorkoutLogId: String? = null,
    ) =
        PersonalRecord(
            id = id,
            exerciseName = exerciseName,
            normalizedExerciseName = normalizedExerciseName,
            weightKg = 180.5,
            achievedOn = "2026-07-16",
            sourceWorkoutLogId = sourceWorkoutLogId,
            createdAt = 1_700_000_000_000,
        )

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) { byte ->
            "%02x".format(byte)
        }
}
