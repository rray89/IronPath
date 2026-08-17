package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlanStatus
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.local.entity.WorkoutStatus
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

class BackupSnapshotCodec(
    private val maxChunkBytes: Int = MAX_CHUNK_BYTES,
    private val maxChunks: Int = MAX_CHUNKS,
) {
    init {
        require(maxChunkBytes > 0)
        require(maxChunks in 1..MAX_CHUNKS)
    }

    fun encode(bundle: BackupBundle): EncodedBackupSnapshot {
        val validated = BackupBundleValidator.validate(bundle).bundle
        val envelopes = validated.toEnvelopes()
        val chunks = mutableListOf<BackupChunk>()
        var pending = mutableListOf<JsonObject>()
        envelopes.forEach { envelope ->
            val candidate = pending + envelope
            if (
                chunkPayload(chunks.size, validated.localChangeRevision, candidate).byteSize() <=
                    maxChunkBytes
            ) {
                pending = candidate.toMutableList()
            } else {
                if (pending.isEmpty()) throw BackupSnapshotTooLargeException()
                chunks += createChunk(chunks.size, validated.localChangeRevision, pending)
                if (chunks.size >= maxChunks) throw BackupSnapshotTooLargeException()
                pending = mutableListOf(envelope)
                if (
                    chunkPayload(chunks.size, validated.localChangeRevision, pending).byteSize() >
                        maxChunkBytes
                ) {
                    throw BackupSnapshotTooLargeException()
                }
            }
        }
        chunks += createChunk(chunks.size, validated.localChangeRevision, pending)
        if (chunks.size > maxChunks) throw BackupSnapshotTooLargeException()

        val counts =
            linkedMapOf(
                WEEKLY_PLAN to validated.weeklyPlans.size,
                PLANNED_WORKOUT to validated.plannedWorkouts.size,
                PLANNED_EXERCISE to validated.plannedExercises.size,
                WORKOUT_LOG to validated.workoutLogs.size,
                LOGGED_EXERCISE to validated.loggedExercises.size,
                LOGGED_SET to validated.loggedSets.size,
                PERSONAL_RECORD to validated.personalRecords.size,
            )
        return EncodedBackupSnapshot(
            formatVersion = FORMAT_VERSION,
            localChangeRevision = validated.localChangeRevision,
            chunks = chunks,
            entityCounts = counts,
            encodedByteCount = chunks.sumOf { it.encodedByteCount },
            contentDigest = digest(JsonArray(envelopes).toString()),
        )
    }

    fun decode(snapshot: EncodedBackupSnapshot): BackupBundle = decodeValidated(snapshot).bundle

    private fun decodeValidated(snapshot: EncodedBackupSnapshot): ValidationResult {
        try {
            require(snapshot.formatVersion == FORMAT_VERSION) { "Unsupported backup format" }
            require(snapshot.chunks.isNotEmpty()) { "Snapshot has no chunks" }
            require(snapshot.chunks.size <= maxChunks) { "Snapshot has too many chunks" }
            require(snapshot.chunks.map { it.index } == snapshot.chunks.indices.toList()) {
                "Snapshot chunk indexes are not contiguous"
            }
            require(snapshot.encodedByteCount == snapshot.chunks.sumOf { it.encodedByteCount }) {
                "Snapshot byte count does not match"
            }
            val envelopes = mutableListOf<JsonObject>()
            snapshot.chunks.forEach { chunk ->
                require(chunk.payload.byteSize() == chunk.encodedByteCount) {
                    "Chunk byte count does not match"
                }
                require(chunk.encodedByteCount <= maxChunkBytes) { "Chunk exceeds size limit" }
                require(digest(chunk.payload) == chunk.digest) { "Chunk digest does not match" }
                val root = Json.parseToJsonElement(chunk.payload).jsonObject
                require(root.int("formatVersion") == FORMAT_VERSION) { "Unsupported chunk format" }
                require(root.long("localChangeRevision") == snapshot.localChangeRevision) {
                    "Chunk revision does not match"
                }
                require(root.int("chunkIndex") == chunk.index) { "Chunk index does not match" }
                envelopes += root.getValue("entities").jsonArray.map { it.jsonObject }
            }
            require(snapshot.contentDigest == digest(JsonArray(envelopes).toString())) {
                "Snapshot digest does not match"
            }

            val decoded = envelopes.toBundle(snapshot.localChangeRevision)
            require(decoded.entityCounts() == snapshot.entityCounts) {
                "Snapshot entity counts do not match"
            }
            return BackupBundleValidator.validate(decoded)
        } catch (failure: InvalidBackupSnapshotException) {
            throw failure
        } catch (failure: Exception) {
            throw InvalidBackupSnapshotException(
                failure.message ?: "Invalid backup snapshot",
                failure
            )
        }
    }

    internal fun decodeForRestore(
        snapshot: EncodedBackupSnapshot,
        lineage: RestoreLineage,
    ): ValidatedRestoreArtifact {
        require(lineage.remoteDigest == snapshot.contentDigest) {
            "Restore lineage digest does not match the remote snapshot"
        }
        val validated = decodeValidated(snapshot)
        return ValidatedRestoreArtifact(
            bundle = validated.bundle,
            lineage = lineage,
            contentDigest = snapshot.contentDigest,
            nulledProvenanceFields = validated.nulledProvenanceFields,
        )
    }

    private fun createChunk(
        index: Int,
        localChangeRevision: Long,
        envelopes: List<JsonObject>,
    ): BackupChunk {
        val payload = chunkPayload(index, localChangeRevision, envelopes)
        return BackupChunk(
            index = index,
            payload = payload,
            encodedByteCount = payload.byteSize(),
            digest = digest(payload),
        )
    }

    private fun chunkPayload(
        index: Int,
        localChangeRevision: Long,
        envelopes: List<JsonObject>,
    ): String =
        buildJsonObject {
                put("formatVersion", FORMAT_VERSION)
                put("localChangeRevision", localChangeRevision)
                put("chunkIndex", index)
                put("entities", JsonArray(envelopes))
            }
            .toString()

    private fun BackupBundle.toEnvelopes(): List<JsonObject> = buildList {
        weeklyPlans
            .sortedBy { it.id }
            .forEach { entity ->
                add(
                    envelope(WEEKLY_PLAN, entity.id) {
                        put("status", entity.status.name)
                        put("startDate", entity.startDate)
                        put("endDate", entity.endDate)
                        put("createdAt", entity.createdAt)
                    }
                )
            }
        plannedWorkouts
            .sortedBy { it.id }
            .forEach { entity ->
                add(
                    envelope(PLANNED_WORKOUT, entity.id, entity.weeklyPlanId) {
                        put("weeklyPlanId", entity.weeklyPlanId)
                        put("dayOfWeek", entity.dayOfWeek)
                        put("scheduledDate", entity.scheduledDate)
                        put("title", entity.title)
                        put("status", entity.status.name)
                    }
                )
            }
        plannedExercises
            .sortedBy { it.id }
            .forEach { entity ->
                add(
                    envelope(PLANNED_EXERCISE, entity.id, entity.plannedWorkoutId) {
                        put("plannedWorkoutId", entity.plannedWorkoutId)
                        put("name", entity.name)
                        put("sets", entity.sets)
                        put("reps", entity.reps)
                        put("weightKg", entity.weightKg)
                        put("orderIndex", entity.orderIndex)
                    }
                )
            }
        workoutLogs
            .sortedBy { it.id }
            .forEach { entity ->
                add(
                    envelope(WORKOUT_LOG, entity.id) {
                        put("title", entity.title)
                        putNullable("sourcePlannedWorkoutId", entity.sourcePlannedWorkoutId)
                        put("startedAt", entity.startedAt)
                        put("completedAt", entity.completedAt)
                        put("durationMinutes", entity.durationMinutes)
                        put("exerciseCount", entity.exerciseCount)
                    }
                )
            }
        loggedExercises
            .sortedBy { it.id }
            .forEach { entity ->
                add(
                    envelope(LOGGED_EXERCISE, entity.id, entity.workoutLogId) {
                        put("workoutLogId", entity.workoutLogId)
                        put("name", entity.name)
                        put("plannedSets", entity.plannedSets)
                        put("plannedReps", entity.plannedReps)
                        put("plannedWeightKg", entity.plannedWeightKg)
                        put("orderIndex", entity.orderIndex)
                    }
                )
            }
        loggedSets
            .sortedBy { it.id }
            .forEach { entity ->
                add(
                    envelope(LOGGED_SET, entity.id, entity.loggedExerciseId) {
                        put("loggedExerciseId", entity.loggedExerciseId)
                        put("setNumber", entity.setNumber)
                        putNullable("reps", entity.reps)
                        putNullable("weightKg", entity.weightKg)
                        put("isExtra", entity.isExtra)
                        putNullable("completedAt", entity.completedAt)
                    }
                )
            }
        personalRecords
            .sortedBy { it.id }
            .forEach { entity ->
                add(
                    envelope(PERSONAL_RECORD, entity.id) {
                        put("exerciseName", entity.exerciseName)
                        put("normalizedExerciseName", entity.normalizedExerciseName)
                        put("weightKg", entity.weightKg)
                        put("achievedOn", entity.achievedOn)
                        putNullable("note", entity.note)
                        put("sourceType", entity.sourceType.name)
                        putNullable("sourceWorkoutLogId", entity.sourceWorkoutLogId)
                        put("createdAt", entity.createdAt)
                    }
                )
            }
    }

    private fun envelope(
        type: String,
        id: String,
        parentId: String? = null,
        payload: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("entityVersion", ENTITY_VERSION)
        put("id", id)
        putNullable("parentId", parentId)
        put("payload", buildJsonObject(payload))
    }

    private fun List<JsonObject>.toBundle(localChangeRevision: Long): BackupBundle {
        val weeklyPlans = mutableListOf<WeeklyPlan>()
        val plannedWorkouts = mutableListOf<PlannedWorkout>()
        val plannedExercises = mutableListOf<PlannedExercise>()
        val workoutLogs = mutableListOf<WorkoutLog>()
        val loggedExercises = mutableListOf<LoggedExercise>()
        val loggedSets = mutableListOf<LoggedSet>()
        val personalRecords = mutableListOf<PersonalRecord>()
        forEach { envelope ->
            require(envelope.int("entityVersion") == ENTITY_VERSION) {
                "Unsupported entity version"
            }
            val id = envelope.string("id")
            val parentId = envelope.nullableString("parentId")
            val payload = envelope.getValue("payload").jsonObject
            when (envelope.string("type")) {
                WEEKLY_PLAN -> {
                    require(parentId == null) { "Weekly plan cannot have a parent" }
                    weeklyPlans +=
                        WeeklyPlan(
                            id,
                            PlanStatus.valueOf(payload.string("status")),
                            payload.string("startDate"),
                            payload.string("endDate"),
                            payload.long("createdAt"),
                        )
                }
                PLANNED_WORKOUT -> {
                    requireParent(parentId, payload.string("weeklyPlanId"), PLANNED_WORKOUT)
                    plannedWorkouts +=
                        PlannedWorkout(
                            id,
                            payload.string("weeklyPlanId"),
                            payload.int("dayOfWeek"),
                            payload.string("scheduledDate"),
                            payload.string("title"),
                            WorkoutStatus.valueOf(payload.string("status")),
                        )
                }
                PLANNED_EXERCISE -> {
                    requireParent(parentId, payload.string("plannedWorkoutId"), PLANNED_EXERCISE)
                    plannedExercises +=
                        PlannedExercise(
                            id,
                            payload.string("plannedWorkoutId"),
                            payload.string("name"),
                            payload.int("sets"),
                            payload.int("reps"),
                            payload.double("weightKg"),
                            payload.int("orderIndex"),
                        )
                }
                WORKOUT_LOG -> {
                    require(parentId == null) { "Workout log cannot have a parent" }
                    workoutLogs +=
                        WorkoutLog(
                            id,
                            payload.string("title"),
                            payload.nullableString("sourcePlannedWorkoutId"),
                            payload.long("startedAt"),
                            payload.long("completedAt"),
                            payload.int("durationMinutes"),
                            payload.int("exerciseCount"),
                        )
                }
                LOGGED_EXERCISE -> {
                    requireParent(parentId, payload.string("workoutLogId"), LOGGED_EXERCISE)
                    loggedExercises +=
                        LoggedExercise(
                            id,
                            payload.string("workoutLogId"),
                            payload.string("name"),
                            payload.int("plannedSets"),
                            payload.int("plannedReps"),
                            payload.double("plannedWeightKg"),
                            payload.int("orderIndex"),
                        )
                }
                LOGGED_SET -> {
                    requireParent(parentId, payload.string("loggedExerciseId"), LOGGED_SET)
                    loggedSets +=
                        LoggedSet(
                            id,
                            payload.string("loggedExerciseId"),
                            payload.int("setNumber"),
                            payload.nullableInt("reps"),
                            payload.nullableDouble("weightKg"),
                            payload.boolean("isExtra"),
                            payload.nullableLong("completedAt"),
                        )
                }
                PERSONAL_RECORD -> {
                    require(parentId == null) { "Personal record cannot have a parent" }
                    personalRecords +=
                        PersonalRecord(
                            id,
                            payload.string("exerciseName"),
                            payload.string("normalizedExerciseName"),
                            payload.double("weightKg"),
                            payload.string("achievedOn"),
                            payload.nullableString("note"),
                            RecordSource.valueOf(payload.string("sourceType")),
                            payload.nullableString("sourceWorkoutLogId"),
                            payload.long("createdAt"),
                        )
                }
                else -> error("Unknown entity type")
            }
        }
        return BackupBundle(
            localChangeRevision,
            weeklyPlans.sortedBy { it.id },
            plannedWorkouts.sortedBy { it.id },
            plannedExercises.sortedBy { it.id },
            workoutLogs.sortedBy { it.id },
            loggedExercises.sortedBy { it.id },
            loggedSets.sortedBy { it.id },
            personalRecords.sortedBy { it.id },
        )
    }

    private fun BackupBundle.entityCounts(): Map<String, Int> =
        linkedMapOf(
            WEEKLY_PLAN to weeklyPlans.size,
            PLANNED_WORKOUT to plannedWorkouts.size,
            PLANNED_EXERCISE to plannedExercises.size,
            WORKOUT_LOG to workoutLogs.size,
            LOGGED_EXERCISE to loggedExercises.size,
            LOGGED_SET to loggedSets.size,
            PERSONAL_RECORD to personalRecords.size,
        )

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.let { value ->
            require(value.isString) { "$name must be a JSON string" }
            value.content
        }

    private fun JsonObject.nullableString(name: String): String? =
        getValue(name).let { value ->
            if (value is JsonNull) {
                null
            } else {
                require(value.jsonPrimitive.isString) { "$name must be a JSON string or null" }
                value.jsonPrimitive.contentOrNull
            }
        }

    private fun JsonObject.int(name: String): Int = unquotedPrimitive(name).int

    private fun JsonObject.nullableInt(name: String): Int? =
        getValue(name).let { if (it is JsonNull) null else unquotedPrimitive(name).int }

    private fun JsonObject.long(name: String): Long = unquotedPrimitive(name).long

    private fun JsonObject.nullableLong(name: String): Long? =
        getValue(name).let { if (it is JsonNull) null else unquotedPrimitive(name).long }

    private fun JsonObject.double(name: String): Double = unquotedPrimitive(name).double

    private fun JsonObject.nullableDouble(name: String): Double? =
        getValue(name).let { if (it is JsonNull) null else unquotedPrimitive(name).double }

    private fun JsonObject.boolean(name: String): Boolean = unquotedPrimitive(name).boolean

    private fun requireParent(actual: String?, expected: String, type: String) {
        require(actual == expected) { "$type envelope parent does not match its payload" }
    }

    private fun JsonObject.unquotedPrimitive(name: String): JsonPrimitive =
        getValue(name).jsonPrimitive.also { value ->
            require(!value.isString) { "$name must be an unquoted JSON primitive" }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        name: String,
        value: String?,
    ) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        name: String,
        value: Int?
    ) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        name: String,
        value: Long?,
    ) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)

    private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
        name: String,
        value: Double?,
    ) = put(name, value?.let(::JsonPrimitive) ?: JsonNull)

    private fun String.byteSize(): Int = toByteArray(Charsets.UTF_8).size

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) { byte ->
            "%02x".format(byte)
        }

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_CHUNK_BYTES = 750 * 1024
        const val MAX_CHUNKS = 6
        private const val ENTITY_VERSION = 1
        private const val WEEKLY_PLAN = "WeeklyPlan"
        private const val PLANNED_WORKOUT = "PlannedWorkout"
        private const val PLANNED_EXERCISE = "PlannedExercise"
        private const val WORKOUT_LOG = "WorkoutLog"
        private const val LOGGED_EXERCISE = "LoggedExercise"
        private const val LOGGED_SET = "LoggedSet"
        private const val PERSONAL_RECORD = "PersonalRecord"
    }
}

data class EncodedBackupSnapshot(
    val formatVersion: Int,
    val localChangeRevision: Long,
    val chunks: List<BackupChunk>,
    val entityCounts: Map<String, Int>,
    val encodedByteCount: Int,
    val contentDigest: String,
)

data class BackupChunk(
    val index: Int,
    val payload: String,
    val encodedByteCount: Int,
    val digest: String,
)

class BackupSnapshotTooLargeException : IllegalArgumentException("Backup exceeds chunk limits")

class InvalidBackupSnapshotException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
