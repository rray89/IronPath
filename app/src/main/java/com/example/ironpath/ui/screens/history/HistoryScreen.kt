package com.example.ironpath.ui.screens.history

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -- Production entry point --

@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onOpenLog: (String, Boolean) -> Unit = { _, _ -> },
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val addRecordShown by viewModel.addRecordShown.collectAsStateWithLifecycle()
    val editingRecord by viewModel.editingRecord.collectAsStateWithLifecycle()
    val editRecordError by viewModel.editRecordError.collectAsStateWithLifecycle()
    val suggestions by viewModel.exerciseSuggestions.collectAsStateWithLifecycle()

    // Intercept system back so it returns to the records list, not pops History off the nav stack
    BackHandler(enabled = editingRecord != null, onBack = viewModel::hideEditRecord)
    BackHandler(enabled = addRecordShown, onBack = viewModel::hideAddRecord)

    when {
        editingRecord != null -> {
            AddRecordScreen(
                suggestions = suggestions,
                onSave = { record -> viewModel.updateRecord(record) },
                onCancel = viewModel::hideEditRecord,
                existingRecord = editingRecord,
                onDelete = { editingRecord?.let { viewModel.deleteRecord(it.id) } },
                externalError = editRecordError,
                onExternalErrorConsumed = viewModel::clearEditRecordError,
                modifier = modifier,
            )
        }
        addRecordShown -> {
            AddRecordScreen(
                suggestions = suggestions,
                onSave = { record -> viewModel.saveRecord(record) {} },
                onCancel = viewModel::hideAddRecord,
                modifier = modifier,
            )
        }
        else -> {
            HistoryContent(
                selectedTab = selectedTab,
                logs = logs,
                records = records,
                onTabSelected = viewModel::selectTab,
                onAddRecord = viewModel::showAddRecord,
                onRecordClick = { record ->
                    if (
                        record.sourceType == RecordSource.Logged &&
                            record.sourceWorkoutLogId != null
                    ) {
                        onOpenLog(record.sourceWorkoutLogId, true)
                    } else {
                        viewModel.showEditRecord(record)
                    }
                },
                onLogClick = { log -> onOpenLog(log.id, false) },
                modifier = modifier,
            )
        }
    }
}

// -- Pure render composable --

@Composable
internal fun HistoryContent(
    selectedTab: HistoryTab,
    logs: List<WorkoutLog>,
    records: List<PersonalRecord>,
    onTabSelected: (HistoryTab) -> Unit,
    onAddRecord: () -> Unit,
    onRecordClick: (PersonalRecord) -> Unit = {},
    onLogClick: (WorkoutLog) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // Segmented control: Logs | Records
        TabBar(selectedTab = selectedTab, onTabSelected = onTabSelected)

        Spacer(Modifier.height(16.dp))

        when (selectedTab) {
            HistoryTab.Logs -> LogsContent(logs, onLogClick)
            HistoryTab.Records -> RecordsContent(records, onAddRecord, onRecordClick)
        }
    }
}

// -- Tab Bar --

@Composable
private fun TabBar(
    selectedTab: HistoryTab,
    onTabSelected: (HistoryTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(2.dp),
    ) {
        HistoryTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            val bgColor = if (selected) MaterialTheme.colorScheme.primary else SurfaceContainerLow
            val textColor =
                if (selected) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.onSurfaceVariant

            Box(
                modifier =
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .background(bgColor)
                        .clickable { onTabSelected(tab) }
                        .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = tab.name.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                )
            }
        }
    }
}

// -- Logs Tab --

@Composable
private fun LogsContent(
    logs: List<WorkoutLog>,
    onLogClick: (WorkoutLog) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (logs.isEmpty()) {
        LogsEmptyState(modifier)
    } else {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
        ) {
            logs.forEach { log ->
                LogRow(log, onClick = { onLogClick(log) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LogsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.size(64.dp).background(SurfaceContainerHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.FitnessCenter,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "No workout logs yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Completed workouts will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogRow(
    log: WorkoutLog,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceContainerLow)
                .clickable(onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = log.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = formatDate(log.completedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${log.durationMinutes} MIN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${log.exerciseCount} exercises",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -- Records Tab --

@Composable
private fun RecordsContent(
    records: List<PersonalRecord>,
    onAddRecord: () -> Unit,
    onRecordClick: (PersonalRecord) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) {
        RecordsEmptyState(onAddRecord, modifier)
    } else {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Personal Bests",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(16.dp))

            records.forEach { record ->
                RecordRow(record, onClick = { onRecordClick(record) })
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))

            // Add new record
            Box(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(SurfaceContainerLow)
                        .clickable(onClick = onAddRecord)
                        .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "ADD NEW RECORD",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordsEmptyState(
    onAddRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.size(64.dp).background(SurfaceContainerHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = "No records yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Personal bests and records will\nappear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        GreenGradientButton(
            text = "Add Record",
            onClick = onAddRecord,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.surface,
                )
            },
        )
    }
}

@Composable
private fun RecordRow(
    record: PersonalRecord,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceContainerLow)
                .clickable(onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.size(40.dp).background(SurfaceContainerHigh, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = record.exerciseName,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = record.achievedOn,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (record.sourceType == RecordSource.Logged) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "LOGGED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Text(
            text = "${record.weightKg.toInt()}kg",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun formatDate(millis: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    return sdf.format(Date(millis))
}

// -- Previews --

@Preview(showBackground = true)
@Composable
private fun PreviewHistoryLogsEmpty() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HistoryContent(
                selectedTab = HistoryTab.Logs,
                logs = emptyList(),
                records = emptyList(),
                onTabSelected = {},
                onAddRecord = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHistoryLogsWithData() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HistoryContent(
                selectedTab = HistoryTab.Logs,
                logs =
                    listOf(
                        WorkoutLog(
                            title = "Upper Body B",
                            startedAt = 1711800000000,
                            completedAt = 1711803600000,
                            durationMinutes = 45,
                            exerciseCount = 5
                        ),
                        WorkoutLog(
                            title = "Deadlift Focused",
                            startedAt = 1711627200000,
                            completedAt = 1711630800000,
                            durationMinutes = 38,
                            exerciseCount = 4
                        ),
                    ),
                records = emptyList(),
                onTabSelected = {},
                onAddRecord = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHistoryRecordsEmpty() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HistoryContent(
                selectedTab = HistoryTab.Records,
                logs = emptyList(),
                records = emptyList(),
                onTabSelected = {},
                onAddRecord = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewHistoryRecordsWithData() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            HistoryContent(
                selectedTab = HistoryTab.Records,
                logs = emptyList(),
                records =
                    listOf(
                        PersonalRecord(
                            exerciseName = "Bench Press",
                            normalizedExerciseName = "bench press",
                            weightKg = 100.0,
                            achievedOn = "2026-03-23"
                        ),
                        PersonalRecord(
                            exerciseName = "Squat",
                            normalizedExerciseName = "squat",
                            weightKg = 180.0,
                            achievedOn = "2026-03-25"
                        ),
                    ),
                onTabSelected = {},
                onAddRecord = {},
            )
        }
    }
}
