package com.example.ironpath.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Durable barrier and resume point for one destructive account-deletion operation. */
@Entity(tableName = "account_deletion_journal")
data class AccountDeletionJournal(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val operationId: String,
    val accountId: String,
    val sessionEpoch: Long,
    val profileGeneration: Long,
    val stage: String,
    val createdAtEpochMillis: Long,
    val expectedLocalOwnerUid: String? = accountId,
    /** Non-secret project and endpoint fingerprint; null identifies a legacy demo operation. */
    val serviceBinding: String? = null,
    val receiptSecret: String? = null,
    val subjectBinding: String? = null,
    @ColumnInfo(defaultValue = "0") val receiptVersion: Long = 0,
    val remoteState: String? = null,
    val installationId: String? = null,
) {
    override fun toString() = "AccountDeletionJournal(stage=$stage, receipt=<redacted>)"

    companion object {
        const val SINGLETON_ID = 1
    }
}
