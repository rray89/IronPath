package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.BackupBaselineChunk
import com.example.ironpath.domain.backup.RemoteBackupSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun RemoteBackupArtifact.validatedBundle(): BackupBundle {
    require(generation > 0)
    require(summary.backupId.isNotBlank() && summary.sourceInstallationId.isNotBlank())
    require(summary.completedAtEpochMillis >= 0)
    require(summary.entityCounts == snapshot.entityCounts)
    return BackupSnapshotCodec().decode(snapshot)
}

internal object BackupBaselineCodec {
    fun encode(
        backup: RemoteBackupArtifact,
        ownerUid: String,
        installationId: String
    ): List<BackupBaselineChunk> {
        backup.validatedBundle()
        val counts =
            JsonObject(backup.snapshot.entityCounts.mapValues { JsonPrimitive(it.value) })
                .toString()
        return backup.snapshot.chunks.map { chunk ->
            BackupBaselineChunk(
                chunk.index,
                ownerUid,
                installationId,
                backup.summary.backupId,
                backup.generation,
                backup.summary.completedAtEpochMillis,
                backup.summary.sourceInstallationId,
                backup.snapshot.formatVersion,
                backup.snapshot.localChangeRevision,
                counts,
                backup.snapshot.encodedByteCount,
                backup.snapshot.contentDigest,
                chunk.payload,
                chunk.encodedByteCount,
                chunk.digest
            )
        }
    }

    fun decode(
        rows: List<BackupBaselineChunk>,
        metadata: AccountBackupMetadata
    ): RemoteBackupArtifact? {
        if (rows.isEmpty()) return null
        val first = rows.first()
        require(
            first.ownerUid == metadata.ownerUid && first.installationId == metadata.installationId
        )
        require(first.remoteGeneration == metadata.lastObservedRemoteGeneration)
        require(first.backupId == metadata.lastObservedRemoteBackupId)
        require(first.snapshotDigest == metadata.lastObservedRemoteDigest)
        rows.forEach { row ->
            require(
                row.copy(
                    chunkIndex = first.chunkIndex,
                    payload = first.payload,
                    chunkByteCount = first.chunkByteCount,
                    chunkDigest = first.chunkDigest
                ) == first
            )
        }
        val counts =
            Json.parseToJsonElement(first.entityCountsJson).jsonObject.mapValues {
                it.value.jsonPrimitive.int
            }
        val snapshot =
            EncodedBackupSnapshot(
                first.formatVersion,
                first.capturedRevision,
                rows.map {
                    BackupChunk(it.chunkIndex, it.payload, it.chunkByteCount, it.chunkDigest)
                },
                counts,
                first.snapshotByteCount,
                first.snapshotDigest
            )
        return RemoteBackupArtifact(
                RemoteBackupSummary(
                    first.backupId,
                    first.completedAt,
                    first.sourceInstallationId,
                    counts
                ),
                first.remoteGeneration,
                snapshot
            )
            .also { it.validatedBundle() }
    }
}
