package com.example.ironpath.data.backup

import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.domain.account.AccountId

data class ManualBackupCapture(
    val metadata: AccountBackupMetadata,
    val bundle: BackupBundle,
    val baseline: RemoteBackupArtifact?,
    val activeSessionId: String?,
)

interface ManualBackupLocalStore {
    suspend fun capture(): ManualBackupCapture

    suspend fun associateEmpty(captured: ManualBackupCapture, accountId: AccountId): Boolean

    suspend fun recordBackup(
        captured: ManualBackupCapture,
        accountId: AccountId,
        backup: RemoteBackupArtifact
    ): Boolean

    suspend fun applySync(
        captured: ManualBackupCapture,
        accountId: AccountId,
        backup: RemoteBackupArtifact
    ): Boolean
}
