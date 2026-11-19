package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Non-blocking reservation identity, in the same excluded database as the blocking journal. */
@Entity(tableName = "account_deletion_draft")
data class AccountDeletionDraftEntity(
    @PrimaryKey val id: Int = 1,
    val operationId: String,
    val receiptSecret: String,
    val accountId: String,
    val sessionEpoch: Long,
    val profileGeneration: Long,
    val expectedLocalOwnerUid: String?,
    val serviceBinding: String,
    val installationId: String,
    val createdAtEpochMillis: Long,
) {
    override fun toString() = "AccountDeletionDraftEntity(receipt=<redacted>)"
}
