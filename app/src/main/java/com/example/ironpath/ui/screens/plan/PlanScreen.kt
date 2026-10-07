package com.example.ironpath.ui.screens.plan

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCatalogId
import com.example.ironpath.domain.planner.ExerciseCautionTag
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanningGoal
import com.example.ironpath.domain.planner.RemotePlanningExperimentState
import com.example.ironpath.domain.planner.RuleExerciseForm
import com.example.ironpath.domain.planner.RuleExerciseRemoval
import com.example.ironpath.domain.planner.TrainingExperience
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.screens.home.dayOfWeekAbbrev
import com.example.ironpath.ui.testing.TestTags
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
    intakeViewModel: PlannerIntakeViewModel = hiltViewModel(),
    nextWeekRequested: Boolean = false,
    onNextWeekRequestConsumed: () -> Unit = {},
) {
    val uiState by viewModel.planUiState.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val ruleUndo by viewModel.ruleUndo.collectAsStateWithLifecycle()
    val suggestions by viewModel.exerciseSuggestions.collectAsStateWithLifecycle()
    val intakeState by intakeViewModel.intakeState.collectAsStateWithLifecycle()
    val aiGenerationState by intakeViewModel.aiGenerationState.collectAsStateWithLifecycle()
    val remotePlanningExperimentState by
        intakeViewModel.remotePlanningExperimentState.collectAsStateWithLifecycle()
    val validatedDraft = (aiGenerationState as? AiGenerationUiState.Validated)?.draft

    DisposableEffect(Unit) { onDispose { intakeViewModel.discardGeneration() } }

    LaunchedEffect(validatedDraft) {
        val draft = validatedDraft ?: return@LaunchedEffect
        val configurationRevision =
            (aiGenerationState as? AiGenerationUiState.Validated)?.configurationRevision
        if (viewModel.enterAiReview(draft, configurationRevision)) {
            intakeViewModel.onDraftConsumed(draft)
        }
    }

    LaunchedEffect(nextWeekRequested, uiState, saveState.isSaving) {
        if (nextWeekRequested && viewModel.beginNextWeekPlanning()) onNextWeekRequestConsumed()
    }
    val backToSetup: () -> Unit = {
        intakeViewModel.discardGeneration()
        viewModel.backToSetup()
    }
    val cancelPlanning: () -> Unit = {
        if (viewModel.cancelPlanning()) {
            intakeViewModel.cancelGeneration()
            intakeViewModel.clearGeneratedDraft()
            onPlanAccepted()
        }
    }
    BackHandler(
        enabled =
            uiState == PlanUiState.Setup ||
                uiState is PlanUiState.Review ||
                uiState is PlanUiState.AiReview
    ) {
        if (uiState == PlanUiState.Setup) cancelPlanning() else backToSetup()
    }

    PlanContent(
        uiState = uiState,
        saveState = saveState,
        onPlanNextWeek = { viewModel.beginNextWeekPlanning() },
        onCancelPlanning = cancelPlanning,
        intakeState = intakeState,
        aiAvailable = intakeViewModel.aiAvailable,
        aiGenerationState = aiGenerationState,
        onGoalSelected = intakeViewModel::setGoal,
        onDayToggled = intakeViewModel::toggleDay,
        onExperienceSelected = intakeViewModel::setExperience,
        onEquipmentToggled = intakeViewModel::toggleEquipment,
        onCautionTagToggled = intakeViewModel::toggleCautionTag,
        onInjuryNotesChanged = intakeViewModel::setInjuryNotes,
        onPreferencesChanged = intakeViewModel::setExercisePreferences,
        onDislikesChanged = intakeViewModel::setExerciseDislikes,
        onGenerate = { viewModel.generatePlan(intakeState.goal, intakeState.selectedDays) },
        onGenerateWithAi = { intakeViewModel.generateWithAi(viewModel.targetWeekStart()) },
        onCancelAiGeneration = intakeViewModel::cancelGeneration,
        onClearAiResult = intakeViewModel::clearGeneratedDraft,
        onDeleteWorkout = viewModel::deleteWorkoutFromReview,
        onMoveRuleWorkout = viewModel::moveRuleWorkout,
        onEditRuleExercise = viewModel::editRuleExercise,
        onAddRuleExercise = viewModel::addRuleExercise,
        onRemoveRuleExercise = viewModel::removeRuleExercise,
        onMoveRuleExercise = viewModel::moveRuleExercise,
        ruleUndo = ruleUndo,
        onUndoRuleRemoval = viewModel::undoRuleRemoval,
        onRuleUndoExpired = viewModel::expireRuleUndo,
        ruleExerciseSuggestions = suggestions,
        onBackToSetup = backToSetup,
        onAccept = {
            viewModel.acceptPlan {
                intakeViewModel.resetAfterAcceptance()
                onPlanAccepted()
            }
        },
        onStartWorkout = onStartWorkout,
        onOpenWorkoutPreview = onOpenWorkoutPreview,
        onAddAiExercise = viewModel::addAiExercise,
        onReplaceAiExercise = viewModel::replaceAiExercise,
        onRegenerateAi = { intakeViewModel.generateWithAi(viewModel.targetWeekStart()) },
        onUseRuleFallback = {
            intakeViewModel.generateWithRuleBasedFallback(viewModel.targetWeekStart())
        },
        remotePlanningExperimentState = remotePlanningExperimentState,
        onRemotePlanningEnabledChanged = intakeViewModel::setRemotePlanningEnabled,
        onRemotePlanningApiKeyChanged = intakeViewModel::setRemotePlanningApiKey,
        onRemotePlanningOptionChanged = intakeViewModel::setRemotePlanningOption,
        modifier = modifier,
    )
}

