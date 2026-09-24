package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.graphics.Color
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
    OutlinedButton(
        onClick = actions.previewRestore,
        enabled = eligible && ui.latest != null && !ui.busy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("PREVIEW WHOLE-BACKUP RESTORE")
    }
    Text(
        "Restore uses the latest complete backup only. Older backups cannot be selected.",
        style = MaterialTheme.typography.bodySmall
    )
    if (ui.undoAvailable)
        OutlinedButton(
            onClick = actions.previewUndo,
            enabled = eligible && !ui.busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("PREVIEW ONE UNDO")
        }
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
            when (review) {
                is ManualReview.Backup -> "MANUAL BACKUP PREVIEW"
                is ManualReview.Sync -> "MANUAL SYNC PREVIEW"
                is ManualReview.Restore -> "WHOLE-BACKUP RESTORE REVIEW"
                is ManualReview.Undo -> "ONE-UNDO REVIEW"
            },
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
            is ManualReview.Restore -> {
                AccountSection(
                    "LATEST COMPLETE DEMO BACKUP",
                    "${completionTime(review.preview.latest.completedAtEpochMillis)}\n${countSummary(review.preview.latest.entityCounts)}"
                )
                Text("Backup source: ${review.preview.sourceDescription}")
                Text(
                    "This replaces all included training data on this device with the latest complete backup. You can review the changes and undo them once on this device. Your active workout is not included in undo."
                )
                ImpactSummary(review.preview.impact)
                if (review.preview.nulledProvenanceFields.isNotEmpty())
                    Text(
                        "Links to unavailable source records will be cleared: ${provenanceLabels(review.preview.nulledProvenanceFields).joinToString()}"
                    )
                if (review.preview.activeWorkoutDiscardRequired) {
                    Text(activeWorkoutDiscardCopy(review.preview.activeWorkoutTitle))
                    Row(
                        Modifier.fillMaxWidth()
                            .toggleable(
                                value = ui.activeWorkoutDiscardConfirmed,
                                enabled = !ui.busy,
                                role = Role.Checkbox,
                                onValueChange = actions.confirmActiveWorkoutDiscard
                            )
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = ui.activeWorkoutDiscardConfirmed,
                            onCheckedChange = null,
                            enabled = !ui.busy
                        )
                        Text(
                            "I understand this active workout will be discarded",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
            is ManualReview.Undo -> {
                Text(
                    "This brings back the included training records from before the last restore. Active workout state is excluded from undo, and undo does not change the backup."
                )
                ImpactSummary(review.preview.impact)
                if (review.preview.activeWorkoutPresent)
                    Text(
                        "Finish or discard the active workout through its normal workout flow before undoing. The workout has not changed."
                    )
                else
                    Text(
                        "After undo, this device will show local changes until you explicitly back up or review a manual sync."
                    )
            }
        }
        ui.feedback?.let { feedback ->
            val color =
                when (review) {
                    is ManualReview.Restore,
                    is ManualReview.Undo -> MaterialTheme.colorScheme.error
                    else -> LocalContentColor.current
                }
            Feedback(feedback, color)
        }
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
                is ManualReview.Restore ->
                    !review.preview.activeWorkoutDiscardRequired || ui.activeWorkoutDiscardConfirmed
                is ManualReview.Undo -> !review.preview.activeWorkoutPresent
            }
        when (review) {
            is ManualReview.Backup,
            is ManualReview.Sync ->
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
            is ManualReview.Restore ->
                HoldConfirmButton(
                    "PRESS AND HOLD TO RESTORE",
                    canConfirm && !ui.busy,
                    actions.holdGuidance,
                    actions.confirm
                )
            is ManualReview.Undo ->
                HoldConfirmButton(
                    "PRESS AND HOLD TO UNDO",
                    canConfirm && !ui.busy,
                    actions.holdGuidance,
                    actions.confirm
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
private fun Feedback(text: String, color: Color = LocalContentColor.current) {
    Text(
        text,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = color,
    )
}

internal fun countSummary(counts: Map<String, Int>): String {
    if (counts.values.sum() == 0) return "No included records"
    return counts
        .filterValues { it > 0 }
        .entries
        .joinToString("\n") { (category, count) -> "${categoryLabel(category)}: $count" }
}

@Composable
private fun ImpactSummary(impact: Map<String, BackupCategoryImpact>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("WHOLE-BACKUP CHANGE", style = MaterialTheme.typography.labelLarge)
        Text("Added = records from the backup that will appear on this device.")
        Text("Updated = records found on both sides whose details differ.")
        Text(
            "Replaced = records on this device that are missing from the backup and will be removed."
        )
        impact.forEach { (category, change) ->
            if (change.added + change.updated + change.replaced > 0)
                AccountSection(
                    categoryLabel(category),
                    "Added: ${change.added} · Updated: ${change.updated} · Replaced: ${change.replaced}"
                )
        }
        if (impact.values.all { it.added + it.updated + it.replaced == 0 })
            Text("No included records change.")
    }
}

private fun activeWorkoutDiscardCopy(title: String?): String =
    title
        ?.takeIf { it.isNotBlank() }
        ?.let {
            "The active workout ‘$it’ was found. Confirming will discard it, and it will not be part of the one undo."
        }
        ?: "An active workout was found. Confirming will discard it, and it will not be part of the one undo."

private fun provenanceLabels(fields: Set<String>): List<String> =
    fields.sorted().map {
        when (it) {
            "sourcePlannedWorkoutId" -> "original planned workout"
            "sourceWorkoutLogId" -> "original workout log"
            else -> "source record link"
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HoldConfirmButton(
    label: String,
    enabled: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val container =
        if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val content =
        if (enabled) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Box(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(container, RoundedCornerShape(24.dp))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onTap,
                onLongClick = onHold
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = content, style = MaterialTheme.typography.labelLarge)
    }
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
