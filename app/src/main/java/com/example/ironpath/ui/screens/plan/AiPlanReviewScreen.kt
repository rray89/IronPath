package com.example.ironpath.ui.screens.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCatalogEntry
import com.example.ironpath.domain.planner.ExerciseCatalogId
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.PlanValidationLimits
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningFailure
import com.example.ironpath.domain.planner.WorkoutDraft
import com.example.ironpath.domain.planner.requiresTargetLoad
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.screens.home.dayOfWeekAbbrev
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.SurfaceContainerHigh
import com.example.ironpath.ui.theme.SurfaceContainerHighest
import kotlinx.coroutines.launch

private data class ExerciseEditorRequest(
    val workoutDay: Int,
    val original: ExerciseDraft?,
)

@Composable
internal fun AiPlanReviewScreen(
    state: AiPlanReviewUiState,
    onAddExercise: (Int, ExerciseDraft) -> Unit,
    onReplaceExercise: (Int, ExerciseCatalogId, ExerciseDraft) -> Unit,
    onEditInputs: () -> Unit,
    onRegenerate: () -> Unit,
    onUseRuleFallback: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    generationState: AiGenerationUiState = AiGenerationUiState.Idle,
    onCancelGeneration: () -> Unit = {},
    onClearGeneration: () -> Unit = {},
) {
    var editorRequest by
        remember(state.sourceToken) { mutableStateOf<ExerciseEditorRequest?>(null) }
    val draft = state.review.draft
    val entriesById = state.eligibleExercises.associateBy(ExerciseCatalogEntry::id)
    val replacementInProgress = generationState is AiGenerationUiState.Generating
    val controlsEnabled = !state.isAccepting && !replacementInProgress

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .testTag(TestTags.PLAN_AI_REVIEW)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "${draft.providerMetadata.engineType.providerLabel()} · GENERATED PLAN",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "REVIEW YOUR WEEK",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Training guidance only. This is not medical advice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TestTags.PLAN_AI_DISCLAIMER),
        )

        AiReplacementStatus(
            state = generationState,
            onCancel = onCancelGeneration,
            onClear = onClearGeneration,
        )

        draft.rationale?.takeIf(String::isNotBlank)?.let { rationale ->
            Spacer(Modifier.height(20.dp))
            Text(
                text = "WHY THIS WEEK",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(TestTags.PLAN_AI_RATIONALE),
            )
        }

        val messages = draft.warnings + state.review.violations.map { it.message }
        if (messages.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (state.canAccept) "CHECK BEFORE TRAINING" else "FIX BEFORE ACCEPTING",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            messages.distinct().forEach { message ->
                Text(
                    text = "• $message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TestTags.PLAN_AI_WARNING).padding(vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        draft.workouts.sortedBy(WorkoutDraft::dayOfWeek).forEach { workout ->
            AiWorkoutReview(
                workout = workout,
                entriesById = entriesById,
                enabled = controlsEnabled,
                onEditExercise = { exercise ->
                    editorRequest = ExerciseEditorRequest(workout.dayOfWeek, exercise)
                },
                onAddExercise = { editorRequest = ExerciseEditorRequest(workout.dayOfWeek, null) },
            )
            Spacer(Modifier.height(24.dp))
        }

        state.saveError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(12.dp))
        }

        GreenGradientButton(
            text = if (state.isAccepting) "Saving plan" else "Accept Plan",
            onClick = onAccept,
            enabled = state.canAccept && !replacementInProgress,
            modifier = Modifier.testTag(TestTags.PLAN_AI_ACCEPT),
        )
        Spacer(Modifier.height(12.dp))
        AiSecondaryAction(
            text = "REGENERATE WITH AI",
            onClick = onRegenerate,
            enabled = controlsEnabled,
            modifier = Modifier.testTag(TestTags.PLAN_AI_REGENERATE),
        )
        Spacer(Modifier.height(10.dp))
        AiSecondaryAction(
            text = "USE RULE-BASED PLAN",
            onClick = onUseRuleFallback,
            enabled = controlsEnabled,
            modifier = Modifier.testTag(TestTags.PLAN_AI_RULE_FALLBACK),
        )
        TextButton(onClick = onEditInputs, enabled = controlsEnabled) { Text("EDIT INPUTS") }
        Spacer(Modifier.height(32.dp))
    }

    editorRequest?.let { request ->
        AiExerciseEditorDialog(
            request = request,
            eligibleExercises = state.eligibleExercises,
            onDismiss = { editorRequest = null },
            onConfirm = { exercise ->
                val original = request.original
                if (original == null) {
                    onAddExercise(request.workoutDay, exercise)
                } else {
                    onReplaceExercise(request.workoutDay, original.catalogId, exercise)
                }
                editorRequest = null
            },
        )
    }
}

