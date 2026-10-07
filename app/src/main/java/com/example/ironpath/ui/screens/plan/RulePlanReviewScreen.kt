package com.example.ironpath.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.RuleExerciseForm
import com.example.ironpath.domain.planner.RuleExerciseRemoval
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.screens.home.dayOfWeekAbbrev
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import java.time.DayOfWeek
import kotlin.math.abs

private data class RuleEditorRequest(val workoutId: String, val exerciseId: String? = null)

@Composable
internal fun RulePlanReviewScreen(
    generated: GeneratedPlan,
    isSaving: Boolean,
    onDeleteWorkout: (String) -> Unit,
    onBackToSetup: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    onMoveWorkout: (String, Int) -> Unit = { _, _ -> },
    onEditExercise: (String, RuleExerciseForm) -> Unit = { _, _ -> },
    onAddExercise: (String, RuleExerciseForm) -> Unit = { _, _ -> },
    onRemoveExercise: (String) -> Unit = {},
    onMoveExercise: (String, String, Int) -> Unit = { _, _, _ -> },
    suggestions: List<String> = emptyList(),
    undo: RuleExerciseRemoval? = null,
    onUndo: () -> Unit = {},
    onUndoExpired: (RuleExerciseRemoval) -> Unit = {},
) {
    var dayRequest by remember(generated.plan.id) { mutableStateOf<String?>(null) }
    var editorRequest by remember(generated.plan.id) { mutableStateOf<RuleEditorRequest?>(null) }
    var reorderRequest by remember(generated.plan.id) { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val currentUndo by rememberUpdatedState(onUndo)
    val currentExpire by rememberUpdatedState(onUndoExpired)
    LaunchedEffect(undo, isSaving) {
        val removal = undo ?: return@LaunchedEffect
        if (isSaving) return@LaunchedEffect
        val result =
            snackbar.showSnackbar(
                "Removed ${removal.exerciseName.take(80)}",
                actionLabel = "Undo",
                withDismissAction = true,
            )
        if (result == SnackbarResult.ActionPerformed) currentUndo() else currentExpire(removal)
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(16.dp))
            Text(
                "WEEKLY PLAN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text("THIS WEEK", style = MaterialTheme.typography.headlineMedium)
            Text(
                "${generated.plan.startDate} – ${generated.plan.endDate}",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))
            if (generated.workouts.isEmpty())
                Text("No workouts left. Regenerate to make a new draft.")
            generated.workouts
                .sortedBy { it.dayOfWeek }
                .forEach { workout ->
                    key(workout.id) {
                        RuleWorkoutCard(
                            workout,
                            generated.exercises
                                .filter { it.plannedWorkoutId == workout.id }
                                .sortedBy { it.orderIndex },
                            enabled = !isSaving,
                            onDay = { dayRequest = workout.id },
                            onDelete = { onDeleteWorkout(workout.id) },
                            onEdit = { editorRequest = RuleEditorRequest(workout.id, it) },
                            onAdd = { editorRequest = RuleEditorRequest(workout.id) },
                            onRemove = onRemoveExercise,
                            onMove = { exerciseId, index ->
                                onMoveExercise(workout.id, exerciseId, index)
                            },
                            onReorderMenu = { reorderRequest = it },
                        )
                        Spacer(Modifier.height(20.dp))
                    }
                }
            Spacer(Modifier.height(12.dp))
            TextButton(
                onClick = onBackToSetup,
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth().background(SurfaceContainerHigh)
            ) {
                Text("REGENERATE")
            }
            Spacer(Modifier.height(12.dp))
            GreenGradientButton(
                "Accept Plan",
                onAccept,
                enabled = generated.workouts.isNotEmpty() && !isSaving
            )
            Spacer(Modifier.height(32.dp))
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(16.dp).testTag("rule_review_undo")
        )
    }
    if (!isSaving) {
        dayRequest?.let { id ->
            generated.workouts
                .find { it.id == id }
                ?.let { workout ->
                    RuleReviewDialog(onDismiss = { dayRequest = null }) {
                        RuleDayPickerContent(
                            workout,
                            generated.workouts,
                            onSelect = { day ->
                                dayRequest = null
                                onMoveWorkout(id, day)
                            },
                            onCancel = { dayRequest = null }
                        )
                    }
                }
        }
        editorRequest?.let { request ->
            val workout = generated.workouts.find { it.id == request.workoutId }
            val original = generated.exercises.find { it.id == request.exerciseId }
            if (workout != null && (request.exerciseId == null || original != null)) {
                RuleReviewDialog(onDismiss = { editorRequest = null }) {
                    RuleExerciseEditorContent(
                        original,
                        suggestions,
                        onCancel = { editorRequest = null },
                        onSave = { form ->
                            editorRequest = null
                            if (original == null) onAddExercise(workout.id, form)
                            else onEditExercise(original.id, form)
                        }
                    )
                }
            }
        }
        reorderRequest?.let { id ->
            generated.exercises
                .find { it.id == id }
                ?.let { exercise ->
                    val exercises =
                        generated.exercises
                            .filter { it.plannedWorkoutId == exercise.plannedWorkoutId }
                            .sortedBy { it.orderIndex }
                    val index = exercises.indexOfFirst { it.id == id }
                    RuleReviewDialog(onDismiss = { reorderRequest = null }) {
                        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                            Text(
                                "Reorder ${exercise.name}",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text("Position ${index + 1} of ${exercises.size}")
                            TextButton(
                                onClick = {
                                    reorderRequest = null
                                    onMoveExercise(exercise.plannedWorkoutId, id, index - 1)
                                },
                                enabled = index > 0
                            ) {
                                Text("Move up")
                            }
                            TextButton(
                                onClick = {
                                    reorderRequest = null
                                    onMoveExercise(exercise.plannedWorkoutId, id, index + 1)
                                },
                                enabled = index < exercises.lastIndex
                            ) {
                                Text("Move down")
                            }
                            TextButton(onClick = { reorderRequest = null }) { Text("Cancel") }
                        }
                    }
                }
        }
    }
}

