package com.example.ironpath.ui.screens.workoutpreview

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.screens.home.dayOfWeekAbbrev
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WorkoutPreviewScreen(
    onBack: () -> Unit,
    onStarted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkoutPreviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WorkoutPreviewContent(
        uiState = uiState,
        onBack = onBack,
        onStart = { viewModel.startWorkout(onStarted) },
        modifier = modifier,
    )
}

@Composable
internal fun WorkoutPreviewContent(
    uiState: WorkoutPreviewUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        WorkoutPreviewUiState.Loading -> {
            Box(
                modifier.fillMaxSize().testTag(TestTags.WORKOUT_PREVIEW_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        WorkoutPreviewUiState.NotFound -> WorkoutPreviewNotFound(onBack, modifier)
        is WorkoutPreviewUiState.Ready ->
            WorkoutPreviewReady(
                state = uiState,
                onBack = onBack,
                onStart = onStart,
                modifier = modifier,
            )
    }
}

@Composable
private fun WorkoutPreviewNotFound(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "WORKOUT NOT FOUND",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This workout is no longer available in the accepted plan.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        GreenGradientButton(text = "Go Back", onClick = onBack)
    }
}

@Composable
private fun WorkoutPreviewReady(
    state: WorkoutPreviewUiState.Ready,
    onBack: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                text = "WORKOUT PREVIEW",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = state.workout.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text =
                "${dayOfWeekAbbrev(state.workout.dayOfWeek)} · ${formatWorkoutDate(state.workout.scheduledDate)}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        GreenGradientButton(
            text = "Start Workout",
            onClick = onStart,
            enabled = state.canStart,
            trailingIcon = {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.surface,
                )
            },
        )
        Spacer(Modifier.height(20.dp))

        if (state.hasActiveSession) {
            Text(
                text = "Finish the active session before starting this workout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
        }

        Row(
            modifier =
                Modifier.fillMaxWidth()
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
            Text(
                text = "${state.exercises.size} EXERCISES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Spacer(Modifier.height(18.dp))

        if (state.exercises.isEmpty()) {
            Text(
                text = "No exercises are attached to this workout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.exercises.forEachIndexed { index, exercise ->
                PreviewExerciseRow(index = index + 1, exercise = exercise)
                Spacer(Modifier.height(10.dp))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PreviewExerciseRow(
    index: Int,
    exercise: PlannedExercise,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(TestTags.planExercise(exercise.id))
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
                text = exercise.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text =
                    "${exercise.sets} sets · ${exercise.reps} reps · ${formatWeight(exercise.weightKg)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatWorkoutDate(scheduledDate: String): String =
    runCatching {
            LocalDate.parse(scheduledDate)
                .format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US))
        }
        .getOrElse { scheduledDate }

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
private fun PreviewWorkoutPreviewReady() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            WorkoutPreviewContent(
                uiState =
                    WorkoutPreviewUiState.Ready(
                        workout =
                            PlannedWorkout(
                                id = "w1",
                                weeklyPlanId = "plan1",
                                dayOfWeek = 3,
                                scheduledDate = "2026-04-15",
                                title = "Push A",
                                status = WorkoutStatus.Upcoming,
                            ),
                        exercises =
                            listOf(
                                PlannedExercise(
                                    id = "preview-exercise-1",
                                    plannedWorkoutId = "w1",
                                    name = "Barbell Bench Press",
                                    sets = 4,
                                    reps = 8,
                                    weightKg = 70.0,
                                    orderIndex = 0,
                                ),
                                PlannedExercise(
                                    id = "preview-exercise-2",
                                    plannedWorkoutId = "w1",
                                    name = "Incline Dumbbell Press",
                                    sets = 3,
                                    reps = 10,
                                    weightKg = 24.0,
                                    orderIndex = 1,
                                ),
                            ),
                        canStart = true,
                        hasActiveSession = false,
                    ),
                onBack = {},
                onStart = {},
            )
        }
    }
}