// -- Pure render composable --

@Composable
internal fun PlanContent(
    uiState: PlanUiState,
    intakeState: PlannerIntakeUiState,
    aiAvailable: Boolean,
    aiGenerationState: AiGenerationUiState,
    onGoalSelected: (PlanningGoal) -> Unit,
    onDayToggled: (Int) -> Unit,
    onExperienceSelected: (TrainingExperience) -> Unit,
    onEquipmentToggled: (Equipment) -> Unit,
    onCautionTagToggled: (ExerciseCautionTag) -> Unit,
    onInjuryNotesChanged: (String) -> Unit,
    onPreferencesChanged: (String) -> Unit,
    onDislikesChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onGenerateWithAi: () -> Unit,
    onCancelAiGeneration: () -> Unit,
    onClearAiResult: () -> Unit,
    onDeleteWorkout: (String) -> Unit,
    onBackToSetup: () -> Unit,
    onAccept: () -> Unit,
    onStartWorkout: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddAiExercise: (Int, ExerciseDraft) -> Unit = { _, _ -> },
    onReplaceAiExercise: (Int, ExerciseCatalogId, ExerciseDraft) -> Unit = { _, _, _ -> },
    onRegenerateAi: () -> Unit = {},
    onUseRuleFallback: () -> Unit = {},
    remotePlanningExperimentState: RemotePlanningExperimentState = RemotePlanningExperimentState(),
    onRemotePlanningEnabledChanged: (Boolean) -> Unit = {},
    onRemotePlanningApiKeyChanged: (String) -> Unit = {},
    onRemotePlanningOptionChanged: (String) -> Unit = {},
    onMoveRuleWorkout: (String, Int) -> Unit = { _, _ -> },
    onEditRuleExercise: (String, RuleExerciseForm) -> Unit = { _, _ -> },
    onAddRuleExercise: (String, RuleExerciseForm) -> Unit = { _, _ -> },
    onRemoveRuleExercise: (String) -> Unit = {},
    onMoveRuleExercise: (String, String, Int) -> Unit = { _, _, _ -> },
    ruleUndo: RuleExerciseRemoval? = null,
    onUndoRuleRemoval: () -> Unit = {},
    onRuleUndoExpired: (RuleExerciseRemoval) -> Unit = {},
    ruleExerciseSuggestions: List<String> = emptyList(),
    saveState: PlanSaveUiState = PlanSaveUiState(),
    onPlanNextWeek: () -> Unit = {},
    onCancelPlanning: () -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        saveState.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        }
        if (saveState.isSaving)
            Text("Saving plan…", modifier = Modifier.padding(horizontal = 24.dp))
        if (
            uiState == PlanUiState.Setup ||
                uiState is PlanUiState.Review ||
                uiState is PlanUiState.AiReview
        ) {
            TextButton(
                onClick = onCancelPlanning,
                enabled =
                    !saveState.isSaving &&
                        (uiState !is PlanUiState.AiReview || !uiState.review.isAccepting)
            ) {
                Text("Cancel Planning")
            }
        }
        Box(Modifier.weight(1f)) {
            when (uiState) {
                PlanUiState.Loading -> {
                    Box(
                        modifier.fillMaxSize().testTag(TestTags.PLAN_LOADING),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                PlanUiState.Setup ->
                    PlanSetupScreen(
                        intakeState = intakeState,
                        aiAvailable = aiAvailable,
                        aiGenerationState = aiGenerationState,
                        onGoalSelected = onGoalSelected,
                        onDayToggled = onDayToggled,
                        onExperienceSelected = onExperienceSelected,
                        onEquipmentToggled = onEquipmentToggled,
                        onCautionTagToggled = onCautionTagToggled,
                        onInjuryNotesChanged = onInjuryNotesChanged,
                        onPreferencesChanged = onPreferencesChanged,
                        onDislikesChanged = onDislikesChanged,
                        onGenerate = onGenerate,
                        onGenerateWithAi = onGenerateWithAi,
                        onCancelAiGeneration = onCancelAiGeneration,
                        onClearAiResult = onClearAiResult,
                        remotePlanningExperimentState = remotePlanningExperimentState,
                        onRemotePlanningEnabledChanged = onRemotePlanningEnabledChanged,
                        onRemotePlanningApiKeyChanged = onRemotePlanningApiKeyChanged,
                        onRemotePlanningOptionChanged = onRemotePlanningOptionChanged,
                        modifier = Modifier,
                    )
                is PlanUiState.Review ->
                    RulePlanReviewScreen(
                        generated = uiState.generated,
                        onMoveWorkout = onMoveRuleWorkout,
                        onEditExercise = onEditRuleExercise,
                        onAddExercise = onAddRuleExercise,
                        onRemoveExercise = onRemoveRuleExercise,
                        onMoveExercise = onMoveRuleExercise,
                        undo = ruleUndo,
                        onUndo = onUndoRuleRemoval,
                        onUndoExpired = onRuleUndoExpired,
                        suggestions = ruleExerciseSuggestions,
                        isSaving = saveState.isSaving,
                        onDeleteWorkout = onDeleteWorkout,
                        onBackToSetup = onBackToSetup,
                        onAccept = onAccept,
                        modifier = Modifier,
                    )
                is PlanUiState.AiReview ->
                    AiPlanReviewScreen(
                        state = uiState.review,
                        onAddExercise = onAddAiExercise,
                        onReplaceExercise = onReplaceAiExercise,
                        onEditInputs = {
                            onClearAiResult()
                            onBackToSetup()
                        },
                        onRegenerate = onRegenerateAi,
                        onUseRuleFallback = onUseRuleFallback,
                        onAccept = onAccept,
                        modifier = Modifier,
                        generationState = aiGenerationState,
                        onCancelGeneration = onCancelAiGeneration,
                        onClearGeneration = onClearAiResult,
                    )
                is PlanUiState.Accepted ->
                    PlanAcceptedScreen(
                        planned = uiState.planned,
                        onPlanNextWeek = onPlanNextWeek,
                        completed = uiState.completed,
                        workouts = uiState.workouts,
                        todayWorkout = uiState.todayWorkout,
                        nextWorkout = uiState.nextWorkout,
                        hasActiveSession = uiState.hasActiveSession,
                        onStartWorkout = onStartWorkout,
                        onOpenWorkoutPreview = onOpenWorkoutPreview,
                        modifier = Modifier,
                    )
            }
        }
    }
}

