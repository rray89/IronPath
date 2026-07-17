package com.example.ironpath.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.TrainingGoal
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.screens.home.dayOfWeekAbbrev
import com.example.ironpath.ui.theme.IronPathTheme
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerHighest

// -- Production entry point --

@Composable
fun PlanScreen(
    onPlanAccepted: () -> Unit,
    onStartWorkout: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val uiState by viewModel.planUiState.collectAsStateWithLifecycle()
    val selectedGoal by viewModel.selectedGoal.collectAsStateWithLifecycle()
    val selectedDays by viewModel.selectedDays.collectAsStateWithLifecycle()
    val undoExercise by viewModel.undoExercise.collectAsStateWithLifecycle()
    val exerciseSuggestions by viewModel.exerciseSuggestions.collectAsStateWithLifecycle()

    PlanContent(
        uiState = uiState,
        selectedGoal = selectedGoal,
        selectedDays = selectedDays,
        undoExercise = undoExercise,
        exerciseSuggestions = exerciseSuggestions,
        onGoalSelected = viewModel::setGoal,
        onDayToggled = viewModel::toggleDay,
        onGenerate = viewModel::generatePlan,
        onDeleteWorkout = viewModel::deleteWorkoutFromReview,
        onReassignDay = viewModel::reassignWorkoutDay,
        onEditExercise = viewModel::updateExerciseInReview,
        onRemoveExercise = viewModel::removeExerciseFromReview,
        onAddExercise = viewModel::addExerciseToReview,
        onMoveExercise = viewModel::moveExerciseInReview,
        onUndoRemove = viewModel::undoRemoveExercise,
        onClearUndo = viewModel::clearUndo,
        onBackToSetup = viewModel::backToSetup,
        onAccept = { viewModel.acceptPlan(onPlanAccepted) },
        onStartWorkout = onStartWorkout,
        onOpenWorkoutPreview = onOpenWorkoutPreview,
        modifier = modifier,
    )
}

// -- Pure render composable --

@Composable
internal fun PlanContent(
    uiState: PlanUiState,
    selectedGoal: TrainingGoal,
    selectedDays: Set<Int>,
    undoExercise: Pair<PlannedExercise, PlannedWorkout?>?,
    exerciseSuggestions: List<String>,
    onGoalSelected: (TrainingGoal) -> Unit,
    onDayToggled: (Int) -> Unit,
    onGenerate: () -> Unit,
    onDeleteWorkout: (String) -> Unit,
    onReassignDay: (String, Int) -> Unit,
    onEditExercise: (PlannedExercise) -> Unit,
    onRemoveExercise: (String) -> Unit,
    onAddExercise: (String, PlannedExercise) -> Unit,
    onMoveExercise: (String, Int) -> Unit,
    onUndoRemove: () -> Unit,
    onClearUndo: () -> Unit,
    onBackToSetup: () -> Unit,
    onAccept: () -> Unit,
    onStartWorkout: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (uiState) {
        PlanUiState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        PlanUiState.Setup ->
            PlanSetupScreen(
                selectedGoal = selectedGoal,
                selectedDays = selectedDays,
                onGoalSelected = onGoalSelected,
                onDayToggled = onDayToggled,
                onGenerate = onGenerate,
                modifier = modifier,
            )
        is PlanUiState.Review ->
            PlanReviewScreen(
                generated = uiState.generated,
                undoExercise = undoExercise,
                exerciseSuggestions = exerciseSuggestions,
                onDeleteWorkout = onDeleteWorkout,
                onReassignDay = onReassignDay,
                onEditExercise = onEditExercise,
                onRemoveExercise = onRemoveExercise,
                onAddExercise = onAddExercise,
                onMoveExercise = onMoveExercise,
                onUndoRemove = onUndoRemove,
                onClearUndo = onClearUndo,
                onBackToSetup = onBackToSetup,
                onAccept = onAccept,
                modifier = modifier,
            )
        is PlanUiState.Accepted ->
            PlanAcceptedScreen(
                planned = uiState.planned,
                completed = uiState.completed,
                workouts = uiState.workouts,
                todayWorkout = uiState.todayWorkout,
                nextWorkout = uiState.nextWorkout,
                hasActiveSession = uiState.hasActiveSession,
                onStartWorkout = onStartWorkout,
                onOpenWorkoutPreview = onOpenWorkoutPreview,
                modifier = modifier,
            )
    }
}

