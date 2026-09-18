package com.example.ironpath.ui.screens.accountbackup

import com.example.ironpath.domain.backup.*

sealed interface ManualReview {
    val id: String

    data class Backup(val preview: BackupPreview) : ManualReview {
        override val id = preview.id
    }

    data class Sync(val preview: SyncPreview) : ManualReview {
        override val id = preview.id
    }
}

data class ManualBackupUiState(
    val status: BackupStatus = BackupStatus.LocalOnly,
    val latest: RemoteBackupSummary? = null,
    val review: ManualReview? = null,
    val busy: Boolean = false,
    val resolution: SyncConflictResolution? = null,
    val destructiveConfirmed: Boolean = false,
    val feedback: String? = null,
)

data class ManualBackupActions(
    val previewBackup: () -> Unit = {},
    val previewSync: () -> Unit = {},
    val selectResolution: (SyncConflictResolution) -> Unit = {},
    val confirmDestructive: (Boolean) -> Unit = {},
    val confirm: () -> Unit = {},
)

internal fun backupFailureMessage(reason: BackupFailureReason): String =
    when (reason) {
        BackupFailureReason.StalePreview,
        BackupFailureReason.ConcurrentRemoteChange ->
            "Data changed since this review. Open a fresh preview before confirming."
        BackupFailureReason.OwnershipMismatch -> "This device's data belongs to another account."
        BackupFailureReason.ActiveSessionPresent ->
            "Finish your active workout before syncing. Your workout has not changed."
        BackupFailureReason.ConflictChoiceRequired ->
            "Choose which conflict versions to keep before confirming."
        BackupFailureReason.DestructiveLocalChange ->
            "Review the reduced backup size and confirm the replacement."
        BackupFailureReason.Offline -> "Offline — try again when connected."
        BackupFailureReason.QuotaOrRateLimited -> "Backup paused — service quota or rate limit."
        BackupFailureReason.PermissionDenied ->
            "Backup access was denied. Your local data remains available."
        BackupFailureReason.UnsupportedVersion -> "This backup needs a newer version of IronPath."
        BackupFailureReason.InvalidSnapshot ->
            "This data cannot be safely synced. Review the backup or keep using this device."
        BackupFailureReason.ReauthenticationRequired ->
            "Needs sign-in. Your local data remains available."
        BackupFailureReason.ServiceUnavailable,
        BackupFailureReason.Unknown ->
            "The manual operation could not finish. Review again to retry."
    }
