package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.account.AccountState
import com.example.ironpath.domain.account.LocalOwnership
import com.example.ironpath.domain.backup.*
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ManualBackupOverview(
    account: AccountState,
    ui: ManualBackupUiState,
    actions: ManualBackupActions,
    onRefresh: () -> Unit
) {
    DemoBackupNotice()
    val eligible =
        when (account) {
            is AccountState.SignedIn -> true
            is AccountState.AwaitingDataChoice ->
                account.context.ownership.let {
                    it is LocalOwnership.Unclaimed ||
                        (it is LocalOwnership.Account && it.accountId == account.accountId)
                }
            else -> false
        }
    if (eligible) {
        val statusDetail =
            (ui.status as? BackupStatus.NeedsAttention)
                ?.takeIf { ui.feedback == null }
                ?.let { backupFailureMessage(it.reason) }
                ?: "Only a confirmed manual action writes a demo backup or syncs training data."
        AccountSection(backupStatusLabel(ui.status), statusDetail)
        ui.latest?.let { latest ->
            AccountSection(
                "LAST COMPLETE DEMO BACKUP",
                "${completionTime(latest.completedAtEpochMillis)}\n${countSummary(latest.entityCounts)}"
            )
        }
    }
    if (ui.busy)
        Text(
            "Manual operation in progress",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
    ui.feedback?.let { Feedback(it) }
    OutlinedButton(
        onClick = actions.previewBackup,
        enabled = eligible && !ui.busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("BACK UP NOW")
    }
    OutlinedButton(
        onClick = actions.previewSync,
        enabled = eligible && !ui.busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("REVIEW MANUAL SYNC")
    }
    OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
        Text("PREVIEW WHOLE-BACKUP RESTORE")
    }
    Text("Whole-backup restore is not available yet.", style = MaterialTheme.typography.bodySmall)
    if (eligible)
        TextButton(onClick = onRefresh, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
            Text("REFRESH BACKUP STATUS")
        }
}

@Composable
internal fun ManualBackupReviewScreen(
    ui: ManualBackupUiState,
    actions: ManualBackupActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val review = ui.review ?: return
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            if (review is ManualReview.Backup) "MANUAL BACKUP PREVIEW" else "MANUAL SYNC PREVIEW",
            style = MaterialTheme.typography.headlineMedium
        )
        DemoBackupNotice()
        when (review) {
            is ManualReview.Backup -> {
                AccountSection("INCLUDED TRAINING DATA", countSummary(review.preview.entityCounts))
                if (review.preview.associationOnly)
                    Text(
                        "There is no included training data. Confirming links this local profile to the demo account without creating an empty backup."
                    )
                else
                    Text(
                        "Confirming saves a complete demo backup of the included training data. Your active workout is not included."
                    )
                if (review.preview.requiresDestructiveConfirmation) {
                    AccountSection(
                        "REDUCED BACKUP",
                        "The previous backup included ${review.preview.previousEntityCount} records. This backup includes ${review.preview.entityCounts.values.sum()}. Review this reduction before replacing the latest backup."
                    )
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = ui.destructiveConfirmed,
                                enabled = !ui.busy,
                                role = Role.Checkbox,
                                onValueChange = actions.confirmDestructive
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = ui.destructiveConfirmed,
                            onCheckedChange = null,
                            enabled = !ui.busy
                        )
                        Text(
                            "I confirm replacing the latest backup with this reduced data",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            is ManualReview.Sync -> {
                AccountSection("LOCAL CHANGES", countSummary(review.preview.localChanges))
                AccountSection("CLOUD CHANGES", countSummary(review.preview.cloudChanges))
                AccountSection("CONFLICTING RECORDS", countSummary(review.preview.conflicts))
                Text(
                    "Changes made on only one side are included in either outcome. Confirming sync can change training data on this device. Device time does not choose the winner."
                )
                if (review.preview.conflicts.values.sum() > 0) {
                    Text("CHOOSE THE CONFLICT OUTCOME", style = MaterialTheme.typography.labelLarge)
                    Column(
                        Modifier.selectableGroup(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConflictChoice(
                            "Merge and keep local conflict versions",
                            "Recommended · keeps this device's conflicting versions.",
                            SyncConflictResolution.KeepLocal,
                            ui,
                            actions,
                            review.preview.canKeepLocal
                        )
                        ConflictChoice(
                            "Overwrite this device from cloud",
                            "Replaces conflicting local versions with cloud versions. Independent changes still merge.",
                            SyncConflictResolution.KeepCloud,
                            ui,
                            actions,
                            review.preview.canKeepCloud
                        )
                    }
                }
                if (!review.preview.canKeepLocal || !review.preview.canKeepCloud)
                    Text(
                        "An unavailable outcome would leave inconsistent training records. Choose a valid outcome or cancel the review."
                    )
            }
        }
        ui.feedback?.let { Feedback(it) }
        if (ui.busy) Feedback("Manual operation in progress")
        val canConfirm =
            when (review) {
                is ManualReview.Backup ->
                    !review.preview.requiresDestructiveConfirmation || ui.destructiveConfirmed
                is ManualReview.Sync ->
                    when {
                        review.preview.conflicts.values.sum() == 0 -> review.preview.canKeepLocal
                        ui.resolution == SyncConflictResolution.KeepLocal ->
                            review.preview.canKeepLocal
                        ui.resolution == SyncConflictResolution.KeepCloud ->
                            review.preview.canKeepCloud
                        else -> false
                    }
            }
        Button(
            onClick = actions.confirm,
            enabled = canConfirm && !ui.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (review is ManualReview.Backup) "CONFIRM MANUAL BACKUP"
                else "CONFIRM MANUAL SYNC"
            )
        }
        TextButton(onClick = onBack, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) {
            Text("BACK TO ACCOUNT & BACKUP")
        }
    }
}