@Composable
internal fun PlanContent(
    uiState: PlanUiState,
    selectedGoal: PlanningGoal,
    selectedDays: Set<Int>,
    onGoalSelected: (PlanningGoal) -> Unit,
    onDayToggled: (Int) -> Unit,
    onGenerate: () -> Unit,
    onDeleteWorkout: (String) -> Unit,
    onBackToSetup: () -> Unit,
    onAccept: () -> Unit,
    onStartWorkout: () -> Unit,
    onOpenWorkoutPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PlanContent(
        uiState = uiState,
        intakeState = PlannerIntakeUiState(goal = selectedGoal, selectedDays = selectedDays),
        aiAvailable = false,
        aiGenerationState = AiGenerationUiState.Idle,
        onGoalSelected = onGoalSelected,
        onDayToggled = onDayToggled,
        onExperienceSelected = {},
        onEquipmentToggled = {},
        onCautionTagToggled = {},
        onInjuryNotesChanged = {},
        onPreferencesChanged = {},
        onDislikesChanged = {},
        onGenerate = onGenerate,
        onGenerateWithAi = {},
        onCancelAiGeneration = {},
        onClearAiResult = {},
        onDeleteWorkout = onDeleteWorkout,
        onBackToSetup = onBackToSetup,
        onAccept = onAccept,
        onStartWorkout = onStartWorkout,
        onOpenWorkoutPreview = onOpenWorkoutPreview,
        modifier = modifier,
    )
}

