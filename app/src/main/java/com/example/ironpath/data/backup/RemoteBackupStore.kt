package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.backup.RemoteBackupSummary

/** The remote implementation owns upload staging, generation CAS, and bounded retention. */
interface RemoteBackupStore {
    suspend fun latest(accountId: AccountId): RemoteBackupRead

    suspend fun publish(
        accountId: AccountId,
        expectedGeneration: Long,
        sourceInstallationId: String,
        snapshot: EncodedBackupSnapshot,
    ): RemoteBackupPublish
}

data class RemoteBackupArtifact(
    val summary: RemoteBackupSummary,
    val generation: Long,
    val snapshot: EncodedBackupSnapshot,
)

sealed interface RemoteBackupRead {
    data class Absent(val generation: Long = 0) : RemoteBackupRead

    data class Complete(val backup: RemoteBackupArtifact) : RemoteBackupRead

    data class Failed(val reason: BackupFailureReason) : RemoteBackupRead
}

sealed interface RemoteBackupPublish {
    data class Completed(val backup: RemoteBackupArtifact) : RemoteBackupPublish

    data class Failed(val reason: BackupFailureReason) : RemoteBackupPublish
}
