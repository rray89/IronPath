package com.example.ironpath.data.local.entity

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
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
