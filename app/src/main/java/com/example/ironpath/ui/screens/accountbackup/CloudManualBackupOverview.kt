package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
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
        if (
            manual.status == BackupStatus.OfflinePending ||
                manual.status is BackupStatus.NeedsAttention
        ) {
            Text(
                "An interrupted request may have reached the cloud. Refresh to check the latest complete backup, then open a fresh preview to retry.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        manual.feedback?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}
