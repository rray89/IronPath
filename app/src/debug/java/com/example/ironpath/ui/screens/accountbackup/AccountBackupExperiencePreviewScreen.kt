package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow

private enum class PreviewPage {
    Overview,
    ManualSync,
    Restore,
}

private enum class ConflictOutcome {
    MergeKeepLocal,
    OverwriteFromCloud,
}

private data class AccountBackupPreviewFixture(
    val accountLabel: String,
    val localRevision: Long,
    val cloudGeneration: Long,
    val safeMergeCount: Int,
    val conflictCount: Int,
    val backupSource: String,
    val addedCount: Int,
    val updatedCount: Int,
    val replacedCount: Int,
)

private val previewFixture =
    AccountBackupPreviewFixture(
        accountLabel = "DEMO GOOGLE ACCOUNT",
        localRevision = 42,
        cloudGeneration = 39,
        safeMergeCount = 4,
        conflictCount = 2,
        backupSource = "Pixel 8 · Aug 18, 2026 at 9:42 PM",
        addedCount = 3,
        updatedCount = 4,
        replacedCount = 2,
    )

/** Debug-only, fixture-backed product-direction preview. It never authenticates or mutates data. */
@Composable
fun AccountBackupExperiencePreviewScreen(modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf(PreviewPage.Overview) }
    var manualBackupPreviewCompleted by rememberSaveable { mutableStateOf(false) }
    var conflictOutcome by rememberSaveable { mutableStateOf<ConflictOutcome?>(null) }
    var syncPreviewCompleted by rememberSaveable { mutableStateOf(false) }
    var restorePreviewCompleted by rememberSaveable { mutableStateOf(false) }
    var showHoldHint by rememberSaveable { mutableStateOf(false) }

    key(page) {
        Column(
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (page) {
                PreviewPage.Overview ->
                    OverviewContent(
                        fixture = previewFixture,
                        manualBackupPreviewCompleted = manualBackupPreviewCompleted,
                        onBackUpNow = { manualBackupPreviewCompleted = true },
                        onReviewSync = { page = PreviewPage.ManualSync },
                        onPreviewRestore = { page = PreviewPage.Restore },
                    )
                PreviewPage.ManualSync ->
                    ManualSyncContent(
                        fixture = previewFixture,
                        conflictOutcome = conflictOutcome,
                        syncPreviewCompleted = syncPreviewCompleted,
                        onConflictOutcomeSelected = {
                            conflictOutcome = it
                            syncPreviewCompleted = false
                        },
                        onConfirm = { syncPreviewCompleted = true },
                        onBack = { page = PreviewPage.Overview },
                    )
                PreviewPage.Restore ->
                    RestoreContent(
                        fixture = previewFixture,
                        restorePreviewCompleted = restorePreviewCompleted,
                        showHoldHint = showHoldHint,
                        onShortPress = { showHoldHint = true },
                        onLongPress = {
                            restorePreviewCompleted = true
                            showHoldHint = false
                        },
                        onBack = { page = PreviewPage.Overview },
                    )
            }
        }
    }
}

