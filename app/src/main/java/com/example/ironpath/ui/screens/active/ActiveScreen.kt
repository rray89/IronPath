package com.example.ironpath.ui.screens.active

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.domain.session.SessionSetInput
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerLow

// -- Production entry point --

@Composable
fun ActiveScreen(
    onNavigateToPlan: () -> Unit,
    onWorkoutComplete: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActiveViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val elapsed by viewModel.elapsedSeconds.collectAsStateWithLifecycle()

    ActiveContent(
        uiState = uiState,
        elapsedSeconds = elapsed,
        nowMillis = viewModel::nowMillis,
        onNavigateToPlan = onNavigateToPlan,
        onStartSession = viewModel::startSession,
        onUpdateSet = viewModel::updateSet,
        onAddSet = viewModel::addExtraSet,
        onFinishWorkout = { viewModel.finishWorkout(onWorkoutComplete) },
        modifier = modifier,
    )
}

// -- Pure render composable --

@Composable
internal fun ActiveContent(
    uiState: ActiveUiState,
    elapsedSeconds: Long,
    nowMillis: () -> Long,
    onNavigateToPlan: () -> Unit,
    onStartSession: (PlannedWorkout) -> Unit,
    onUpdateSet: (SessionSet) -> Unit,
    onAddSet: (String, Int) -> Unit,
    onFinishWorkout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        ActiveUiState.Loading -> {
            Box(
                modifier.fillMaxSize().testTag(TestTags.ACTIVE_LOADING),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        ActiveUiState.NoPlan -> ActiveNoPlanState(onNavigateToPlan, modifier)
        is ActiveUiState.RestDay -> ActiveRestDayState(uiState.nextWorkoutDay, modifier)
        is ActiveUiState.ReadyToStart -> ActiveReadyState(uiState.workout, onStartSession, modifier)
        is ActiveUiState.InSession ->
            ActiveSessionState(
                session = uiState.session,
                exercises = uiState.exercises,
                sets = uiState.sets,
                elapsedSeconds = elapsedSeconds,
                nowMillis = nowMillis,
                onUpdateSet = onUpdateSet,
                onAddSet = onAddSet,
                onFinishWorkout = onFinishWorkout,
                modifier = modifier,
            )
    }
}

// -- No Plan State --

@Composable
private fun ActiveNoPlanState(
    onNavigateToPlan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.size(80.dp).background(SurfaceContainerHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.FitnessCenter,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "NO WORKOUT READY YET",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Create your next week in Plan\nbefore starting a session.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))

        GreenGradientButton(
            text = "Open Plan",
            onClick = onNavigateToPlan,
        )
    }
}

// -- Rest Day State --