@Composable
private fun AiReplacementStatus(
    state: AiGenerationUiState,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    val status =
        when (state) {
            AiGenerationUiState.Idle,
            is AiGenerationUiState.Validated -> null
            AiGenerationUiState.Stale ->
                Triple(
                    "REPLACEMENT INPUTS CHANGED",
                    "Your current draft is still here. Generate again when the inputs are ready.",
                    false,
                )
            is AiGenerationUiState.Generating ->
                Triple(
                    "BUILDING A REPLACEMENT",
                    "Your current draft will stay here until a validated replacement is ready.",
                    true,
                )
            is AiGenerationUiState.Invalid ->
                Triple(
                    "REPLACEMENT NEEDS CHANGES",
                    (state.violations.firstOrNull()?.message
                        ?: "The replacement did not validate.") +
                        " Your current draft is still here.",
                    false,
                )
            is AiGenerationUiState.Failed ->
                Triple(
                    "REPLACEMENT UNAVAILABLE",
                    state.failure.reviewMessage() + " Your current draft is still here.",
                    false,
                )
        } ?: return

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        if (status.third) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = status.first,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = status.second,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = if (status.third) onCancel else onClear) {
            Text(if (status.third) "CANCEL REPLACEMENT" else "DISMISS")
        }
    }
}

@Composable
private fun AiWorkoutReview(
    workout: WorkoutDraft,
    entriesById: Map<ExerciseCatalogId, ExerciseCatalogEntry>,
    enabled: Boolean,
    onEditExercise: (ExerciseDraft) -> Unit,
    onAddExercise: () -> Unit,
) {
    Text(
        text = "${dayOfWeekAbbrev(workout.dayOfWeek)} · ${workout.title}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))
    workout.exercises.forEach { exercise ->
        val entry = entriesById[exercise.catalogId]
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .testTag(TestTags.planAiExercise(workout.dayOfWeek, exercise.catalogId.value))
                    .clip(RoundedCornerShape(4.dp))
                    .background(SurfaceContainerHigh)
                    .clickable(enabled = enabled, role = Role.Button) { onEditExercise(exercise) }
                    .semantics {
                        contentDescription =
                            "${entry?.displayName ?: exercise.catalogId.value}, " +
                                "${exercise.prescriptionLabel(entry)}. Edit prescription."
                    }
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry?.displayName ?: exercise.catalogId.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = exercise.prescriptionLabel(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    TextButton(
        onClick = onAddExercise,
        enabled = enabled && workout.exercises.size < PlanValidationLimits.MAX_EXERCISES_PER_DAY,
        modifier = Modifier.testTag(TestTags.planAiAddExercise(workout.dayOfWeek)),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("ADD EXERCISE")
    }
}

@Composable
private fun AiExerciseEditorDialog(
    request: ExerciseEditorRequest,
    eligibleExercises: List<ExerciseCatalogEntry>,
    onDismiss: () -> Unit,
    onConfirm: (ExerciseDraft) -> Unit,
) {
    val originalEntry = eligibleExercises.firstOrNull { it.id == request.original?.catalogId }
    val editorListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedId by remember(request) { mutableStateOf(request.original?.catalogId) }
    var setsText by remember(request) { mutableStateOf((request.original?.sets ?: 3).toString()) }
    var repsText by remember(request) { mutableStateOf((request.original?.reps ?: 10).toString()) }
    var weightText by
        remember(request) { mutableStateOf(initialWeightText(request.original, originalEntry)) }
    var loadAffirmed by
        remember(request) {
            mutableStateOf(
                originalEntry != null &&
                    (!originalEntry.requiresTargetLoad() ||
                        (request.original?.targetWeightKg ?: 0.0) > 0)
            )
        }
    val selectedEntry = eligibleExercises.firstOrNull { it.id == selectedId }
    val sets = setsText.toIntOrNull()
    val reps = repsText.toIntOrNull()
    val weight = weightText.toDoubleOrNull()
    val hasValidLoad =
        when {
            selectedEntry == null || weight == null || !weight.isFinite() -> false
            selectedEntry.requiresTargetLoad() ->
                loadAffirmed && weight > PlanValidationLimits.MIN_WEIGHT_KG
            else -> weight == 0.0
        }
    val canConfirm =
        selectedEntry != null &&
            sets in
                PlanValidationLimits.MIN_SETS_PER_EXERCISE..PlanValidationLimits
                        .MAX_SETS_PER_EXERCISE &&
            reps in PlanValidationLimits.MIN_REPS_PER_SET..PlanValidationLimits.MAX_REPS_PER_SET &&
            hasValidLoad &&
            weight != null &&
            weight in PlanValidationLimits.MIN_WEIGHT_KG..PlanValidationLimits.MAX_WEIGHT_KG

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier =
                Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.9f).testTag(TestTags.PLAN_AI_EDITOR),
            color = SurfaceContainerHighest,
            shape = RoundedCornerShape(4.dp),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(TestTags.PLAN_AI_EDITOR_LIST),
                state = editorListState,
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = if (request.original == null) "ADD EXERCISE" else "EDIT EXERCISE",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                item {
                    Column {
                        Text(
                            text = "PRESCRIPTION",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text =
                                selectedEntry?.displayName
                                    ?: "Choose an exercise below to continue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = setsText,
                        onValueChange = { setsText = it },
                        label = { Text("Sets") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAN_AI_EDITOR_SETS),
                    )
                }
                item {
                    OutlinedTextField(
                        value = repsText,
                        onValueChange = { repsText = it },
                        label = { Text("Reps") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAN_AI_EDITOR_REPS),
                    )
                }
                item {
                    OutlinedTextField(
                        value = weightText,
                        onValueChange = {
                            weightText = it
                            loadAffirmed = true
                        },
                        label = {
                            Text(
                                if (selectedEntry?.requiresTargetLoad() == false) "No external load"
                                else "Target load (kg)"
                            )
                        },
                        supportingText =
                            if (selectedEntry?.requiresTargetLoad() == true && !loadAffirmed) {
                                { Text("Enter the target load in kilograms.") }
                            } else {
                                null
                            },
                        enabled = selectedEntry?.requiresTargetLoad() == true,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAN_AI_EDITOR_WEIGHT),
                    )
                }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        GreenGradientButton(
                            text = "Confirm exercise",
                            onClick = {
                                onConfirm(
                                    ExerciseDraft(
                                        catalogId = requireNotNull(selectedId),
                                        sets = requireNotNull(sets),
                                        reps = requireNotNull(reps),
                                        targetWeightKg = requireNotNull(weight),
                                    )
                                )
                            },
                            enabled = canConfirm,
                            modifier = Modifier.testTag(TestTags.PLAN_AI_EDITOR_CONFIRM),
                        )
                        TextButton(onClick = onDismiss) { Text("CANCEL") }
                    }
                }
                item {
                    Column {
                        Text(
                            text =
                                if (request.original == null) "CHOOSE EXERCISE"
                                else "CHANGE EXERCISE",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Browse ${eligibleExercises.size} compatible catalog entries.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(eligibleExercises, key = { it.id.value }) { entry ->
                    Row(
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag(TestTags.planAiCatalogExercise(entry.id.value))
                                .selectable(
                                    selected = entry.id == selectedId,
                                    role = Role.RadioButton,
                                    onClick = {
                                        selectedId = entry.id
                                        val preservesLoad =
                                            originalEntry?.requiredEquipment ==
                                                entry.requiredEquipment
                                        weightText =
                                            if (!entry.requiresTargetLoad()) {
                                                "0"
                                            } else if (
                                                preservesLoad &&
                                                    (request.original?.targetWeightKg ?: 0.0) > 0
                                            ) {
                                                request.original!!.targetWeightKg.editableWeight()
                                            } else {
                                                ""
                                            }
                                        loadAffirmed =
                                            !entry.requiresTargetLoad() ||
                                                (preservesLoad &&
                                                    (request.original?.targetWeightKg ?: 0.0) > 0)
                                        scope.launch { editorListState.animateScrollToItem(1) }
                                    },
                                )
                                .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = entry.id == selectedId, onClick = null)
                        Text(
                            text = entry.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    HorizontalDivider(color = SurfaceContainerHigh)
                }
            }
        }
    }
}

@Composable
private fun AiSecondaryAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceContainerHigh)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color =
                if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun PlanningEngineType.providerLabel(): String =
    when (this) {
        PlanningEngineType.RULE_BASED -> "RULE-BASED"
        PlanningEngineType.ON_DEVICE_AI -> "ON-DEVICE AI"
        PlanningEngineType.DEBUG_FAKE_AI -> "DEBUG FAKE AI"
        PlanningEngineType.DEBUG_REMOTE_AI -> "REMOTE AI EXPERIMENT"
    }

