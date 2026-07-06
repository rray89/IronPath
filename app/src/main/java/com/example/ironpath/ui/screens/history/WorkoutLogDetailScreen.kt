package com.example.ironpath.ui.screens.history

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WorkoutLogDetailScreen(
    logId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    recordActionsEnabled: Boolean = true,
    viewModel: WorkoutLogDetailViewModel = koinViewModel(parameters = { parametersOf(logId) }),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WorkoutLogDetailContent(
        uiState = uiState,
        onBack = onBack,
        onSaveSetAsRecord = viewModel::saveSetAsRecord,
        recordActionsEnabled = recordActionsEnabled,
        modifier = modifier,
    )
}

@Composable
internal fun WorkoutLogDetailContent(
    uiState: WorkoutLogDetailUiState,
    onBack: () -> Unit,
    onSaveSetAsRecord: (LoggedExerciseDetail, LoggedSet) -> Unit = { _, _ -> },
    recordActionsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        WorkoutLogDetailUiState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        WorkoutLogDetailUiState.NotFound -> WorkoutLogDetailNotFound(onBack, modifier)
        is WorkoutLogDetailUiState.Ready ->
            WorkoutLogDetailReady(
                state = uiState,
                onBack = onBack,
                onSaveSetAsRecord = onSaveSetAsRecord,
                recordActionsEnabled = recordActionsEnabled,
                modifier = modifier,
            )
    }
}

@Composable
private fun WorkoutLogDetailNotFound(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "LOG NOT FOUND",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This workout log is no longer available.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        GreenGradientButton(text = "Go Back", onClick = onBack)
    }
}

@Composable
private fun WorkoutLogDetailReady(
    state: WorkoutLogDetailUiState.Ready,
    onBack: () -> Unit,
    onSaveSetAsRecord: (LoggedExerciseDetail, LoggedSet) -> Unit,
    recordActionsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val detail = state.detail
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "WORKOUT LOG",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = detail.log.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatLogDate(detail.log.completedAt),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        WorkoutLogSummary(log = detail.log)

        if (state.recordMessage != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.recordMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(18.dp))

        if (detail.exercises.isEmpty()) {
            Text(
                text = "No exercise snapshot was saved for this log.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            detail.exercises.forEachIndexed { index, exerciseDetail ->
                LoggedExerciseRow(
                    index = index + 1,
                    detail = exerciseDetail,
                    savedSetIds = state.savedSetIds,
                    onSaveSetAsRecord = onSaveSetAsRecord,
                    recordActionsEnabled = recordActionsEnabled,
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun WorkoutLogSummary(
    log: WorkoutLog,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.FitnessCenter,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${log.durationMinutes} MIN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${log.exerciseCount} exercises",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoggedExerciseRow(
    index: Int,
    detail: LoggedExerciseDetail,
    savedSetIds: Set<String>,
    onSaveSetAsRecord: (LoggedExerciseDetail, LoggedSet) -> Unit,
    recordActionsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = index.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = detail.exercise.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text =
                        "${detail.exercise.plannedSets} sets · ${detail.exercise.plannedReps} reps · ${formatWeight(detail.exercise.plannedWeightKg)} planned",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        if (detail.sets.isEmpty()) {
            Text(
                text = "No sets were logged for this exercise.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            detail.sets
                .sortedBy { it.setNumber }
                .forEach { set ->
                    LoggedSetRow(
                        set = set,
                        isSaved = savedSetIds.contains(set.id),
                        onSaveRecord = { onSaveSetAsRecord(detail, set) },
                        recordActionsEnabled = recordActionsEnabled,
                    )
                    Spacer(Modifier.height(8.dp))
                }
        }
    }
}

@Composable
private fun LoggedSetRow(
    set: LoggedSet,
    isSaved: Boolean,
    onSaveRecord: () -> Unit,
    recordActionsEnabled: Boolean,
) {
    val canSave = set.reps != null && set.weightKg != null && set.weightKg > 0.0
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(SurfaceContainerHigh, RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (set.isExtra) "EXTRA ${set.setNumber}" else "SET ${set.setNumber}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = formatSetResult(set),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        if (canSave && (recordActionsEnabled || isSaved)) {
            Text(
                text = if (isSaved) "SAVED" else "SAVE RECORD",
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (isSaved) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary,
                modifier =
                    if (isSaved || !recordActionsEnabled) {
                        Modifier
                    } else {
                        Modifier.clickable(onClick = onSaveRecord)
                    },
            )
        }
    }
}

private fun formatSetResult(set: LoggedSet): String {
    val reps = set.reps
    val weight = set.weightKg
    return if (reps == null || weight == null) {
        "Not logged"
    } else {
        "$reps reps · ${formatWeight(weight)}"
    }
}

private fun formatLogDate(millis: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.US)
    return sdf.format(Date(millis))
}

private fun formatWeight(weightKg: Double): String =
    if (weightKg <= 0.0) {
        "bodyweight"
    } else if (weightKg % 1.0 == 0.0) {
        "${weightKg.toInt()} kg"
    } else {
        "$weightKg kg"
    }

@Preview(showBackground = true)
@Composable
private fun PreviewWorkoutLogDetailReady() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            WorkoutLogDetailContent(
                uiState =
                    WorkoutLogDetailUiState.Ready(
                        WorkoutLogDetail(
                            log =
                                WorkoutLog(
                                    id = "log1",
                                    title = "Push A",
                                    startedAt = 1_000L,
                                    completedAt = 1_711_803_600_000L,
                                    durationMinutes = 45,
                                    exerciseCount = 2,
                                ),
                            exercises =
                                listOf(
                                    LoggedExerciseDetail(
                                        exercise =
                                            LoggedExercise(
                                                id = "lex1",
                                                workoutLogId = "log1",
                                                name = "Bench Press",
                                                plannedSets = 3,
                                                plannedReps = 8,
                                                plannedWeightKg = 70.0,
                                                orderIndex = 0,
                                            ),
                                        sets =
                                            listOf(
                                                LoggedSet(
                                                    id = "set1",
                                                    loggedExerciseId = "lex1",
                                                    setNumber = 1,
                                                    reps = 8,
                                                    weightKg = 70.0,
                                                ),
                                                LoggedSet(
                                                    id = "set2",
                                                    loggedExerciseId = "lex1",
                                                    setNumber = 2,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
                onBack = {},
            )
        }
    }
}
