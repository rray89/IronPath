package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Bounded chunk rows avoid placing a multi-megabyte shared baseline in one CursorWindow row. */
@Entity(tableName = "backup_baseline_chunks")
data class BackupBaselineChunk(
    @PrimaryKey val chunkIndex: Int,
    val ownerUid: String,
    val installationId: String,
    val backupId: String,
    val remoteGeneration: Long,
    val completedAt: Long,
    val sourceInstallationId: String,
    val formatVersion: Int,
    val capturedRevision: Long,
    val entityCountsJson: String,
    val snapshotByteCount: Int,
    val snapshotDigest: String,
    val payload: String,
    val chunkByteCount: Int,
    val chunkDigest: String,
)