// -- Setup Screen --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSetupScreen(
    selectedGoal: TrainingGoal,
    selectedDays: Set<Int>,
    onGoalSelected: (TrainingGoal) -> Unit,
    onDayToggled: (Int) -> Unit,
    onGenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Primary Goal",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrainingGoal.entries.forEach { goal ->
                GoalChip(
                    label = goal.name.uppercase(),
                    selected = goal == selectedGoal,
                    onClick = { onGoalSelected(goal) },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Workout Days",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val dayLabels = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
            dayLabels.forEachIndexed { index, label ->
                val dow = index + 1
                DayChip(
                    label = label,
                    selected = dow in selectedDays,
                    onClick = { onDayToggled(dow) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "SELECT THE DAYS YOU WANT TO TRAIN THIS WEEK",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        GreenGradientButton(
            text = "Generate Week",
            onClick = onGenerate,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.surface,
                )
            },
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun GoalChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val bgColor = if (selected) MaterialTheme.colorScheme.primary else SurfaceContainerHigh
    val textColor =
        if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(bgColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = textColor,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
}

@Composable
private fun DayChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(4.dp)
    val bgColor = if (selected) MaterialTheme.colorScheme.primary else SurfaceContainerHigh
    val textColor =
        if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface

    Box(
        modifier =
            modifier
                .clip(shape)
                .background(bgColor)
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
    }
}

// -- Review Screen --

@Composable
private fun PlanReviewScreen(
    generated: GeneratedPlan,
    undoExercise: Pair<PlannedExercise, PlannedWorkout?>?,
    exerciseSuggestions: List<String>,
    onDeleteWorkout: (String) -> Unit,
    onReassignDay: (String, Int) -> Unit,
    onEditExercise: (PlannedExercise) -> Unit,
    onRemoveExercise: (String) -> Unit,
    onAddExercise: (String, PlannedExercise) -> Unit,
    onMoveExercise: (String, Int) -> Unit,
    onUndoRemove: () -> Unit,
    onClearUndo: () -> Unit,
    onBackToSetup: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val occupiedDays = generated.workouts.map { it.dayOfWeek }.toSet()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(undoExercise) {
        if (undoExercise != null) {
            val result =
                snackbarHostState.showSnackbar(
                    message = "Exercise removed",
                    actionLabel = "UNDO",
                    duration = SnackbarDuration.Short,
                )
            when (result) {
                SnackbarResult.ActionPerformed -> onUndoRemove()
                SnackbarResult.Dismissed -> onClearUndo()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))

            Text(
                text = "WEEKLY PLAN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "THIS WEEK",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(24.dp))

            generated.workouts.forEach { workout ->
                val exercises =
                    generated.exercises
                        .filter { it.plannedWorkoutId == workout.id }
                        .sortedBy { it.orderIndex }
                ReviewWorkoutCard(
                    workout = workout,
                    exercises = exercises,
                    occupiedDays = occupiedDays,
                    suggestions = exerciseSuggestions,
                    onDelete = { onDeleteWorkout(workout.id) },
                    onReassignDay = { newDay -> onReassignDay(workout.id, newDay) },
                    onEditExercise = onEditExercise,
                    onRemoveExercise = onRemoveExercise,
                    onAddExercise = { exercise -> onAddExercise(workout.id, exercise) },
                    onMoveExercise = onMoveExercise,
                )
                Spacer(Modifier.height(20.dp))
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier =
                        Modifier.weight(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SurfaceContainerHigh)
                            .clickable(onClick = onBackToSetup)
                            .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "REGENERATE",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                GreenGradientButton(
                    text = "Accept Plan",
                    onClick = onAccept,
                    enabled = generated.workouts.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(32.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ReviewWorkoutCard(
    workout: PlannedWorkout,
    exercises: List<PlannedExercise>,
    occupiedDays: Set<Int>,
    suggestions: List<String>,
    onDelete: () -> Unit,
    onReassignDay: (Int) -> Unit,
    onEditExercise: (PlannedExercise) -> Unit,
    onRemoveExercise: (String) -> Unit,
    onAddExercise: (PlannedExercise) -> Unit,
    onMoveExercise: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDayPicker by remember { mutableStateOf(false) }
    var editingExercise by remember { mutableStateOf<PlannedExercise?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    if (showDayPicker) {
        DayPickerDialog(
            currentDayOfWeek = workout.dayOfWeek,
            occupiedDays = occupiedDays,
            onDaySelected = onReassignDay,
            onDismiss = { showDayPicker = false },
        )
    }

    editingExercise?.let { ex ->
        ExerciseEditorDialog(
            existingExercise = ex,
            suggestions = suggestions,
            onSave = { updated ->
                onEditExercise(updated)
                editingExercise = null
            },
            onDismiss = { editingExercise = null },
        )
    }

    if (showAddDialog) {
        ExerciseEditorDialog(
            existingExercise = null,
            suggestions = suggestions,
            onSave = { new ->
                onAddExercise(new)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = dayOfWeekAbbrev(workout.dayOfWeek),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showDayPicker = true },
            )
            Text(
                text = " — ${workout.title}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove workout",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        exercises.forEachIndexed { index, exercise ->
            ReviewExerciseRow(
                exercise = exercise,
                isFirst = index == 0,
                isLast = index == exercises.lastIndex,
                onClick = { editingExercise = exercise },
                onRemove = { onRemoveExercise(exercise.id) },
                onMoveUp = { onMoveExercise(exercise.id, -1) },
                onMoveDown = { onMoveExercise(exercise.id, +1) },
            )
            Spacer(Modifier.height(4.dp))
        }

        // Add Exercise button
        TextButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "ADD EXERCISE",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ReviewExerciseRow(
    exercise: PlannedExercise,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Up / Down arrows
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            IconButton(
                onClick = onMoveUp,
                enabled = !isFirst,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move up",
                    modifier = Modifier.size(14.dp),
                    tint =
                        if (!isFirst) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
            IconButton(
                onClick = onMoveDown,
                enabled = !isLast,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move down",
                    modifier = Modifier.size(14.dp),
                    tint =
                        if (!isLast) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
        }

        Spacer(Modifier.width(6.dp))

        // Exercise name
        Text(
            text = exercise.name,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )

        // Compact specs
        val weightText = if (exercise.weightKg > 0) "${exercise.weightKg.toInt()}kg" else "BW"
        Text(
            text = "${exercise.sets}×${exercise.reps} · $weightText",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Remove button
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove exercise",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExerciseEditorDialog(
    existingExercise: PlannedExercise?,
    suggestions: List<String>,
    onSave: (PlannedExercise) -> Unit,
    onDismiss: () -> Unit,
) {
    val isEditMode = existingExercise != null
    var exerciseName by remember { mutableStateOf(existingExercise?.name ?: "") }
    var sets by remember { mutableStateOf(existingExercise?.sets?.toString() ?: "") }
    var reps by remember { mutableStateOf(existingExercise?.reps?.toString() ?: "") }
    var weight by remember { mutableStateOf(existingExercise?.weightKg?.toString() ?: "") }

    val setsInt = sets.toIntOrNull()
    val repsInt = reps.toIntOrNull()
    val weightDouble = weight.toDoubleOrNull()
    val isValid =
        exerciseName.isNotBlank() &&
            setsInt != null &&
            setsInt in 1..20 &&
            repsInt != null &&
            repsInt in 1..100 &&
            weightDouble != null &&
            weightDouble >= 0.0

    val filteredSuggestions =
        if (exerciseName.isNotBlank()) {
            suggestions.filter { it.contains(exerciseName, ignoreCase = true) }.take(5)
        } else emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainerHighest,
        title = {
            Text(
                text = if (isEditMode) "EDIT EXERCISE" else "ADD EXERCISE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Exercise name + suggestions
                Column {
                    OutlinedTextField(
                        value = exerciseName,
                        onValueChange = { exerciseName = it },
                        label = { Text("Exercise Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    filteredSuggestions.forEach { suggestion ->
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .clickable { exerciseName = suggestion }
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                        )
                    }
                }

                // Sets / Reps row
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sets,
                        onValueChange = { sets = it },
                        label = { Text("Sets") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = reps,
                        onValueChange = { reps = it },
                        label = { Text("Reps") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                // Weight
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("Weight (kg)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val saved =
                        if (isEditMode) {
                            existingExercise.copy(
                                name = exerciseName.trim(),
                                sets = setsInt!!,
                                reps = repsInt!!,
                                weightKg = weightDouble!!,
                            )
                        } else {
                            PlannedExercise(
                                id = "",
                                plannedWorkoutId = "",
                                name = exerciseName.trim(),
                                sets = setsInt!!,
                                reps = repsInt!!,
                                weightKg = weightDouble!!,
                                orderIndex = 0,
                            )
                        }
                    onSave(saved)
                },
                enabled = isValid,
            ) {
                Text(
                    text = if (isEditMode) "UPDATE" else "ADD",
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (isValid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "CANCEL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayPickerDialog(
    currentDayOfWeek: Int,
    occupiedDays: Set<Int>,
    onDaySelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceContainerHighest,
        title = {
            Text(
                text = "MOVE TO",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        text = {
            val dayLabels = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dayLabels.forEachIndexed { index, label ->
                    val dow = index + 1
                    val isCurrent = dow == currentDayOfWeek
                    val isOccupied = dow in occupiedDays && !isCurrent
                    val bgColor =
                        if (isCurrent) MaterialTheme.colorScheme.primary else SurfaceContainerHigh
                    val textColor =
                        when {
                            isCurrent -> MaterialTheme.colorScheme.surface
                            isOccupied -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    Box(
                        modifier =
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(bgColor)
                                .then(
                                    if (!isCurrent)
                                        Modifier.clickable {
                                            onDaySelected(dow)
                                            onDismiss()
                                        }
                                    else Modifier
                                )
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = textColor)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "CANCEL",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

// -- Accepted/Lightweight Status Screen --

@Composable
private fun PlanAcceptedScreen(
    planned: Int,
    completed: Int,
    workouts: List<PlannedWorkout>,
    todayWorkout: PlannedWorkout?,
    nextWorkout: PlannedWorkout?,
    hasActiveSession: Boolean,
    onStartWorkout: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(24.dp))

        Text(
            text =
                if (hasActiveSession) "SESSION IN PROGRESS"
                else if (todayWorkout != null) "WORKOUT DAY TODAY" else "NO WORKOUT TODAY",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(12.dp))

        if (hasActiveSession) {
            Text(
                text = "Switch to the Active tab to continue your workout.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (nextWorkout != null && todayWorkout == null) {
                Text(
                    text =
                        "Next workout: ${dayOfWeekAbbrev(nextWorkout.dayOfWeek)} · ${nextWorkout.title}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }

            Text(
                text = "$planned workouts planned · $completed completed",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (todayWorkout != null) {
                Spacer(Modifier.height(24.dp))
                GreenGradientButton(text = "Start Workout", onClick = onStartWorkout)
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text = "ACCEPTED WEEK",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))

        workouts
            .sortedBy { it.dayOfWeek }
            .forEach { workout ->
                AcceptedWorkoutRow(
                    workout = workout,
                    onClick = { onOpenWorkoutPreview(workout.id) },
                )
                Spacer(Modifier.height(10.dp))
            }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun AcceptedWorkoutRow(
    workout: PlannedWorkout,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceContainerHigh)
                .clickable(onClick = onClick)
                .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier.background(SurfaceContainerHighest, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayOfWeekAbbrev(workout.dayOfWeek),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workout.title.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = workout.scheduledDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Previews --

private val PreviewPlan =
    WeeklyPlan(
        id = "preview-plan",
        startDate = "2026-03-30",
        endDate = "2026-04-05",
        createdAt = 1L,
    )

private val PreviewWorkouts =
    listOf(
        PlannedWorkout(
            "w1",
            "preview-plan",
            1,
            "2026-03-30",
            "Chest/Tris",
            WorkoutStatus.Completed
        ),
        PlannedWorkout("w2", "preview-plan", 3, "2026-04-01", "Back/Bis", WorkoutStatus.Upcoming),
        PlannedWorkout("w3", "preview-plan", 5, "2026-04-03", "Legs", WorkoutStatus.Upcoming),
    )

private val PreviewExercises =
    listOf(
        PlannedExercise(
            id = "preview-exercise-1",
            plannedWorkoutId = "w1",
            name = "Barbell Bench Press",
            sets = 4,
            reps = 10,
            weightKg = 50.0,
            orderIndex = 0,
        ),
        PlannedExercise(
            id = "preview-exercise-2",
            plannedWorkoutId = "w1",
            name = "Dumbbell Incline Flys",
            sets = 3,
            reps = 12,
            weightKg = 12.0,
            orderIndex = 1,
        ),
        PlannedExercise(
            id = "preview-exercise-3",
            plannedWorkoutId = "w2",
            name = "Barbell Rows",
            sets = 4,
            reps = 10,
            weightKg = 50.0,
            orderIndex = 0,
        ),
        PlannedExercise(
            id = "preview-exercise-4",
            plannedWorkoutId = "w3",
            name = "Barbell Squats",
            sets = 4,
            reps = 10,
            weightKg = 60.0,
            orderIndex = 0,
        ),
    )

@Preview(showBackground = true)
@Composable
private fun PreviewPlanSetup() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PlanContent(
                uiState = PlanUiState.Setup,
                selectedGoal = TrainingGoal.Strength,
                selectedDays = setOf(1, 3, 5),
                undoExercise = null,
                exerciseSuggestions = emptyList(),
                onGoalSelected = {},
                onDayToggled = {},
                onGenerate = {},
                onDeleteWorkout = {},
                onReassignDay = { _, _ -> },
                onEditExercise = {},
                onRemoveExercise = {},
                onAddExercise = { _, _ -> },
                onMoveExercise = { _, _ -> },
                onUndoRemove = {},
                onClearUndo = {},
                onBackToSetup = {},
                onAccept = {},
                onStartWorkout = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPlanReview() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PlanContent(
                uiState =
                    PlanUiState.Review(
                        GeneratedPlan(PreviewPlan, PreviewWorkouts, PreviewExercises)
                    ),
                selectedGoal = TrainingGoal.Hypertrophy,
                selectedDays = setOf(1, 3, 5),
                undoExercise = null,
                exerciseSuggestions = listOf("Barbell Bench Press", "Deadlift", "Squat"),
                onGoalSelected = {},
                onDayToggled = {},
                onGenerate = {},
                onDeleteWorkout = {},
                onReassignDay = { _, _ -> },
                onEditExercise = {},
                onRemoveExercise = {},
                onAddExercise = { _, _ -> },
                onMoveExercise = { _, _ -> },
                onUndoRemove = {},
                onClearUndo = {},
                onBackToSetup = {},
                onAccept = {},
                onStartWorkout = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewPlanAccepted() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PlanContent(
                uiState =
                    PlanUiState.Accepted(
                        planned = 3,
                        completed = 1,
                        workouts = PreviewWorkouts,
                        todayWorkout = null,
                        nextWorkout = PreviewWorkouts[1],
                        hasActiveSession = false,
                    ),
                selectedGoal = TrainingGoal.Strength,
                selectedDays = emptySet(),
                undoExercise = null,
                exerciseSuggestions = emptyList(),
                onGoalSelected = {},
                onDayToggled = {},
                onGenerate = {},
                onDeleteWorkout = {},
                onReassignDay = { _, _ -> },
                onEditExercise = {},
                onRemoveExercise = {},
                onAddExercise = { _, _ -> },
                onMoveExercise = { _, _ -> },
                onUndoRemove = {},
                onClearUndo = {},
                onBackToSetup = {},
                onAccept = {},
                onStartWorkout = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}
