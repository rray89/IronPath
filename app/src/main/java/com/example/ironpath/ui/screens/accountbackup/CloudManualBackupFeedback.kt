package com.example.ironpath.ui.screens.accountbackup

import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.backup.BackupStatus

internal fun ManualBackupUiState.cloudFailureReason(): BackupFailureReason? {
    if (busy || signOutBusy) return null
    // The latest action feedback takes precedence over an older coordinator status.
    if (feedback != null) return feedbackFailureReason
    return when (val current = status) {
        BackupStatus.OfflinePending -> BackupFailureReason.Offline
        BackupStatus.QuotaPaused -> BackupFailureReason.QuotaOrRateLimited
        BackupStatus.NeedsSignIn -> BackupFailureReason.ReauthenticationRequired
        is BackupStatus.NeedsAttention -> current.reason
        else -> null
    }
}

internal fun cloudFailureRecovery(reason: BackupFailureReason): String? =
    when (reason) {
        BackupFailureReason.PermissionDenied ->
            "Check that you are using the intended account. After cloud access is restored, refresh backup status, then open a fresh preview."
        BackupFailureReason.Offline ->
            "An interrupted request may have reached the cloud. When connected, refresh to check the latest complete backup, then open a fresh preview to retry."
        BackupFailureReason.ServiceUnavailable,
        BackupFailureReason.Unknown ->
            "An interrupted request may have reached the cloud. Refresh to check the latest complete backup, then open a fresh preview to retry."
        BackupFailureReason.QuotaOrRateLimited ->
            "Wait for the service quota or rate limit to reset, then refresh backup status and open a fresh preview."
        BackupFailureReason.ReauthenticationRequired ->
            "Sign in again, then refresh backup status before opening a fresh preview."
        BackupFailureReason.OwnershipMismatch ->
            "Use the account that owns this device's data, then refresh backup status."
        BackupFailureReason.StalePreview,
        BackupFailureReason.ConcurrentRemoteChange ->
            "Refresh backup status, then open a fresh preview to review the current data."
        BackupFailureReason.UnsupportedVersion -> "Update IronPath before retrying."
        BackupFailureReason.ActiveSessionPresent,
        BackupFailureReason.ConflictChoiceRequired,
        BackupFailureReason.DestructiveLocalChange,
        BackupFailureReason.InvalidSnapshot -> null
    }
