package com.example.ironpath.data.backup

import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.backup.RemoteBackupSummary

/** The remote implementation owns upload staging, generation CAS, and bounded retention. */
interface RemoteBackupStore {
    suspend fun latest(accountId: AccountId): RemoteBackupRead

    /** Metadata-only status inspection. Live adapters override this without fetching payloads. */
    suspend fun inspect(accountId: AccountId): RemoteBackupInspection =
        when (val read = latest(accountId)) {
            is RemoteBackupRead.Absent -> RemoteBackupInspection.Absent(read.generation)
            is RemoteBackupRead.Complete ->
                RemoteBackupInspection.Complete(RemoteBackupMetadata.from(read.backup))
            is RemoteBackupRead.Failed -> RemoteBackupInspection.Failed(read.reason)
        }

    /** Explicit confirmed unchanged-data retry may repair bounded retention without reuploading. */
    suspend fun retryRetention(
        accountId: AccountId,
        expectedGeneration: Long,
        latestBackupId: String
    ): RemoteBackupRetention = RemoteBackupRetention.Completed

    /** Permanently fences this account incarnation and removes all of its backup state. */
    suspend fun purgeAccount(accountId: AccountId): RemoteAccountPurge =
        RemoteAccountPurge.Unavailable

    suspend fun publish(
        accountId: AccountId,
        expectedGeneration: Long,
        sourceInstallationId: String,
        snapshot: EncodedBackupSnapshot,
    ): RemoteBackupPublish
}

sealed interface RemoteAccountPurge {
    data object Completed : RemoteAccountPurge

    data object Unavailable : RemoteAccountPurge

    data class Failed(val reason: BackupFailureReason) : RemoteAccountPurge
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

/** Inspection is an observation, never an acknowledgement or shared Room baseline. */
data class RemoteBackupMetadata(
    val summary: RemoteBackupSummary,
    val generation: Long,
    val contentDigest: String,
    val capturedLocalRevision: Long,
) {
    companion object {
        fun from(artifact: RemoteBackupArtifact) =
            RemoteBackupMetadata(
                artifact.summary,
                artifact.generation,
                artifact.snapshot.contentDigest,
                artifact.snapshot.localChangeRevision,
            )
    }
}

sealed interface RemoteBackupInspection {
    data class Absent(val generation: Long = 0) : RemoteBackupInspection

    data class Complete(val backup: RemoteBackupMetadata) : RemoteBackupInspection

    data class Failed(val reason: BackupFailureReason) : RemoteBackupInspection
}

internal class CloudBackupFailure(val reason: BackupFailureReason) : Exception()

sealed interface RemoteBackupRetention {
    data object Completed : RemoteBackupRetention

    data class Failed(val reason: BackupFailureReason) : RemoteBackupRetention
}
