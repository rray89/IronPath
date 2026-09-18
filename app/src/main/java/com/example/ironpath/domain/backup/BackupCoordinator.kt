package com.example.ironpath.domain.backup

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface BackupCoordinator {
    val status: StateFlow<BackupStatus>

    val latestSummary: StateFlow<RemoteBackupSummary?>
        get() = MutableStateFlow(null)

    suspend fun refreshStatus() {}

    suspend fun discardPreview(previewId: String) {}

    suspend fun previewBackup(): BackupPreviewResult = BackupPreviewResult.Unavailable

    suspend fun confirmBackup(
        previewId: String,
        destructiveConfirmed: Boolean = false
    ): BackupActionResult = BackupActionResult.Unavailable

    suspend fun previewSync(): SyncPreviewResult = SyncPreviewResult.Unavailable

    suspend fun confirmSync(
        previewId: String,
        resolution: SyncConflictResolution? = null
    ): BackupActionResult = BackupActionResult.Unavailable

    suspend fun backUpNow(): BackupActionResult

    suspend fun latestCompleteBackup(): BackupLookupResult

    suspend fun restore(request: RestoreRequest): BackupActionResult

    suspend fun deleteAllRemoteData(): BackupActionResult
}

sealed interface BackupStatus {
    data object LocalOnly : BackupStatus

    data object SignedInNoBackup : BackupStatus

    data object LocalChanges : BackupStatus

    data object ReviewRequired : BackupStatus

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
    StalePreview,
    OwnershipMismatch,
    ActiveSessionPresent,
    ConflictChoiceRequired,
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

data class BackupPreview(
    val id: String,
    val localRevision: Long,
    val remoteGeneration: Long,
    val entityCounts: Map<String, Int>,
    val previousEntityCount: Int,
    val requiresDestructiveConfirmation: Boolean,
    val associationOnly: Boolean,
)

data class SyncPreview(
    val id: String,
    val localRevision: Long,
    val remoteGeneration: Long,
    val localChanges: Map<String, Int>,
    val cloudChanges: Map<String, Int>,
    val conflicts: Map<String, Int>,
    val canKeepLocal: Boolean = true,
    val canKeepCloud: Boolean = true,
)

enum class SyncConflictResolution {
    KeepLocal,
    KeepCloud
}

sealed interface BackupPreviewResult {
    data class Ready(val preview: BackupPreview) : BackupPreviewResult

    data object Unavailable : BackupPreviewResult

    data class Failed(val reason: BackupFailureReason) : BackupPreviewResult
}

sealed interface SyncPreviewResult {
    data class Ready(val preview: SyncPreview) : SyncPreviewResult

    data object Unavailable : SyncPreviewResult

    data class Failed(val reason: BackupFailureReason) : SyncPreviewResult
}

sealed interface BackupActionResult {
    data object Completed : BackupActionResult

    data object Unavailable : BackupActionResult

    data object Cancelled : BackupActionResult

    data class ActiveSessionRequiresConfirmation(val activeSessionId: String) : BackupActionResult

    data class Failed(val reason: BackupFailureReason) : BackupActionResult
}
