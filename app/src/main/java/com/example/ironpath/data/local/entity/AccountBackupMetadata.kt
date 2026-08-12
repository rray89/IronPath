package com.example.ironpath.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "account_backup_metadata")
data class AccountBackupMetadata(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val ownerUid: String? = null,
    val installationId: String,
    val localChangeRevision: Long = 0,
    val lastCompleteLocalRevision: Long = 0,
    val lastObservedRemoteBackupId: String? = null,
    val lastObservedRemoteGeneration: Long = 0,
    val lastObservedRemoteDigest: String? = null,
    val lastObservedSourceInstallationId: String? = null,
    val lastObservedRemoteCompletedAt: Long? = null,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