@Composable
private fun RuleWorkoutCard(
    workout: PlannedWorkout,
    exercises: List<PlannedExercise>,
    enabled: Boolean,
    onDay: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
    onReorderMenu: (String) -> Unit,
) {
    val bounds = remember(workout.id) { mutableMapOf<String, Rect>() }
    val latestExercises by rememberUpdatedState(exercises)
    val latestMove by rememberUpdatedState(onMove)
    var draggingId by remember(workout.id) { mutableStateOf<String?>(null) }
    Column(Modifier.testTag(TestTags.workout(workout.id))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onDay,
                enabled = enabled,
                modifier =
                    Modifier.testTag(TestTags.planReviewDay(workout.id)).semantics {
                        contentDescription =
                            "Change day for ${workout.title}, ${fullDay(workout.dayOfWeek)}"
                    }
            ) {
                Text(dayOfWeekAbbrev(workout.dayOfWeek))
            }
            Text(workout.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(onDelete, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.Close,
                    "Remove ${workout.title} on ${fullDay(workout.dayOfWeek)}",
                    Modifier.size(18.dp)
                )
            }
        }
        exercises.forEachIndexed { index, exercise ->
            key(exercise.id) {
                var handleBounds by remember { mutableStateOf(Rect.Zero) }
                var dragY by remember { mutableFloatStateOf(0f) }
                Row(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { bounds[exercise.id] = it.boundsInRoot() }
                        .background(
                            if (draggingId == exercise.id) SurfaceContainerHigh
                            else MaterialTheme.colorScheme.surface
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        Modifier.weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag(TestTags.planExercise(exercise.id))
                            .clickable(
                                enabled = enabled,
                                role = Role.Button,
                                onClickLabel = "Edit ${exercise.name}"
                            ) {
                                onEdit(exercise.id)
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(exercise.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${exercise.sets}×${exercise.reps} · ${if (exercise.weightKg == 0.0) "BW" else "${exercise.weightKg.toString().removeSuffix(".0")}kg"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        Modifier.size(48.dp)
                            .testTag("rule_drag_${exercise.id}")
                            .onGloballyPositioned { handleBounds = it.boundsInRoot() }
                            .clickable(enabled = enabled, role = Role.Button) {
                                onReorderMenu(exercise.id)
                            }
                            .semantics {
                                contentDescription = "Reorder ${exercise.name} in ${workout.title}"
                                stateDescription = "Position ${index + 1} of ${exercises.size}"
                                customActions =
                                    if (!enabled) emptyList()
                                    else
                                        buildList {
                                            if (index > 0)
                                                add(
                                                    CustomAccessibilityAction("Move up") {
                                                        latestMove(
                                                            exercise.id,
                                                            latestExercises.indexOfFirst {
                                                                it.id == exercise.id
                                                            } - 1
                                                        )
                                                        true
                                                    }
                                                )
                                            if (index < exercises.lastIndex)
                                                add(
                                                    CustomAccessibilityAction("Move down") {
                                                        latestMove(
                                                            exercise.id,
                                                            latestExercises.indexOfFirst {
                                                                it.id == exercise.id
                                                            } + 1
                                                        )
                                                        true
                                                    }
                                                )
                                        }
                            }
                            .pointerInput(exercise.id, enabled) {
                                var firstDrag = false
                                if (enabled)
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            draggingId = exercise.id
                                            dragY = handleBounds.top + offset.y
                                            firstDrag = true
                                        },
                                        onDragEnd = { draggingId = null },
                                        onDragCancel = { draggingId = null },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            if (firstDrag) firstDrag = false else dragY += amount.y
                                            val target =
                                                latestExercises
                                                    .mapIndexedNotNull { i, item ->
                                                        bounds[item.id]?.let {
                                                            i to abs(it.center.y - dragY)
                                                        }
                                                    }
                                                    .minByOrNull { it.second }
                                                    ?.first
                                            val source =
                                                latestExercises.indexOfFirst {
                                                    it.id == exercise.id
                                                }
                                            if (target != null && target != source)
                                                latestMove(exercise.id, target)
                                        },
                                    )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.DragHandle, null)
                    }
                    IconButton(
                        { onRemove(exercise.id) },
                        enabled = enabled,
                        modifier = Modifier.size(48.dp).testTag("rule_remove_${exercise.id}")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            "Remove ${exercise.name} from ${workout.title}",
                            Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        TextButton(
            onAdd,
            enabled = enabled,
            modifier =
                Modifier.testTag("rule_add_${workout.id}").semantics {
                    contentDescription = "Add exercise to ${workout.title}"
                }
        ) {
            Text("Add Exercise")
        }
    }
}

@Composable
private fun RuleReviewDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val windowHeight =
        with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().heightIn(max = (windowHeight - 48.dp).coerceAtLeast(120.dp)),
            shape = RoundedCornerShape(4.dp),
            color = SurfaceContainerHigh
        ) {
            content()
        }
    }
}