// -- Setup Screen --

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanSetupScreen(
    intakeState: PlannerIntakeUiState,
    aiAvailable: Boolean,
    aiGenerationState: AiGenerationUiState,
    onGoalSelected: (PlanningGoal) -> Unit,
    onDayToggled: (Int) -> Unit,
    onExperienceSelected: (TrainingExperience) -> Unit,
    onEquipmentToggled: (Equipment) -> Unit,
    onCautionTagToggled: (ExerciseCautionTag) -> Unit,
    onInjuryNotesChanged: (String) -> Unit,
    onPreferencesChanged: (String) -> Unit,
    onDislikesChanged: (String) -> Unit,
    onGenerate: () -> Unit,
    onGenerateWithAi: () -> Unit,
    onCancelAiGeneration: () -> Unit,
    onClearAiResult: () -> Unit,
    remotePlanningExperimentState: RemotePlanningExperimentState,
    onRemotePlanningEnabledChanged: (Boolean) -> Unit,
    onRemotePlanningApiKeyChanged: (String) -> Unit,
    onRemotePlanningOptionChanged: (String) -> Unit,
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
            modifier = Modifier.testTag(TestTags.PLAN_GOAL_GROUP).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlanningGoal.entries.forEach { goal ->
                GoalChip(
                    label = goal.displayLabel.uppercase(),
                    selected = goal == intakeState.goal,
                    onClick = { onGoalSelected(goal) },
                    modifier = Modifier.testTag(TestTags.planGoal(goal.slug)),
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

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 4,
        ) {
            workoutDayLabels.forEachIndexed { index, (label, accessibleLabel) ->
                val dow = index + 1
                DayChip(
                    label = label,
                    accessibleLabel = accessibleLabel,
                    selected = dow in intakeState.selectedDays,
                    onClick = { onDayToggled(dow) },
                    modifier = Modifier.testTag(TestTags.planDay(dow)),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "CHOOSE UP TO SIX DAYS. YOUR PLAN TARGETS NEXT WEEK.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        intakeState.daySelectionMessage?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SetupSectionTitle("Training Experience")
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TrainingExperience.entries.forEach { experience ->
                GoalChip(
                    label = experience.displayText(),
                    selected = experience == intakeState.experience,
                    onClick = { onExperienceSelected(experience) },
                    modifier = Modifier.testTag(TestTags.planExperience(experience.name)),
                )
            }
        }

        SetupSectionTitle("Available Equipment")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Equipment.entries.forEach { equipment ->
                ToggleChip(
                    label = equipment.displayText(),
                    selected = equipment in intakeState.availableEquipment,
                    onClick = { onEquipmentToggled(equipment) },
                    modifier = Modifier.testTag(TestTags.planEquipment(equipment.name)),
                )
            }
        }
        if (intakeState.availableEquipment.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Choose at least one equipment option.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SetupSectionTitle("Movement Limits")
        Text(
            text = "Skip exercises carrying any selected caution tag.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExerciseCautionTag.entries.forEach { tag ->
                ToggleChip(
                    label = tag.displayText(),
                    selected = tag in intakeState.forbiddenCautionTags,
                    onClick = { onCautionTagToggled(tag) },
                    modifier = Modifier.testTag(TestTags.planCaution(tag.name)),
                )
            }
        }

        SetupSectionTitle("Training Notes")
        PlanningTextField(
            value = intakeState.injuryNotes,
            onValueChange = onInjuryNotesChanged,
            label = "Injury notes",
            supportingText = "Context only. IronPath does not provide medical advice.",
            modifier = Modifier.testTag(TestTags.PLAN_INJURY_NOTES),
        )
        Spacer(Modifier.height(12.dp))
        PlanningTextField(
            value = intakeState.exercisePreferences,
            onValueChange = onPreferencesChanged,
            label = "Exercise preferences",
            supportingText = "Movements you enjoy or want to prioritize.",
            modifier = Modifier.testTag(TestTags.PLAN_PREFERENCES),
        )
        Spacer(Modifier.height(12.dp))
        PlanningTextField(
            value = intakeState.exerciseDislikes,
            onValueChange = onDislikesChanged,
            label = "Exercise dislikes",
            supportingText = "A soft preference unless covered by a movement limit above.",
            modifier = Modifier.testTag(TestTags.PLAN_DISLIKES),
        )

        if (remotePlanningExperimentState.available) {
            RemoteAiLab(
                state = remotePlanningExperimentState,
                onEnabledChanged = onRemotePlanningEnabledChanged,
                onApiKeyChanged = onRemotePlanningApiKeyChanged,
                onOptionChanged = onRemotePlanningOptionChanged,
            )
        }

        Spacer(Modifier.height(32.dp))

        if (aiAvailable) {
            GreenGradientButton(
                text = "Generate with AI",
                onClick = onGenerateWithAi,
                modifier = Modifier.testTag(TestTags.PLAN_GENERATE_AI),
                enabled =
                    intakeState.canGenerateWithAi &&
                        aiGenerationState !is AiGenerationUiState.Generating,
            )
            AiGenerationStatus(
                state = aiGenerationState,
                onCancel = onCancelAiGeneration,
                onClear = onClearAiResult,
            )
            Spacer(Modifier.height(12.dp))
        }

        GreenGradientButton(
            text = "Use Rule-Based Planner",
            onClick = onGenerate,
            modifier = Modifier.testTag(TestTags.PLAN_GENERATE),
            enabled = intakeState.canGenerateRuleBased,
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
private fun SetupSectionTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Spacer(Modifier.height(32.dp))
    Text(
        text = title,
        modifier = modifier,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun PlanningTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
        minLines = 2,
        maxLines = 4,
    )
}

@Composable
private fun ToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (selected) MaterialTheme.colorScheme.primary else SurfaceContainerHigh
    val contentColor =
        if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
    Box(
        modifier =
            modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(backgroundColor)
                .toggleable(
                    value = selected,
                    role = Role.Checkbox,
                    onValueChange = { onClick() },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
        )
    }
}

@Composable
private fun AiGenerationStatus(
    state: AiGenerationUiState,
    onCancel: () -> Unit,
    onClear: () -> Unit,
) {
    when (state) {
        AiGenerationUiState.Idle -> Unit
        AiGenerationUiState.Stale -> {
            AiResultMessage(
                title = "Plan inputs changed",
                detail = "Generate again to build a fresh draft.",
                onClear = onClear,
            )
        }
        is AiGenerationUiState.Generating -> {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(top = 16.dp)
                        .testTag(TestTags.PLAN_AI_GENERATING),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text("Building your draft", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        is AiGenerationUiState.Validated -> {
            AiResultMessage(
                title = "Draft ready",
                detail =
                    "${state.draft.draft.providerMetadata.engineType.displayText()} · " +
                        "${state.draft.draft.workouts.size} workouts · Not saved",
                onClear = onClear,
            )
        }
        is AiGenerationUiState.Invalid -> {
            AiResultMessage(
                title = "Draft needs changes",
                detail = state.violations.firstOrNull()?.message ?: "The draft did not validate.",
                onClear = onClear,
            )
        }
        is AiGenerationUiState.Failed -> {
            AiResultMessage(
                title = "Draft unavailable",
                detail = state.failure.userMessage(),
                onClear = onClear,
            )
        }
    }
}

@Composable
private fun AiResultMessage(
    title: String,
    detail: String,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onClear) { Text("Dismiss") }
    }
}

private fun Enum<*>.displayText(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercaseChar() }

private fun com.example.ironpath.domain.planner.PlanningEngineType.displayText(): String =
    name.replace('_', ' ')

private fun com.example.ironpath.domain.planner.PlanningFailure.userMessage(): String =
    when (this) {
        is com.example.ironpath.domain.planner.PlanningFailure.InvalidRequest ->
            violations.firstOrNull() ?: "Review the planning intake and try again."
        com.example.ironpath.domain.planner.PlanningFailure.Timeout ->
            "Generation took too long. Try again."
        com.example.ironpath.domain.planner.PlanningFailure.Unavailable ->
            "No AI provider is available on this build."
        is com.example.ironpath.domain.planner.PlanningFailure.ProviderError ->
            "The provider could not create a draft. Try again."
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
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .background(bgColor)
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
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
    accessibleLabel: String,
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
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .clip(shape)
                .background(bgColor)
                .toggleable(
                    value = selected,
                    role = Role.Checkbox,
                    onValueChange = { onClick() },
                )
                .semantics { contentDescription = accessibleLabel }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = textColor,
        )
    }
}

