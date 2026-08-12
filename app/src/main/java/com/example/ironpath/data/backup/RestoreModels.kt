package com.example.ironpath.data.backup

data class RestoreLineage(
    val ownerUid: String,
    val remoteBackupId: String,
    val remoteGeneration: Long,
    val remoteDigest: String,
    val sourceInstallationId: String,
    val completedAt: Long,
)

class ValidatedRestoreArtifact
internal constructor(
    val bundle: BackupBundle,
    val lineage: RestoreLineage,
    val contentDigest: String,
    val nulledProvenanceFields: Set<String>,
) {
    init {
        require(contentDigest.isNotBlank())
        require(lineage.ownerUid.isNotBlank())
        require(lineage.remoteBackupId.isNotBlank())
        require(lineage.remoteGeneration >= 0)
        require(lineage.remoteDigest == contentDigest) {
            "Restore lineage digest does not match the validated snapshot"
        }
        require(lineage.sourceInstallationId.isNotBlank())
        require(lineage.completedAt >= 0)
    }
}

sealed interface ActiveSessionRestoreDisposition {
    data object Preserve : ActiveSessionRestoreDisposition

    data class Discard(val confirmedSessionId: String) : ActiveSessionRestoreDisposition
}

sealed interface RestoreResult {
    data class ActiveSessionRequiresConfirmation(val activeSessionId: String) : RestoreResult

    data class InvalidSnapshot(val reason: String) : RestoreResult

    data class Success(val nulledProvenanceFields: Set<String>) : RestoreResult
}