@Composable
private fun OverviewContent(
    fixture: AccountBackupPreviewFixture,
    manualBackupPreviewCompleted: Boolean,
    onBackUpNow: () -> Unit,
    onReviewSync: () -> Unit,
    onPreviewRestore: () -> Unit,
) {
    ScreenHeading("MANUAL BACKUP & RESTORE")
    PreviewNotice()
    StatusCard(
        label = "MANUAL ONLY",
        title = "Nothing moves without your action",
        body =
            "Signing in identifies the account. Nothing uploads until you confirm a manual " +
                "backup or sync.",
    )
    StatusCard(
        label = "SIGNED-IN FIXTURE",
        title = fixture.accountLabel,
        body =
            "Local revision ${fixture.localRevision} · Cloud generation " +
                "${fixture.cloudGeneration}. Revision lineage—not device time—drives comparison.",
    )
    Button(onClick = onBackUpNow, modifier = Modifier.fillMaxWidth()) { Text("BACK UP NOW") }
    if (manualBackupPreviewCompleted) {
        OutcomeNotice("Preview complete — no backup ran and no data changed")
    }
    OutlinedButton(onClick = onReviewSync, modifier = Modifier.fillMaxWidth()) {
        Text("REVIEW MANUAL SYNC")
    }
    OutlinedButton(onClick = onPreviewRestore, modifier = Modifier.fillMaxWidth()) {
        Text("PREVIEW WHOLE-BACKUP RESTORE")
    }
    Text(
        text =
            "Automatic and background backup, production Firebase, sign-out, and account deletion " +
                "are outside this first release.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ManualSyncContent(
    fixture: AccountBackupPreviewFixture,
    conflictOutcome: ConflictOutcome?,
    syncPreviewCompleted: Boolean,
    onConflictOutcomeSelected: (ConflictOutcome) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenHeading("MANUAL SYNC PREVIEW")
    PreviewNotice()
    StatusCard(
        label = "REVISION CHECK",
        title = "${fixture.safeMergeCount} changes can merge safely",
        body =
            "2 local additions, 1 cloud addition, and 1 one-sided update have independent " +
                "revision history. They merge automatically only after confirmation.",
    )
    StatusCard(
        label = "CONFLICT",
        title = "${fixture.conflictCount} records need your choice",
        body =
            "Both sides changed since their shared revision. Device wall-clock timestamps do not " +
                "decide the winner.",
    )
    Text(
        text = "CHOOSE THE CONFLICT OUTCOME",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ConflictOption(
            title = "Merge and keep local conflict versions",
            detail = "Recommended · preserves newer offline work from this device.",
            selected = conflictOutcome == ConflictOutcome.MergeKeepLocal,
            onClick = { onConflictOutcomeSelected(ConflictOutcome.MergeKeepLocal) },
        )
        ConflictOption(
            title = "Overwrite this device from cloud",
            detail = "Replaces the conflicting local versions with the cloud versions.",
            selected = conflictOutcome == ConflictOutcome.OverwriteFromCloud,
            onClick = { onConflictOutcomeSelected(ConflictOutcome.OverwriteFromCloud) },
        )
    }
    Button(
        onClick = onConfirm,
        enabled = conflictOutcome != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("CONFIRM MANUAL SYNC")
    }
    if (syncPreviewCompleted) {
        OutcomeNotice("Preview complete — no sync ran and no data changed")
    }
    BackToAccountButton(onBack)
}

@Composable
private fun ConflictOption(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RestoreContent(
    fixture: AccountBackupPreviewFixture,
    restorePreviewCompleted: Boolean,
    showHoldHint: Boolean,
    onShortPress: () -> Unit,
    onLongPress: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenHeading("WHOLE-BACKUP RESTORE")
    PreviewNotice()
    StatusCard(
        label = "BACKUP SOURCE",
        title = fixture.backupSource,
        body = "Validated complete backup · Cloud generation ${fixture.cloudGeneration}",
    )
    Text(
        text = "IMPACT ON THIS DEVICE",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    ImpactRow("Added", fixture.addedCount, "Cloud-only plans and workout records")
    ImpactRow("Updated", fixture.updatedCount, "Matching records with compatible revisions")
    ImpactRow("Replaced", fixture.replacedCount, "Conflicting local records")
    Text(
        text = "Whole backup only. Individual records cannot be edited here.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    StatusCard(
        label = "ATOMIC & REVERSIBLE ONCE",
        title = "Failure leaves local data unchanged",
        body =
            "One pre-restore local snapshot will be kept for one undo. The next successful " +
                "restore replaces it.",
    )
    LongPressRestoreButton(onClick = onShortPress, onLongClick = onLongPress)
    if (showHoldHint) {
        OutcomeNotice("Keep holding Restore to confirm the whole-backup replacement")
    }
    if (restorePreviewCompleted) {
        OutcomeNotice("Preview complete — no data changed")
    }
    BackToAccountButton(onBack)
}

@Composable
private fun ImpactRow(label: String, count: Int, detail: String) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "$label · $count",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressRestoreButton(onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .testTag(TestTags.ACCOUNT_PREVIEW_LONG_PRESS_RESTORE)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .semantics {
                    role = Role.Button
                    contentDescription = "Long-press Restore"
                    stateDescription = "Hold to confirm whole-backup restore"
                }
                .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "HOLD TO RESTORE",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PreviewNotice() {
    StatusCard(
        label = "EXPERIENCE PREVIEW",
        title = "No account or cloud connection",
        body = "All identities, revisions, backups, and counts are deterministic fixtures.",
    )
}

@Composable
private fun StatusCard(label: String, title: String, body: String) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutcomeNotice(message: String) {
    Text(
        text = message,
        modifier =
            Modifier.fillMaxWidth()
                .background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
                .padding(12.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ScreenHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun BackToAccountButton(onBack: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
        Text("BACK TO ACCOUNT & BACKUP")
    }
}