private val workoutDayLabels =
    listOf(
        "MO" to "Monday",
        "TU" to "Tuesday",
        "WE" to "Wednesday",
        "TH" to "Thursday",
        "FR" to "Friday",
        "SA" to "Saturday",
        "SU" to "Sunday",
    )

// -- Accepted/Lightweight Status Screen --

@Composable
private fun PlanAcceptedScreen(
    planned: Int,
    onPlanNextWeek: () -> Unit,
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

            if (planned > 0 && completed == planned) {
                Spacer(Modifier.height(24.dp))
                GreenGradientButton(text = "Plan Next Week", onClick = onPlanNextWeek)
            }
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
                .testTag(TestTags.workout(workout.id))
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
                selectedGoal = PlanningGoal.STRENGTH,
                selectedDays = setOf(1, 3, 5),
                onGoalSelected = {},
                onDayToggled = {},
                onGenerate = {},
                onDeleteWorkout = {},
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
                selectedGoal = PlanningGoal.HYPERTROPHY,
                selectedDays = setOf(1, 3, 5),
                onGoalSelected = {},
                onDayToggled = {},
                onGenerate = {},
                onDeleteWorkout = {},
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
                selectedGoal = PlanningGoal.STRENGTH,
                selectedDays = emptySet(),
                onGoalSelected = {},
                onDayToggled = {},
                onGenerate = {},
                onDeleteWorkout = {},
                onBackToSetup = {},
                onAccept = {},
                onStartWorkout = {},
                onOpenWorkoutPreview = {},
            )
        }
    }
}