private fun ExerciseDraft.prescriptionLabel(entry: ExerciseCatalogEntry?): String {
    val load =
        when {
            entry.isBodyweight() -> "BW"
            entry?.requiresTargetLoad() == false -> "No load"
            targetWeightKg > 0 -> "${targetWeightKg.editableWeight()}kg"
            else -> "Set load"
        }
    return "$sets×$reps · $load"
}

private fun ExerciseCatalogEntry?.isBodyweight(): Boolean =
    this?.requiredEquipment == setOf(Equipment.BODYWEIGHT)

private fun initialWeightText(
    exercise: ExerciseDraft?,
    entry: ExerciseCatalogEntry?,
): String =
    when {
        entry?.requiresTargetLoad() == false -> "0"
        exercise != null && exercise.targetWeightKg > 0 -> exercise.targetWeightKg.editableWeight()
        else -> ""
    }

private fun Double.editableWeight(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

private fun PlanningFailure.reviewMessage(): String =
    when (this) {
        is PlanningFailure.InvalidRequest ->
            violations.firstOrNull() ?: "Review the planning intake and try again."
        PlanningFailure.Timeout -> "Generation took too long. Try again."
        PlanningFailure.Unavailable -> "No planning provider is available on this build."
        is PlanningFailure.ProviderError -> "The provider could not create a replacement."
    }