@Composable
private fun ActiveRestDayState(
    nextWorkoutDay: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier.size(80.dp).background(SurfaceContainerHigh, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.FitnessCenter,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(24.dp))

        Text(
            text = "NO WORKOUT TODAY",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        if (nextWorkoutDay != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your next workout is on $nextWorkoutDay.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -- Ready to Start State --

@Composable
private fun ActiveReadyState(
    workout: PlannedWorkout,
    onStart: (PlannedWorkout) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "TODAY'S WORKOUT",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = workout.title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(24.dp))

        GreenGradientButton(
            text = "Start Workout",
            onClick = { onStart(workout) },
        )
    }
}

// -- In-Session State --

@Composable
private fun ActiveSessionState(
    session: ActiveSession,
    exercises: List<SessionExercise>,
    sets: List<SessionSet>,
    elapsedSeconds: Long,
    nowMillis: () -> Long,
    onUpdateSet: (SessionSet) -> Unit,
    onAddSet: (String, Int) -> Unit,
    onFinishWorkout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        // Header: title + timer
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.workoutTitle.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatElapsed(elapsedSeconds),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Exercise list
        exercises.forEachIndexed { index, exercise ->
            val exerciseSets =
                sets.filter { it.sessionExerciseId == exercise.id }.sortedBy { it.setNumber }

            ExerciseSection(
                index = index + 1,
                exercise = exercise,
                sets = exerciseSets,
                onUpdateSet = onUpdateSet,
                nowMillis = nowMillis,
                onAddSet = { onAddSet(exercise.id, exerciseSets.size) },
            )

            Spacer(Modifier.height(20.dp))
        }

        Spacer(Modifier.height(16.dp))

        // Finish Workout button
        GreenGradientButton(
            text = "Complete Workout",
            onClick = onFinishWorkout,
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun ExerciseSection(
    index: Int,
    exercise: SessionExercise,
    sets: List<SessionSet>,
    onUpdateSet: (SessionSet) -> Unit,
    nowMillis: () -> Long,
    onAddSet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Exercise name
        Text(
            text = "$index. ${exercise.name}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        // Column headers (spacing matches SetRow layout)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SET",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(36.dp)
            )
            Text(
                "TARGET",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                "KG",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "REPS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Box(modifier = Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "DONE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Set rows
        sets.forEach { set ->
            SetRow(
                set = set,
                planInfo = "${exercise.plannedReps}reps@${exercise.plannedWeightKg.toInt()}kg",
                onUpdateSet = onUpdateSet,
                nowMillis = nowMillis,
            )
            Spacer(Modifier.height(4.dp))
        }

        // Add Set
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onAddSet)
                    .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add set",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "ADD SET",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SetRow(
    set: SessionSet,
    planInfo: String,
    onUpdateSet: (SessionSet) -> Unit,
    nowMillis: () -> Long,
    modifier: Modifier = Modifier,
) {
    val isDone = set.reps != null && set.weightKg != null

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(TestTags.set(set.id))
                .semantics {
                    if (set.isExtra) {
                        stateDescription = "Extra set"
                    }
                }
                .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Set number
        Text(
            text = set.setNumber.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color =
                if (set.isExtra) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(36.dp),
        )

        // Plan
        Text(
            text = planInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )

        // Weight input
        CompactNumberField(
            value =
                set.weightKg?.let {
                    if (it == it.toLong().toDouble()) it.toInt().toString() else it.toString()
                } ?: "",
            onValueChange = { text ->
                onUpdateSet(SessionSetInput.withWeight(set, text, nowMillis()))
            },
            modifier = Modifier.weight(1f).testTag(TestTags.setWeight(set.id)),
        )

        Spacer(Modifier.width(4.dp))

        // Reps input
        CompactNumberField(
            value = set.reps?.toString() ?: "",
            onValueChange = { text ->
                onUpdateSet(SessionSetInput.withReps(set, text, nowMillis()))
            },
            modifier = Modifier.weight(1f).testTag(TestTags.setReps(set.id)),
        )

        // Done indicator (passive — auto-filled when both kg and reps are entered)
        Box(
            modifier = Modifier.width(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Set complete",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun CompactNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Use TextFieldValue to preserve cursor position across recompositions
    var tfv by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }

    // Sync when external value changes (pre-fill or DB roundtrip)
    if (tfv.text != value) {
        tfv = TextFieldValue(value, TextRange(value.length))
    }

    var isFocused by remember { mutableStateOf(false) }
    val borderColor =
        if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    BasicTextField(
        value = tfv,
        onValueChange = { newTfv ->
            tfv = newTfv
            onValueChange(newTfv.text)
        },
        modifier =
            modifier
                .height(40.dp)
                .onFocusChanged { isFocused = it.isFocused }
                .border(1.dp, borderColor, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp),
        textStyle =
            MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart,
            ) {
                innerTextField()
            }
        },
    )
}

private fun formatElapsed(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

// -- Previews --

@Preview(showBackground = true)
@Composable
private fun PreviewActiveNoPlan() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ActiveContent(
                uiState = ActiveUiState.NoPlan,
                elapsedSeconds = 0,
                nowMillis = { 1L },
                onNavigateToPlan = {},
                onStartSession = {},
                onUpdateSet = {},
                onAddSet = { _, _ -> },
                onFinishWorkout = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewActiveRestDay() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ActiveContent(
                uiState = ActiveUiState.RestDay(nextWorkoutDay = "Wednesday"),
                elapsedSeconds = 0,
                nowMillis = { 1L },
                onNavigateToPlan = {},
                onStartSession = {},
                onUpdateSet = {},
                onAddSet = { _, _ -> },
                onFinishWorkout = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewActiveInSession() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ActiveContent(
                uiState =
                    ActiveUiState.InSession(
                        session =
                            ActiveSession(
                                id = "s1",
                                sourcePlannedWorkoutId = "w1",
                                workoutTitle = "Push A",
                                startedAt = 1L,
                                lastUpdatedAt = 1L,
                            ),
                        exercises =
                            listOf(
                                SessionExercise(
                                    id = "e1",
                                    activeSessionId = "s1",
                                    name = "Barbell Squat",
                                    plannedSets = 3,
                                    plannedReps = 5,
                                    plannedWeightKg = 100.0,
                                    orderIndex = 0
                                ),
                                SessionExercise(
                                    id = "e2",
                                    activeSessionId = "s1",
                                    name = "Bench Press",
                                    plannedSets = 3,
                                    plannedReps = 8,
                                    plannedWeightKg = 80.0,
                                    orderIndex = 1
                                ),
                            ),
                        sets =
                            listOf(
                                SessionSet(
                                    id = "s1",
                                    sessionExerciseId = "e1",
                                    setNumber = 1,
                                    reps = 5,
                                    weightKg = 100.0,
                                    completedAt = 1L
                                ),
                                SessionSet(
                                    id = "s2",
                                    sessionExerciseId = "e1",
                                    setNumber = 2,
                                    weightKg = 100.0
                                ),
                                SessionSet(
                                    id = "s3",
                                    sessionExerciseId = "e1",
                                    setNumber = 3,
                                    weightKg = 100.0
                                ),
                                SessionSet(
                                    id = "s4",
                                    sessionExerciseId = "e2",
                                    setNumber = 1,
                                    weightKg = 80.0
                                ),
                            ),
                    ),
                elapsedSeconds = 2892,
                nowMillis = { 1L },
                onNavigateToPlan = {},
                onStartSession = {},
                onUpdateSet = {},
                onAddSet = { _, _ -> },
                onFinishWorkout = {},
            )
        }
    }
}
