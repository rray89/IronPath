package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.*
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Firestore protocol, independent of Android/Firebase SDK types and credentials. */
@Singleton
class FirestoreManualBackupStore
@Inject
constructor(
    private val clients: FirestoreBackupClientFactory,
    private val ids: IdProvider,
    private val time: TimeProvider,
    @param:AuthPreviewAppVersion private val appVersion: String,
) : RemoteBackupStore {
    private val codec = BackupSnapshotCodec()

    override suspend fun latest(accountId: AccountId): RemoteBackupRead =
        RemoteBackupRead.Failed(BackupFailureReason.ServiceUnavailable)

    override suspend fun inspect(accountId: AccountId): RemoteBackupInspection =
        safely({ RemoteBackupInspection.Failed(it) }) {
            val client = clients.forAccount(accountId)
            inspect(client, user(accountId))
        }

    override suspend fun publish(
        accountId: AccountId,
        expectedGeneration: Long,
        sourceInstallationId: String,
        snapshot: EncodedBackupSnapshot
    ): RemoteBackupPublish =
        safely({ RemoteBackupPublish.Failed(it) }) {
            codec.decode(snapshot)
            require(expectedGeneration in 0 until Long.MAX_VALUE)
            require(sourceInstallationId.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
            val client = clients.forAccount(accountId)
            val user = user(accountId)
            client.transaction { if (get(user) == null) put(user, emptyMetadata()) }
            val existing = metadata(requireNotNull(client.get(user)))
            requireGeneration(existing, expectedGeneration)
            var active = existing.textOrNull("activeUploadBackupId")
            if (active != null) {
                val manifest = client.get("$user/backups/$active")
                if (
                    manifest != null &&
                        resumable(manifest, sourceInstallationId, snapshot, expectedGeneration)
                ) {
                    // Resume only this exact installation/snapshot. Existing immutable chunks are
                    // verified individually, including after a lost claim/chunk acknowledgement.
                } else {
                    if (
                        manifest != null &&
                            time.epochMillis() - timestamp(manifest, "createdAt") < LEASE_MILLIS
                    )
                        fail(BackupFailureReason.ConcurrentRemoteChange)
                    reclaim(client, user, active, expectedGeneration)
                    active = null
                }
            }
            if (active == null) {
                retainTwo(client, user)
                active = ids.newId()
                require(active.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
                val backup = "$user/backups/$active"
                client.transaction {
                    val current = metadata(requireNotNull(get(user)))
                    requireGeneration(current, expectedGeneration)
                    if (current.textOrNull("activeUploadBackupId") != null || get(backup) != null)
                        fail(BackupFailureReason.ConcurrentRemoteChange)
                    val registry = registry(current)
                    require(registry.size < 4)
                    put(
                        backup,
                        manifest(active, sourceInstallationId, expectedGeneration, snapshot),
                        setOf("createdAt")
                    )
                    put(
                        user,
                        current.with(
                            "backupIds" to JsonArray((registry + active).map(::JsonPrimitive)),
                            "activeUploadBackupId" to JsonPrimitive(active)
                        )
                    )
                }
            }
            val id = active
            val backup = "$user/backups/$id"
            snapshot.chunks.forEach { chunk ->
                client.transaction {
                    val current = metadata(requireNotNull(get(user)))
                    requireSlot(current, expectedGeneration, id)
                    val path = chunkPath(backup, chunk.index)
                    val prior = get(path)
                    val expected = chunkFields(snapshot.formatVersion, chunk)
                    if (prior == null) put(path, expected)
                    else if (prior != expected) fail(BackupFailureReason.InvalidSnapshot)
                }
            }
            // Read back exact bytes and validate the complete graph before making COMPLETE visible.
            verifyChunks(client, backup, snapshot)
            client.transaction {
                val current = metadata(requireNotNull(get(user)))
                val uploading = requireNotNull(get(backup))
                requireSlot(current, expectedGeneration, id)
                if (!resumable(uploading, sourceInstallationId, snapshot, expectedGeneration))
                    fail(BackupFailureReason.InvalidSnapshot)
                put(
                    backup,
                    uploading.with("state" to JsonPrimitive("COMPLETE")),
                    setOf("completedAt")
                )
                put(
                    user,
                    current.with(
                        "generation" to JsonPrimitive(Math.addExact(expectedGeneration, 1)),
                        "activeUploadBackupId" to JsonNull,
                        "latestCompleteBackupId" to JsonPrimitive(id),
                        "latestSourceInstallationId" to JsonPrimitive(sourceInstallationId)
                    ),
                    setOf("latestCompletedAt")
                )
            }
            val completed =
                (inspect(client, user) as? RemoteBackupInspection.Complete)?.backup
                    ?: fail(BackupFailureReason.InvalidSnapshot)
            if (
                completed.summary.backupId != id ||
                    completed.generation != expectedGeneration + 1 ||
                    completed.contentDigest != snapshot.contentDigest
            )
                fail(BackupFailureReason.ConcurrentRemoteChange)
            // Completion is durable. Failed bounded retention remains registered for explicit
            // retry.
            try {
                retainTwo(client, user)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                /* A complete backup still succeeded; never hide its registry. */
            }
            RemoteBackupPublish.Completed(
                RemoteBackupArtifact(completed.summary, completed.generation, snapshot)
            )
        }

    private suspend fun inspect(
        client: FirestoreBackupClient,
        user: String
    ): RemoteBackupInspection {
        var inspected: RemoteBackupInspection = RemoteBackupInspection.Absent()
        client.transaction {
            val current = get(user)?.let(::metadata)
            if (current == null) {
                inspected = RemoteBackupInspection.Absent()
                return@transaction
            }
            val generation = current.number("generation")
            val id = current.textOrNull("latestCompleteBackupId")
            if (id == null) {
                inspected = RemoteBackupInspection.Absent(generation)
                return@transaction
            }
            if (id !in registry(current)) fail(BackupFailureReason.InvalidSnapshot)
            val complete = requireNotNull(get("$user/backups/$id"))
            validateManifest(complete)
            if (
                complete.text("state") != "COMPLETE" ||
                    complete.text("backupId") != id ||
                    complete.number("observedRemoteGeneration") != generation - 1 ||
                    complete.text("sourceInstallationId") !=
                        current.text("latestSourceInstallationId") ||
                    timestamp(complete, "completedAt") != timestamp(current, "latestCompletedAt")
            )
                fail(BackupFailureReason.InvalidSnapshot)
            inspected =
                RemoteBackupInspection.Complete(
                    RemoteBackupMetadata(
                        RemoteBackupSummary(
                            id,
                            timestamp(complete, "completedAt"),
                            complete.text("sourceInstallationId"),
                            counts(complete)
                        ),
                        generation,
                        complete.text("contentDigest"),
                        complete.number("capturedLocalRevision")
                    )
                )
        }
        return inspected
    }

    private suspend fun verifyChunks(
        client: FirestoreBackupClient,
        backup: String,
        snapshot: EncodedBackupSnapshot
    ) {
        val chunks =
            snapshot.chunks.map { expected ->
                val stored = requireNotNull(client.get(chunkPath(backup, expected.index)))
                if (stored != chunkFields(snapshot.formatVersion, expected))
                    fail(BackupFailureReason.InvalidSnapshot)
                expected
            }
        codec.decode(snapshot.copy(chunks = chunks))
    }

    private suspend fun retainTwo(client: FirestoreBackupClient, user: String) {
        val current = metadata(requireNotNull(client.get(user)))
        if (current.textOrNull("activeUploadBackupId") != null) return
        for (id in registry(current).dropLast(2)) {
            val backup = "$user/backups/$id"
            // All six known chunk paths cover registered orphans without subcollection discovery.
            for (index in 0 until BackupSnapshotCodec.MAX_CHUNKS) client.transaction {
                val latest = metadata(requireNotNull(get(user)))
                if (
                    latest.textOrNull("activeUploadBackupId") != null ||
                        id == latest.textOrNull("latestCompleteBackupId") ||
                        id !in registry(latest).dropLast(2)
                )
                    fail(BackupFailureReason.ConcurrentRemoteChange)
                val path = chunkPath(backup, index)
                if (get(path) != null) delete(path)
            }
            client.transaction {
                val latest = metadata(requireNotNull(get(user)))
                if (
                    latest.textOrNull("activeUploadBackupId") != null ||
                        id == latest.textOrNull("latestCompleteBackupId") ||
                        id !in registry(latest).dropLast(2)
                )
                    fail(BackupFailureReason.ConcurrentRemoteChange)
                if (get(backup) != null) delete(backup)
                put(
                    user,
                    latest.with(
                        "backupIds" to
                            JsonArray(registry(latest).filter { it != id }.map(::JsonPrimitive))
                    )
                )
            }
        }
    }

    private suspend fun reclaim(
        client: FirestoreBackupClient,
        user: String,
        id: String,
        generation: Long
    ) {
        val backup = "$user/backups/$id"
        for (index in 0 until BackupSnapshotCodec.MAX_CHUNKS) client.transaction {
            val current = metadata(requireNotNull(get(user)))
            requireSlot(current, generation, id)
            val path = chunkPath(backup, index)
            if (get(path) != null) delete(path)
        }
        client.transaction {
            val current = metadata(requireNotNull(get(user)))
            requireSlot(current, generation, id)
            if (get(backup) != null) delete(backup)
            put(
                user,
                current.with(
                    "activeUploadBackupId" to JsonNull,
                    "backupIds" to
                        JsonArray(registry(current).filter { it != id }.map(::JsonPrimitive))
                )
            )
        }
    }

    private fun metadata(fields: JsonObject): JsonObject {
        require(fields.keys == USER_KEYS)
        require(fields.number("generation") >= 0)
        val ids = registry(fields)
        require(ids.size <= 4 && ids.distinct().size == ids.size)
        ids.forEach { require(it.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) }
        fields.textOrNull("activeUploadBackupId")?.let { require(it in ids) }
        val latest = fields.textOrNull("latestCompleteBackupId")
        if (latest == null)
            require(
                fields["latestCompletedAt"] == JsonNull &&
                    fields["latestSourceInstallationId"] == JsonNull
            )
        else
            require(
                latest in ids &&
                    fields.textOrNull("latestSourceInstallationId") != null &&
                    timestamp(fields, "latestCompletedAt") >= 0
            )
        return fields
    }

    private fun validateManifest(fields: JsonObject) {
        if (fields.number("formatVersion") != 1L) fail(BackupFailureReason.UnsupportedVersion)
        require(fields.keys == MANIFEST_KEYS)
        require(fields.text("state") in setOf("UPLOADING", "COMPLETE"))
        require(fields.number("chunkCount") in 1..6)
        require(fields.number("encodedByteCount") in 0..(6L * BackupSnapshotCodec.MAX_CHUNK_BYTES))
        require(
            fields.number("capturedLocalRevision") >= 0 &&
                fields.number("observedRemoteGeneration") >= 0
        )
        require(fields.text("backupId").matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        require(fields.text("appVersion").length in 1..128)
        require(fields.text("contentDigest").matches(Regex("[a-f0-9]{64}")))
        require(fields.text("sourceInstallationId").matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        require(timestamp(fields, "createdAt") >= 0)
        counts(fields)
    }

    private fun resumable(
        fields: JsonObject,
        source: String,
        snapshot: EncodedBackupSnapshot,
        generation: Long
    ): Boolean {
        validateManifest(fields)
        return fields.text("state") == "UPLOADING" &&
            fields["completedAt"] == JsonNull &&
            fields.text("sourceInstallationId") == source &&
            fields.number("observedRemoteGeneration") == generation &&
            fields.text("contentDigest") == snapshot.contentDigest &&
            fields.number("capturedLocalRevision") == snapshot.localChangeRevision &&
            fields.number("chunkCount") == snapshot.chunks.size.toLong() &&
            fields.number("encodedByteCount") == snapshot.encodedByteCount.toLong() &&
            counts(fields) == snapshot.entityCounts
    }

    private fun counts(fields: JsonObject): Map<String, Int> {
        val counts = fields.getValue("entityCounts").jsonObject
        require(counts.keys == ENTITY_TYPES)
        require(counts.values.sumOf { it.jsonPrimitive.long } in 0..Int.MAX_VALUE.toLong())
        return counts.mapValues { (_, value) ->
            value.jsonPrimitive.long.also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()
        }
    }

    private fun requireGeneration(fields: JsonObject, expected: Long) {
        if (fields.number("generation") != expected)
            fail(BackupFailureReason.ConcurrentRemoteChange)
    }

    private fun requireSlot(fields: JsonObject, generation: Long, id: String) {
        requireGeneration(fields, generation)
        if (fields.textOrNull("activeUploadBackupId") != id)
            fail(BackupFailureReason.ConcurrentRemoteChange)
    }

    private fun manifest(
        id: String,
        source: String,
        generation: Long,
        snapshot: EncodedBackupSnapshot
    ) = buildJsonObject {
        put("backupId", id)
        put("formatVersion", snapshot.formatVersion)
        put("appVersion", appVersion)
        put("sourceInstallationId", source)
        put("state", "UPLOADING")
        put("createdAt", JsonNull)
        put("completedAt", JsonNull)
        put("chunkCount", snapshot.chunks.size)
        put("encodedByteCount", snapshot.encodedByteCount)
        put("entityCounts", JsonObject(snapshot.entityCounts.mapValues { JsonPrimitive(it.value) }))
        put("contentDigest", snapshot.contentDigest)
        put("capturedLocalRevision", snapshot.localChangeRevision)
        put("observedRemoteGeneration", generation)
    }

    private fun chunkFields(formatVersion: Int, chunk: BackupChunk) = buildJsonObject {
        put("formatVersion", formatVersion)
        put("chunkIndex", chunk.index)
        put("encodedByteCount", chunk.encodedByteCount)
        put("chunkDigest", chunk.digest)
        put("payload", chunk.payload)
    }

    private fun emptyMetadata() = buildJsonObject {
        put("generation", 0)
        put("backupIds", JsonArray(emptyList()))
        put("activeUploadBackupId", JsonNull)
        put("latestCompleteBackupId", JsonNull)
        put("latestCompletedAt", JsonNull)
        put("latestSourceInstallationId", JsonNull)
    }

    private fun registry(fields: JsonObject) =
        fields.getValue("backupIds").jsonArray.map { it.jsonPrimitive.content }

    private fun user(account: AccountId): String {
        require(account.opaqueValue.matches(Regex("[a-zA-Z0-9_-]{1,128}")))
        return "users/${account.opaqueValue}"
    }

    private fun chunkPath(backup: String, index: Int) =
        "$backup/chunks/${index.toString().padStart(3, '0')}"

    private fun timestamp(fields: JsonObject, key: String) =
        Instant.parse(fields.text(key)).toEpochMilli()

    private fun JsonObject.text(key: String) =
        getValue(key).jsonPrimitive.also { require(it.isString) }.content

    private fun JsonObject.textOrNull(key: String) =
        if (getValue(key) == JsonNull) null else text(key)

    private fun JsonObject.number(key: String) =
        getValue(key).jsonPrimitive.also { require(!it.isString) }.long

    private fun JsonObject.with(vararg fields: Pair<String, JsonElement>) =
        JsonObject(this + fields)

    private fun fail(reason: BackupFailureReason): Nothing = throw CloudBackupFailure(reason)

    private suspend fun <T> safely(
        failure: (BackupFailureReason) -> T,
        operation: suspend () -> T
    ): T =
        try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure((error as? CloudBackupFailure)?.reason ?: BackupFailureReason.InvalidSnapshot)
        }

    private companion object {
        const val LEASE_MILLIS = 24L * 60 * 60 * 1000
        val ENTITY_TYPES =
            setOf(
                "WeeklyPlan",
                "PlannedWorkout",
                "PlannedExercise",
                "WorkoutLog",
                "LoggedExercise",
                "LoggedSet",
                "PersonalRecord"
            )
        val USER_KEYS =
            setOf(
                "generation",
                "backupIds",
                "activeUploadBackupId",
                "latestCompleteBackupId",
                "latestCompletedAt",
                "latestSourceInstallationId"
            )
        val MANIFEST_KEYS =
            setOf(
                "backupId",
                "formatVersion",
                "appVersion",
                "sourceInstallationId",
                "state",
                "createdAt",
                "completedAt",
                "chunkCount",
                "encodedByteCount",
                "entityCounts",
                "contentDigest",
                "capturedLocalRevision",
                "observedRemoteGeneration"
            )
    }
}
