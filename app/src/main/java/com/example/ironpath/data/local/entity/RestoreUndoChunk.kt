package com.example.ironpath.data.local.entity

import androidx.room.Entity

/** Individual bounded payload rows for the durable undo snapshot and its previous baseline. */
@Entity(
    tableName = "restore_undo_chunks",
    primaryKeys = ["kind", "chunkIndex"],
)
data class RestoreUndoChunk(
    val kind: String,
    val chunkIndex: Int,
    val payload: String,
    val payloadByteCount: Int,
    val payloadDigest: String,
) {
    companion object {
        const val LOCAL_SNAPSHOT = "local"
        const val REMOTE_BASELINE = "baseline"
    }
}
