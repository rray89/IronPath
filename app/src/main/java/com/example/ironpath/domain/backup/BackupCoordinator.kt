package com.example.ironpath.domain.backup

import kotlinx.coroutines.flow.StateFlow

interface BackupCoordinator {
    val status: StateFlow<BackupStatus>

    suspend fun backUpNow(): BackupActionResult

    suspend fun latestCompleteBackup(): BackupLookupResult

    suspend fun restore(request: RestoreRequest): BackupActionResult

    suspend fun deleteAllRemoteData(): BackupActionResult
}

sealed interface BackupStatus {
    data object LocalOnly : BackupStatus

    data object Preparing : BackupStatus

    data object BackingUp : BackupStatus

    data class UpToDate(val completedAtEpochMillis: Long) : BackupStatus

    data object OfflinePending : BackupStatus

    data object QuotaPaused : BackupStatus

    data object NeedsSignIn : BackupStatus

    data class NeedsAttention(val reason: BackupFailureReason) : BackupStatus
}

data class RemoteBackupSummary(
    val backupId: String,
    val completedAtEpochMillis: Long,
    val sourceInstallationId: String,
    val entityCounts: Map<String, Int>,
)

sealed interface BackupLookupResult {
    data class Complete(val summary: RemoteBackupSummary) : BackupLookupResult

    data object Absent : BackupLookupResult

    data object Unavailable : BackupLookupResult

    data class Failed(val reason: BackupFailureReason) : BackupLookupResult
}

data class RestoreRequest(
    val backupId: String,
    val activeSessionDisposition: ActiveSessionDisposition = ActiveSessionDisposition.Preserve,
)

sealed interface ActiveSessionDisposition {
    data object Preserve : ActiveSessionDisposition

    data class Discard(val confirmedSessionId: String) : ActiveSessionDisposition
}

enum class BackupFailureReason {
    InvalidSnapshot,
    ConcurrentRemoteChange,
    DestructiveLocalChange,
    Offline,
    QuotaOrRateLimited,
    PermissionDenied,
    UnsupportedVersion,
    ReauthenticationRequired,
    ServiceUnavailable,
    Unknown,
}

sealed interface BackupActionResult {
    data object Completed : BackupActionResult

    data object Unavailable : BackupActionResult

    data object Cancelled : BackupActionResult

    data class ActiveSessionRequiresConfirmation(val activeSessionId: String) : BackupActionResult

    data class Failed(val reason: BackupFailureReason) : BackupActionResult
}