@Composable
private fun ConflictChoice(
    title: String,
    detail: String,
    value: SyncConflictResolution,
    ui: ManualBackupUiState,
    actions: ManualBackupActions,
    available: Boolean
) {
    val enabled = available && !ui.busy
    Row(
        Modifier.fillMaxWidth()
            .background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
            .selectable(
                selected = ui.resolution == value,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = { actions.selectResolution(value) }
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = ui.resolution == value, onClick = null, enabled = enabled)
        Column(
            Modifier.weight(1f).padding(start = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DemoBackupNotice() {
    AccountSection(
        "DEMO BACKUP ON THIS DEVICE",
        "Demo backups stay inside this app on this device. They do not protect against uninstall or device loss. No data is sent to Google or Firebase."
    )
}

@Composable
private fun Feedback(text: String) {
    Text(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium
    )
}

internal fun countSummary(counts: Map<String, Int>): String {
    if (counts.values.sum() == 0) return "No included records"
    return counts
        .filterValues { it > 0 }
        .entries
        .joinToString("\n") { (category, count) -> "${categoryLabel(category)}: $count" }
}

private fun categoryLabel(category: String): String =
    when (category) {
        "WeeklyPlan" -> "Weekly plans"
        "PlannedWorkout" -> "Planned workouts"
        "PlannedExercise" -> "Planned exercises"
        "WorkoutLog" -> "Workout logs"
        "LoggedExercise" -> "Logged exercises"
        "LoggedSet" -> "Logged sets"
        "PersonalRecord" -> "Personal records"
        else -> category
    }

internal fun backupStatusLabel(status: BackupStatus): String =
    when (status) {
        BackupStatus.LocalOnly -> "Local only"
        BackupStatus.SignedInNoBackup -> "Signed in — no backup yet"
        BackupStatus.LocalChanges -> "Local changes"
        BackupStatus.ReviewRequired -> "Review required"
        BackupStatus.Preparing -> "Preparing manual backup"
        BackupStatus.BackingUp -> "Backing up now"
        is BackupStatus.UpToDate -> "Up to date"
        BackupStatus.OfflinePending -> "Offline — try again when connected"
        BackupStatus.QuotaPaused -> "Backup paused — service quota or rate limit"
        BackupStatus.NeedsSignIn -> "Needs sign-in"
        is BackupStatus.NeedsAttention -> "Needs attention"
    }

private fun completionTime(millis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(millis))
