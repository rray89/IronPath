package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.backup.RemoteBackupSummary
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Debug-only, local deterministic remote protocol. No credentials or network access. */
@Singleton
class DeterministicRemoteBackupStore
internal constructor(
    private val directory: File,
    private val idProvider: IdProvider,
    private val timeProvider: TimeProvider,
    private val afterWrite: (DebugRemoteWritePhase) -> Unit,
) : RemoteBackupStore {
    @Inject
    constructor(
        @DebugBackupDirectory directory: File,
        idProvider: IdProvider,
        timeProvider: TimeProvider,
    ) : this(directory, idProvider, timeProvider, {})

    override suspend fun latest(accountId: AccountId): RemoteBackupRead =
        withContext(Dispatchers.IO) {
            try {
                if (!directory.exists()) return@withContext RemoteBackupRead.Absent()
                locked(accountId) { file ->
                    val state = read(file)
                    state.latestId?.let { RemoteBackupRead.Complete(artifact(state, it)) }
                        ?: RemoteBackupRead.Absent(state.generation)
                }
            } catch (failure: Exception) {
                RemoteBackupRead.Failed(reason(failure))
            }
        }

    override suspend fun publish(
        accountId: AccountId,
        expectedGeneration: Long,
        sourceInstallationId: String,
        snapshot: EncodedBackupSnapshot,
    ): RemoteBackupPublish =
        withContext(Dispatchers.IO) {
            try {
                val captured = immutable(snapshot)
                validateSnapshot(captured)
                require(expectedGeneration >= 0)
                require(sourceInstallationId.isNotBlank() && sourceInstallationId.length <= 128)
                locked(accountId) { file ->
                    publish(file, expectedGeneration, sourceInstallationId, captured)
                }
            } catch (failure: Exception) {
                RemoteBackupPublish.Failed(reason(failure))
            }
        }

    private fun publish(
        file: File,
        expectedGeneration: Long,
        source: String,
        snapshot: EncodedBackupSnapshot,
    ): RemoteBackupPublish {
        var state = read(file)
        if (state.generation != expectedGeneration || state.generation == Long.MAX_VALUE) {
            return RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange)
        }
        val active = state.activeId?.let(state.manifests::get)
        val canResume =
            active != null &&
                active.source == source &&
                active.observedGeneration == expectedGeneration &&
                active.snapshot.copy(chunks = emptyList()) == snapshot.copy(chunks = emptyList()) &&
                active.snapshot.chunks == snapshot.chunks.take(active.snapshot.chunks.size)
        if (state.activeId != null && !canResume) {
            if (
                active != null &&
                    timeProvider.epochMillis() - active.createdAt < UPLOAD_LEASE_MILLIS
            ) {
                return RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange)
            }
            state = remove(file, state, checkNotNull(state.activeId))
        }
        if (state.activeId == null) {
            state = retainTwo(file, state)
            val id = idProvider.newId()
            require(id.isNotBlank() && id.length <= 128 && id !in state.backupIds)
            val createdAt = timeProvider.epochMillis()
            require(createdAt >= 0)
            val claimed =
                Manifest(
                    source,
                    createdAt,
                    null,
                    expectedGeneration,
                    snapshot.chunks.size,
                    snapshot.copy(chunks = emptyList())
                )
            state =
                state.copy(
                    activeId = id,
                    backupIds = state.backupIds + id,
                    manifests = state.manifests + (id to claimed)
                )
            save(file, state, DebugRemoteWritePhase.Claimed)
        }
        val id = checkNotNull(state.activeId)
        var manifest = state.manifests.getValue(id)
        for (chunk in snapshot.chunks.drop(manifest.snapshot.chunks.size)) {
            manifest =
                manifest.copy(
                    snapshot = manifest.snapshot.copy(chunks = manifest.snapshot.chunks + chunk)
                )
            state = state.copy(manifests = state.manifests + (id to manifest))
            save(file, state, DebugRemoteWritePhase.ChunkWritten)
        }
        validateSnapshot(manifest.snapshot)
        check(manifest.snapshot.chunks.size == manifest.chunkCount)
        // Recheck the persisted CAS and slot immediately before exposing COMPLETE.
        val current = read(file)
        if (current.generation != expectedGeneration || current.activeId != id) {
            return RemoteBackupPublish.Failed(BackupFailureReason.ConcurrentRemoteChange)
        }
        manifest =
            manifest.copy(completedAt = maxOf(timeProvider.epochMillis(), manifest.createdAt))
        state =
            state.copy(
                generation = expectedGeneration + 1,
                latestId = id,
                activeId = null,
                manifests = state.manifests + (id to manifest)
            )
        save(file, state, DebugRemoteWritePhase.Completed)
        val completed = artifact(state, id)
        // COMPLETE is durable already. Cleanup failure leaves the ordered work list for explicit
        // retry.
        try {
            retainTwo(file, state)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
        }
        return RemoteBackupPublish.Completed(completed)
    }

    private fun retainTwo(file: File, original: State): State {
        var state = original
        val completeIds = state.backupIds.filter { state.manifests[it]?.completedAt != null }
        val retained = completeIds.takeLast(2).toSet()
        for (id in state.backupIds) {
            if (id != state.activeId && id != state.latestId && id !in retained) {
                state = remove(file, state, id)
            }
        }
        return state
    }

    private fun remove(file: File, original: State, id: String): State {
        check(id != original.latestId)
        var state = original
        state.manifests[id]?.let { manifest ->
            state =
                state.copy(
                    manifests =
                        state.manifests +
                            (id to
                                manifest.copy(
                                    snapshot = manifest.snapshot.copy(chunks = emptyList())
                                ))
                )
            save(file, state, DebugRemoteWritePhase.RetentionChunksDeleted)
            state = state.copy(manifests = state.manifests - id)
            save(file, state, DebugRemoteWritePhase.RetentionManifestDeleted)
        }
        state =
            state.copy(
                activeId = state.activeId?.takeUnless { it == id },
                backupIds = state.backupIds - id
            )
        save(file, state, DebugRemoteWritePhase.RetentionRegistryUpdated)
        return state
    }

    private suspend fun <T> locked(accountId: AccountId, action: (File) -> T): T =
        processMutex.withLock {
            check(directory.isDirectory || directory.mkdirs())
            val name = digest(accountId.opaqueValue)
            FileChannel.open(
                    File(directory, "$name.lock").toPath(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE
                )
                .use { channel -> channel.lock().use { action(File(directory, "$name.json")) } }
        }

    private fun read(file: File): State {
        if (!file.exists()) return State()
        try {
            require(file.isFile && file.length() <= MAX_STATE_BYTES)
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            if (root.int("version") != 1)
                throw ProtocolFailure(BackupFailureReason.UnsupportedVersion)
            return State(
                    root.long("generation"),
                    root.nullableString("latestId"),
                    root.nullableString("activeId"),
                    root.getValue("backupIds").jsonArray.map { it.jsonPrimitive.content },
                    root.getValue("manifests").jsonObject.mapValues { (_, value) ->
                        val manifest = value.jsonObject
                        Manifest(
                            manifest.string("source"),
                            manifest.long("createdAt"),
                            manifest.nullableLong("completedAt"),
                            manifest.long("observedGeneration"),
                            manifest.int("chunkCount"),
                            decodeSnapshot(manifest.getValue("snapshot").jsonObject),
                        )
                    },
                )
                .also(::validate)
        } catch (failure: ProtocolFailure) {
            throw failure
        } catch (failure: java.io.IOException) {
            throw failure
        } catch (_: Exception) {
            throw ProtocolFailure(BackupFailureReason.InvalidSnapshot)
        }
    }

    private fun validate(state: State) {
        require(state.generation >= 0 && state.backupIds.size <= 4)
        require(state.backupIds.distinct() == state.backupIds)
        require(state.backupIds.all { it.isNotBlank() && it.length <= 128 })
        require(state.manifests.keys.all { it in state.backupIds })
        require(state.activeId == null || state.activeId in state.backupIds)
        require(
            state.manifests
                .filterValues { it.completedAt == null }
                .keys
                .all { it == state.activeId }
        )
        require(state.manifests.values.count { it.completedAt != null } <= 3)
        state.manifests.forEach { (id, manifest) ->
            require(manifest.source.isNotBlank() && manifest.source.length <= 128)
            require(manifest.createdAt >= 0 && manifest.observedGeneration in 0..state.generation)
            require(manifest.completedAt == null || manifest.completedAt >= manifest.createdAt)
            require(manifest.chunkCount in 1..BackupSnapshotCodec.MAX_CHUNKS)
            val snapshot = manifest.snapshot
            require(
                snapshot.formatVersion == BackupSnapshotCodec.FORMAT_VERSION &&
                    snapshot.localChangeRevision >= 0
            )
            require(snapshot.chunks.size <= manifest.chunkCount)
            require(snapshot.chunks.map { it.index } == snapshot.chunks.indices.toList())
            require(
                snapshot.encodedByteCount in 0..MAX_SNAPSHOT_BYTES &&
                    snapshot.contentDigest.matches(DIGEST)
            )
            require(
                snapshot.entityCounts.keys == ENTITY_TYPES &&
                    snapshot.entityCounts.values.all { it >= 0 }
            )
            snapshot.chunks.forEach { chunk ->
                require(chunk.encodedByteCount in 0..BackupSnapshotCodec.MAX_CHUNK_BYTES)
                require(
                    chunk.payload.toByteArray(Charsets.UTF_8).size == chunk.encodedByteCount &&
                        digest(chunk.payload) == chunk.digest
                )
            }
            if (snapshot.chunks.size == manifest.chunkCount) validateSnapshot(snapshot)
            if (manifest.completedAt != null)
                require(snapshot.chunks.isEmpty() || snapshot.chunks.size == manifest.chunkCount)
            if (id == state.activeId)
                require(
                    manifest.completedAt == null && manifest.observedGeneration == state.generation
                )
        }
        if (state.latestId == null) {
            require(state.generation == 0L)
        } else {
            require(state.latestId in state.backupIds && state.latestId != state.activeId)
            val latest = state.manifests.getValue(state.latestId)
            require(latest.completedAt != null && latest.observedGeneration == state.generation - 1)
            require(latest.snapshot.chunks.size == latest.chunkCount)
            validateSnapshot(latest.snapshot)
        }
    }

    private fun validateSnapshot(snapshot: EncodedBackupSnapshot) {
        if (snapshot.formatVersion != BackupSnapshotCodec.FORMAT_VERSION)
            throw ProtocolFailure(BackupFailureReason.UnsupportedVersion)
        try {
            BackupSnapshotCodec().decode(snapshot)
        } catch (_: IllegalArgumentException) {
            throw ProtocolFailure(BackupFailureReason.InvalidSnapshot)
        }
    }

    private fun save(file: File, state: State, phase: DebugRemoteWritePhase) {
        val encoded = encode(state).toString().toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_STATE_BYTES)
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temporary.exists()) check(temporary.delete())
        }
        afterWrite(phase)
    }

    private fun artifact(state: State, id: String): RemoteBackupArtifact {
        val manifest = state.manifests.getValue(id)
        val snapshot = immutable(manifest.snapshot)
        return RemoteBackupArtifact(
            RemoteBackupSummary(
                id,
                checkNotNull(manifest.completedAt),
                manifest.source,
                snapshot.entityCounts
            ),
            state.generation,
            snapshot
        )
    }

    private fun immutable(snapshot: EncodedBackupSnapshot) =
        snapshot.copy(
            chunks = Collections.unmodifiableList(snapshot.chunks.toList()),
            entityCounts = Collections.unmodifiableMap(snapshot.entityCounts.toMap())
        )

    private fun encode(state: State) = buildJsonObject {
        put("version", 1)
        put("generation", state.generation)
        put("latestId", state.latestId?.let(::JsonPrimitive) ?: JsonNull)
        put("activeId", state.activeId?.let(::JsonPrimitive) ?: JsonNull)
        put("backupIds", JsonArray(state.backupIds.map(::JsonPrimitive)))
        put(
            "manifests",
            JsonObject(
                state.manifests.mapValues { (_, manifest) ->
                    buildJsonObject {
                        put("source", manifest.source)
                        put("createdAt", manifest.createdAt)
                        put("completedAt", manifest.completedAt?.let(::JsonPrimitive) ?: JsonNull)
                        put("observedGeneration", manifest.observedGeneration)
                        put("chunkCount", manifest.chunkCount)
                        put("snapshot", encodeSnapshot(manifest.snapshot))
                    }
                }
            )
        )
    }

    private fun encodeSnapshot(snapshot: EncodedBackupSnapshot) = buildJsonObject {
        put("formatVersion", snapshot.formatVersion)
        put("localChangeRevision", snapshot.localChangeRevision)
        put("encodedByteCount", snapshot.encodedByteCount)
        put("contentDigest", snapshot.contentDigest)
        put("entityCounts", JsonObject(snapshot.entityCounts.mapValues { JsonPrimitive(it.value) }))
        put(
            "chunks",
            JsonArray(
                snapshot.chunks.map { chunk ->
                    buildJsonObject {
                        put("index", chunk.index)
                        put("payload", chunk.payload)
                        put("encodedByteCount", chunk.encodedByteCount)
                        put("digest", chunk.digest)
                    }
                }
            )
        )
    }

    private fun decodeSnapshot(root: JsonObject) =
        EncodedBackupSnapshot(
            root.int("formatVersion"),
            root.long("localChangeRevision"),
            root.getValue("chunks").jsonArray.map { value ->
                val chunk = value.jsonObject
                BackupChunk(
                    chunk.int("index"),
                    chunk.string("payload"),
                    chunk.int("encodedByteCount"),
                    chunk.string("digest")
                )
            },
            root.getValue("entityCounts").jsonObject.mapValues { it.value.jsonPrimitive.int },
            root.int("encodedByteCount"),
            root.string("contentDigest"),
        )

    private fun reason(failure: Exception): BackupFailureReason =
        when (failure) {
            is CancellationException -> throw failure
            is ProtocolFailure -> failure.reason
            is IllegalArgumentException -> BackupFailureReason.InvalidSnapshot
            else -> BackupFailureReason.ServiceUnavailable
        }

    private fun JsonObject.string(name: String) =
        getValue(name).jsonPrimitive.also { require(it.isString) }.content

    private fun JsonObject.nullableString(name: String) =
        if (getValue(name) == JsonNull) null else string(name)

    private fun JsonObject.long(name: String) =
        getValue(name).jsonPrimitive.also { require(!it.isString) }.long

    private fun JsonObject.int(name: String) =
        getValue(name).jsonPrimitive.also { require(!it.isString) }.int

    private fun JsonObject.nullableLong(name: String) =
        if (getValue(name) == JsonNull) null else long(name)

    private data class State(
        val generation: Long = 0,
        val latestId: String? = null,
        val activeId: String? = null,
        val backupIds: List<String> = emptyList(),
        val manifests: Map<String, Manifest> = emptyMap(),
    )

    private data class Manifest(
        val source: String,
        val createdAt: Long,
        val completedAt: Long?,
        val observedGeneration: Long,
        val chunkCount: Int,
        val snapshot: EncodedBackupSnapshot,
    )

    private class ProtocolFailure(val reason: BackupFailureReason) : IllegalArgumentException()

    private companion object {
        val processMutex = Mutex()
        const val UPLOAD_LEASE_MILLIS = 24 * 60 * 60 * 1_000L
        const val MAX_SNAPSHOT_BYTES =
            BackupSnapshotCodec.MAX_CHUNK_BYTES * BackupSnapshotCodec.MAX_CHUNKS
        const val MAX_STATE_BYTES = 40 * 1024 * 1024L
        val DIGEST = Regex("[a-f0-9]{64}")
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

        fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

internal enum class DebugRemoteWritePhase {
    Claimed,
    ChunkWritten,
    Completed,
    RetentionChunksDeleted,
    RetentionManifestDeleted,
    RetentionRegistryUpdated,
}
