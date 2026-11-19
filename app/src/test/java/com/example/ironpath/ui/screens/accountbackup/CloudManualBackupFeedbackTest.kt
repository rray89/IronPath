package com.example.ironpath.ui.screens.accountbackup

import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.backup.BackupStatus
import org.junit.Assert.*
import org.junit.Test

class CloudManualBackupFeedbackTest {
    @Test
    fun `latest typed action failure wins before status updates`() {
        val ui =
            ManualBackupUiState(
                status = BackupStatus.UpToDate(123),
                feedback = "Backup access was denied. Your local data remains available.",
                feedbackFailureReason = BackupFailureReason.PermissionDenied
            )
        assertEquals(BackupFailureReason.PermissionDenied, ui.cloudFailureReason())
        assertNull(ui.copy(busy = true).cloudFailureReason())
        assertNull(ui.copy(signOutBusy = true).cloudFailureReason())
    }

    @Test
    fun `success and cancellation feedback do not inherit stale status severity`() {
        for (feedback in listOf("Manual cloud backup complete.", "Manual operation cancelled.")) {
            val ui =
                ManualBackupUiState(
                    status = BackupStatus.NeedsAttention(BackupFailureReason.PermissionDenied),
                    feedback = feedback
                )
            assertNull(ui.cloudFailureReason())
        }
    }

    @Test
    fun `restored typed statuses remain visible without ephemeral action feedback`() {
        val expected =
            listOf(
                BackupStatus.NeedsAttention(BackupFailureReason.PermissionDenied) to
                    BackupFailureReason.PermissionDenied,
                BackupStatus.OfflinePending to BackupFailureReason.Offline,
                BackupStatus.QuotaPaused to BackupFailureReason.QuotaOrRateLimited,
                BackupStatus.NeedsSignIn to BackupFailureReason.ReauthenticationRequired
            )
        expected.forEach { (status, reason) ->
            assertEquals(reason, ManualBackupUiState(status = status).cloudFailureReason())
        }
        assertNull(ManualBackupUiState(status = BackupStatus.UpToDate(123)).cloudFailureReason())
    }

    @Test
    fun `permission and quota guidance do not imply an interrupted upload`() {
        val denied = cloudFailureRecovery(BackupFailureReason.PermissionDenied)!!
        assertTrue(denied.contains("After cloud access is restored"))
        assertFalse(denied.contains("interrupted"))
        val quota = cloudFailureRecovery(BackupFailureReason.QuotaOrRateLimited)!!
        assertTrue(quota.contains("Wait"))
        assertFalse(quota.contains("interrupted"))
        assertFalse(
            cloudFailureRecovery(BackupFailureReason.ReauthenticationRequired)!!.contains(
                "interrupted"
            )
        )
    }

    @Test
    fun `uncertain results retain refresh then fresh preview recovery`() {
        for (reason in
            listOf(
                BackupFailureReason.Offline,
                BackupFailureReason.ServiceUnavailable,
                BackupFailureReason.Unknown
            )) {
            val guidance = cloudFailureRecovery(reason)!!
            assertTrue(guidance.contains("may have reached the cloud"))
            assertTrue(
                guidance.indexOf("refresh", ignoreCase = true) < guidance.indexOf("fresh preview")
            )
        }
    }
}
