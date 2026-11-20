package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.backup.BackupFailureReason
import com.example.ironpath.domain.backup.BackupStatus

/** Shared credential-free presentation seam; release has no live cloud route or transport. */
@Composable
internal fun CloudManualBackupOverview(
    manual: ManualBackupUiState,
    backupAvailable: Boolean,
    onBackup: () -> Unit,
    onRefresh: () -> Unit,
    onSync: () -> Unit,
    onRestore: () -> Unit = {},
    onUndo: () -> Unit = {},
) {
    val failure = manual.cloudFailureReason()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AccountSection(
            backupStatusLabel(manual.status),
            if (manual.status == BackupStatus.ReviewRequired)
                "Review manual sync to compare local and cloud changes before choosing what to keep."
            else
                "Refresh checks the latest complete backup. Signing in and app startup never upload training data.",
            modifier = Modifier.semantics { stateDescription = backupStatusLabel(manual.status) },
        )
        manual.latest?.let { latest ->
            AccountSection(
                "LATEST COMPLETE CLOUD BACKUP",
                "${completionTime(latest.completedAtEpochMillis)}\n${countSummary(latest.entityCounts)}"
            )
        }
        failure?.let {
            CloudBackupError(
                it,
                revealActionFailure = manual.feedbackFailureReason != null,
                failureVersion = manual.feedbackFailureVersion
            )
        }
        Button(
            onClick = onSync,
            enabled = backupAvailable && !manual.busy && !manual.signOutBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("REVIEW MANUAL SYNC")
        }
        Text(
            "Review manual sync downloads the latest complete backup to compare changes. Only confirming the preview can merge, upload, or change training data on this device.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = onBackup,
            enabled = backupAvailable && !manual.busy && !manual.signOutBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("BACK UP NOW")
        }
        Button(
            onClick = onRestore,
            enabled =
                backupAvailable && manual.latest != null && !manual.busy && !manual.signOutBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("PREVIEW WHOLE-BACKUP RESTORE")
        }
        Text(
            "Restore replaces all included training data with the latest complete cloud backup after review and a long press. Exactly one local undo is kept. Restore and undo never change the cloud backup.",
            style = MaterialTheme.typography.bodyMedium
        )
        if (manual.undoAvailable)
            Button(
                onClick = onUndo,
                enabled = backupAvailable && !manual.busy && !manual.signOutBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("PREVIEW ONE UNDO")
            }
        TextButton(
            onClick = onRefresh,
            enabled = !manual.busy && !manual.signOutBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("REFRESH BACKUP STATUS")
        }
        if (manual.busy)
            Text(
                "Manual operation in progress",
                modifier =
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        stateDescription = "Busy"
                    }
            )
        manual.feedback
            ?.takeIf { failure == null && manual.feedbackFailureReason == null }
            ?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
    }
}

@Composable
private fun CloudBackupError(
    reason: BackupFailureReason,
    revealActionFailure: Boolean,
    failureVersion: Long
) {
    val message = backupFailureMessage(reason)
    val causeRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(reason, revealActionFailure, failureVersion) {
        if (revealActionFailure) {
            // Wait for the new heading to be placed; the whole card can exceed a large-text
            // viewport.
            withFrameNanos {}
            causeRequester.bringIntoView()
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                error(message)
                liveRegion = LiveRegionMode.Assertive
                stateDescription = "Backup failed"
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.bringIntoViewRequester(causeRequester).semantics { heading() }
            )
            cloudFailureRecovery(reason)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
