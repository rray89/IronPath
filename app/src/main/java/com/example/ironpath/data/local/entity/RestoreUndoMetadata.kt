package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** The one durable undo capability created by the most recent successful restore. */
@Entity(tableName = "restore_undo_metadata")
data class RestoreUndoMetadata(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val slotIdentity: String,
    val restoringOwnerUid: String,
    val restoringInstallationId: String,
    val previousOwnerUid: String?,
    val previousInstallationId: String,
    val previousLocalChangeRevision: Long,
    val previousLastCompleteLocalRevision: Long,
    val previousLastObservedRemoteBackupId: String?,
    val previousLastObservedRemoteGeneration: Long,
    val previousLastObservedRemoteDigest: String?,
    val previousLastObservedSourceInstallationId: String?,
    val previousLastObservedRemoteCompletedAt: Long?,
    val snapshotFormatVersion: Int,
    val snapshotRevision: Long,
    val snapshotEntityCountsJson: String,
    val snapshotByteCount: Int,
    val snapshotDigest: String,
    val baselineBackupId: String?,
    val baselineGeneration: Long?,
    val baselineCompletedAt: Long?,
    val baselineSourceInstallationId: String?,
    val baselineFormatVersion: Int?,
    val baselineRevision: Long?,
    val baselineEntityCountsJson: String?,
    val baselineByteCount: Int?,
    val baselineDigest: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