@Composable
internal fun RuleDayPickerContent(
    workout: PlannedWorkout,
    workouts: List<PlannedWorkout>,
    onSelect: (Int) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("rule_day_picker")
    ) {
        Text("Move ${workout.title}", style = MaterialTheme.typography.titleLarge)
        Text("Choose a day in this week. An occupied day swaps both workouts.")
        (1..7).forEach { day ->
            val other = workouts.find { it.dayOfWeek == day && it.id != workout.id }
            val status =
                if (day == workout.dayOfWeek) "Current day"
                else other?.let { "Swap with ${it.title}" } ?: "Empty day"
            Column(
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("rule_day_$day")
                    .selectable(
                        day == workout.dayOfWeek,
                        role = Role.RadioButton,
                        onClick = { onSelect(day) }
                    )
                    .semantics { stateDescription = status }
                    .padding(vertical = 12.dp)
            ) {
                Text(fullDay(day))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
}

@Composable
internal fun RuleExerciseEditorContent(
    original: PlannedExercise?,
    suggestions: List<String>,
    onCancel: () -> Unit,
    onSave: (RuleExerciseForm) -> Unit
) {
    var form by
        remember(original?.id) {
            mutableStateOf(
                RuleExerciseForm(
                    original?.name ?: "",
                    original?.sets?.toString() ?: "3",
                    original?.reps?.toString() ?: "10",
                    original?.weightKg?.toString() ?: "0"
                )
            )
        }
    var submitted by remember(original?.id) { mutableStateOf(false) }
    val errors = form.validate()
    Column(
        Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
            .testTag("rule_exercise_editor"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (original == null) "Add exercise" else "Edit exercise",
            style = MaterialTheme.typography.titleLarge
        )
        RuleExerciseField(
            form.name,
            "Exercise name",
            "rule_name",
            if (submitted) errors.nameError else null,
            KeyboardType.Text
        ) {
            form = form.copy(name = it)
        }
        if (form.name.isNotBlank()) {
            suggestions
                .filter {
                    it.contains(form.name.trim(), ignoreCase = true) &&
                        !it.equals(form.name.trim(), ignoreCase = true)
                }
                .take(4)
                .forEach { suggestion ->
                    TextButton({ form = form.copy(name = suggestion) }) { Text(suggestion) }
                }
        }
        RuleExerciseField(
            form.sets,
            "Sets (1–20)",
            "rule_sets",
            if (submitted) errors.setsError else null,
            KeyboardType.Number
        ) {
            form = form.copy(sets = it)
        }
        RuleExerciseField(
            form.reps,
            "Reps (1–100)",
            "rule_reps",
            if (submitted) errors.repsError else null,
            KeyboardType.Number
        ) {
            form = form.copy(reps = it)
        }
        RuleExerciseField(
            form.weight,
            "Weight (kg)",
            "rule_weight",
            if (submitted) errors.weightError else null,
            KeyboardType.Decimal
        ) {
            form = form.copy(weight = it)
        }
        TextButton(onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        Button(
            {
                submitted = true
                if (form.validate().values != null) onSave(form)
            },
            modifier = Modifier.fillMaxWidth().testTag("rule_save")
        ) {
            Text("Save exercise")
        }
    }
}

@Composable
private fun RuleExerciseField(
    value: String,
    label: String,
    tag: String,
    fieldError: String?,
    keyboard: KeyboardType,
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value,
        onChange,
        Modifier.fillMaxWidth().testTag(tag).semantics {
            contentDescription = label
            if (fieldError != null) error(fieldError)
        },
        label = { Text(label) },
        isError = fieldError != null,
        supportingText =
            if (fieldError != null) {
                { Text(fieldError) }
            } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        singleLine = true
    )
}

private fun fullDay(day: Int) =
    DayOfWeek.of(day).name.lowercase().replaceFirstChar { it.uppercaseChar() }
