package com.example.ironpath.accessibility

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.then
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.domain.planner.AiPlanDraftReviewState
import com.example.ironpath.domain.planner.DefaultExerciseCatalog
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCatalogIds
import com.example.ironpath.domain.planner.ExerciseCautionTag
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanDraft
import com.example.ironpath.domain.planner.PlanValidationContext
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningGoal
import com.example.ironpath.domain.planner.PlanningProviderMetadata
import com.example.ironpath.domain.planner.RemotePlanningExperimentState
import com.example.ironpath.domain.planner.TrainingExperience
import com.example.ironpath.domain.planner.ValidatedPlanDraft
import com.example.ironpath.domain.planner.WorkoutDraft
import com.example.ironpath.ui.screens.active.ActiveContent
import com.example.ironpath.ui.screens.active.ActiveUiState
import com.example.ironpath.ui.screens.entry.EntryScreen
import com.example.ironpath.ui.screens.history.AddRecordScreen
import com.example.ironpath.ui.screens.history.HistoryContent
import com.example.ironpath.ui.screens.history.HistoryTab
import com.example.ironpath.ui.screens.history.WorkoutLogDetailContent
import com.example.ironpath.ui.screens.history.WorkoutLogDetailUiState
import com.example.ironpath.ui.screens.home.HomeContent
import com.example.ironpath.ui.screens.home.HomeUiState
import com.example.ironpath.ui.screens.plan.AiGenerationUiState
import com.example.ironpath.ui.screens.plan.AiPlanReviewScreen
import com.example.ironpath.ui.screens.plan.AiPlanReviewUiState
import com.example.ironpath.ui.screens.plan.PlanContent
import com.example.ironpath.ui.screens.plan.PlanUiState
import com.example.ironpath.ui.screens.plan.PlannerIntakeUiState
import com.example.ironpath.ui.screens.workoutpreview.WorkoutPreviewContent
import com.example.ironpath.ui.screens.workoutpreview.WorkoutPreviewUiState
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun entry_compactPortraitAt200Percent_keepsGuestAndAccountActionsReachable() {
        setAdaptiveContent(COMPACT_PORTRAIT) { EntryScreen(onGetStarted = {}) }

        assertEntryContentReachable(accountExperiencePreviewEnabled = true)
    }

    @Test
    fun entry_compactLandscapeAt200Percent_keepsGuestAndAccountActionsReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) { EntryScreen(onGetStarted = {}) }

        assertEntryContentReachable(accountExperiencePreviewEnabled = true)
    }

    @Test
    fun entry_standardPortraitAt100Percent_keepsActionsAndLegalCopyReachable() {
        setAdaptiveContent(STANDARD_PORTRAIT) {
            EntryScreen(onGetStarted = {}, accountExperiencePreviewEnabled = false)
        }

        assertEntryContentReachable(accountExperiencePreviewEnabled = false)
    }

    private fun assertEntryContentReachable(accountExperiencePreviewEnabled: Boolean) {
        composeRule
            .onNodeWithText("CONTINUE ON THIS DEVICE")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        if (accountExperiencePreviewEnabled) {
            composeRule
                .onNodeWithText("SIGN IN WITH GOOGLE")
                .performScrollTo()
                .assertIsDisplayed()
                .assertHasClickAction()
            composeRule
                .onNodeWithText(
                    "Signing in identifies your account. Your training data stays local until " +
                        "you choose a manual backup or restore."
                )
                .performScrollTo()
                .assertIsDisplayed()
        } else {
            composeRule
                .onNodeWithText(
                    "Your training data is already saved on this device. " +
                        "IronPath cloud backup is not available in this version. " +
                        "Android device-to-device transfer may copy it to a new phone during setup."
                )
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    @Test
    fun planSetup_compactPortraitAt200Percent_keepsSelectionsAndGenerateReachable() {
        setAdaptiveContent(COMPACT_PORTRAIT) {
            PlanContent(
                uiState = PlanUiState.Setup,
                intakeState = PlannerIntakeUiState(selectedDays = setOf(1)),
                aiAvailable = true,
                aiGenerationState = AiGenerationUiState.Idle,
                onGoalSelected = {},
                onDayToggled = {},
                onExperienceSelected = {},
                onEquipmentToggled = {},
                onCautionTagToggled = {},
                onInjuryNotesChanged = {},
                onPreferencesChanged = {},
                onDislikesChanged = {},
                onGenerate = {},
                onGenerateWithAi = {},
                onCancelAiGeneration = {},
                onClearAiResult = {},
                onDeleteWorkout = {},
                onBackToSetup = {},
                onAccept = {},
                onStartWorkout = {},
                onOpenWorkoutPreview = {},
                remotePlanningExperimentState =
                    RemotePlanningExperimentState(
                        available = true,
                        enabled = true,
                    ),
            )
        }

        PlanningGoal.entries.forEach { goal ->
            composeRule
                .onNodeWithTag(TestTags.planGoal(goal.slug))
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsSelectable()
                .assertMinimumTouchTarget(goal.name)
        }

        (1..7).forEach { day ->
            val node =
                composeRule
                    .onNodeWithTag(TestTags.planDay(day))
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertIsToggleable()
                    .assertMinimumTouchTarget("day $day")
            if (day == 1) node.assertIsOn() else node.assertIsOff()
        }
        // Capture all bounds from one stable scroll position; comparing bounds collected after
        // separate scrolls would not detect overlap in a shared layout coordinate space.
        composeRule.onNodeWithTag(TestTags.planDay(1)).performScrollTo()
        composeRule.waitForIdle()
        val dayTargets =
            (1..7).map { day ->
                "day $day" to
                    composeRule
                        .onNodeWithTag(TestTags.planDay(day))
                        .fetchSemanticsNode()
                        .touchBoundsInRoot
            }
        assertTouchTargetsDoNotOverlap(dayTargets)

        TrainingExperience.entries.forEach { experience ->
            composeRule
                .onNodeWithTag(TestTags.planExperience(experience.name))
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsSelectable()
                .assertMinimumTouchTarget("${experience.name} experience")
        }

        Equipment.entries.forEach { equipment ->
            composeRule
                .onNodeWithTag(TestTags.planEquipment(equipment.name))
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsToggleable()
                .assertMinimumTouchTarget("${equipment.name} equipment")
        }

        ExerciseCautionTag.entries.forEach { caution ->
            composeRule
                .onNodeWithTag(TestTags.planCaution(caution.name))
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsToggleable()
                .assertMinimumTouchTarget("${caution.name} caution")
        }

        listOf(
                TestTags.PLAN_INJURY_NOTES,
                TestTags.PLAN_PREFERENCES,
                TestTags.PLAN_DISLIKES,
                TestTags.PLAN_REMOTE_AI_KEY,
            )
            .forEach { tag ->
                composeRule
                    .onNodeWithTag(tag)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assert(hasSetTextAction())
                    .assertMinimumTouchTarget(tag)
            }

        composeRule
            .onNodeWithTag(TestTags.PLAN_REMOTE_AI_TOGGLE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsToggleable()
            .assertIsOn()
            .assertMinimumTouchTarget("Remote AI experiment")

        composeRule
            .onNodeWithText("Key stays in memory and clears when the app process ends.")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule
            .onNodeWithTag(TestTags.PLAN_GENERATE_AI)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Generate with AI")

        composeRule
            .onNodeWithTag(TestTags.PLAN_GENERATE)
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Generate week")
    }

    @Test
    fun planReview_compactLandscapeAt200Percent_keepsBothDecisionsReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) { PlanReviewContent() }

        assertPlanReviewDecisionsReachable()
    }

    @Test
    fun planReview_standardPortraitAt100Percent_keepsBothDecisionsReachable() {
        setAdaptiveContent(STANDARD_PORTRAIT) { PlanReviewContent() }

        assertPlanReviewDecisionsReachable()
    }

    @Test
    fun aiPlanReview_compactLandscapeAt200Percent_keepsEditingAndRecoveryActionsReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) { AiPlanReviewContent() }

        composeRule
            .onNodeWithTag(TestTags.PLAN_AI_FALLBACK_REASON)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(TestTags.planAiExercise(1, ExerciseCatalogIds.PUSH_UPS.value))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Edit AI exercise")
        composeRule
            .onNodeWithTag(TestTags.planAiAddExercise(1))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Add AI exercise")
        listOf(
                TestTags.PLAN_AI_ACCEPT to "Accept AI plan",
                TestTags.PLAN_AI_REGENERATE to "Regenerate AI plan",
                TestTags.PLAN_AI_RULE_FALLBACK to "Use rule-based plan",
            )
            .forEach { (tag, label) ->
                composeRule
                    .onNodeWithTag(tag)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .assertMinimumTouchTarget(label)
            }
    }

    @Test
    fun aiPlanEditor_compactLandscapeAt200Percent_keepsCatalogAndFormReachable() {
        val reviewState = aiPlanReviewState()
        setAdaptiveContent(COMPACT_LANDSCAPE) {
            AiPlanReviewScreen(
                state = reviewState,
                onAddExercise = { _, _ -> },
                onReplaceExercise = { _, _, _ -> },
                onEditInputs = {},
                onRegenerate = {},
                onUseRuleFallback = {},
                onAccept = {},
            )
        }
        composeRule.onNodeWithTag(TestTags.planAiAddExercise(1)).performScrollTo().performClick()
        val editor = composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_LIST)
        val formStartIndex = 2
        val catalogStartIndex = 7

        editor.performScrollToIndex(catalogStartIndex + reviewState.eligibleExercises.lastIndex)
        composeRule
            .onNodeWithTag(
                TestTags.planAiCatalogExercise(reviewState.eligibleExercises.last().id.value)
            )
            .assertIsDisplayed()

        listOf(
                formStartIndex to TestTags.PLAN_AI_EDITOR_SETS,
                formStartIndex + 1 to TestTags.PLAN_AI_EDITOR_REPS,
                formStartIndex + 2 to TestTags.PLAN_AI_EDITOR_WEIGHT,
                formStartIndex + 3 to TestTags.PLAN_AI_EDITOR_CONFIRM,
            )
            .forEach { (index, tag) ->
                editor.performScrollToIndex(index)
                composeRule.onNodeWithTag(tag).assertIsDisplayed()
            }
    }

    private fun assertPlanReviewDecisionsReachable() {
        composeRule
            .onNodeWithText("REGENERATE")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Regenerate")
        composeRule
            .onNodeWithText("ACCEPT PLAN")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Accept plan")
    }

    @Test
    fun addRecord_compactPortraitAt200Percent_keepsFieldsAndActionsReachable() {
        setAdaptiveContent(COMPACT_PORTRAIT) { AddRecordContent() }

        assertRecordFormReachable()
    }

    @Test
    fun addRecord_compactLandscapeAt200Percent_keepsFieldsAndActionsReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) { AddRecordContent() }

        assertRecordFormReachable()
    }

    @Test
    fun activeSession_compactPortraitAt200Percent_keepsInputsAndActionsReachable() {
        setAdaptiveContent(COMPACT_PORTRAIT) { ActiveSessionContent() }

        assertActiveSessionReachable()
    }

    @Test
    fun activeSession_compactLandscapeAt200Percent_keepsInputsAndActionsReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) { ActiveSessionContent() }

        assertActiveSessionReachable()
    }

    @Test
    fun activeSession_standardPortraitAt100Percent_keepsInputsAndActionsReachable() {
        setAdaptiveContent(STANDARD_PORTRAIT) { ActiveSessionContent() }

        assertActiveSessionReachable()
    }

    @Test
    fun activeEmptyState_compactLandscapeAt200Percent_keepsPrimaryActionReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) {
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

        composeRule
            .onNodeWithText("OPEN PLAN")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Open plan")
    }

    @Test
    fun historyEmptyState_compactLandscapeAt200Percent_keepsPrimaryActionReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) {
            HistoryContent(
                selectedTab = HistoryTab.Records,
                logs = emptyList(),
                records = emptyList(),
                onTabSelected = {},
                onAddRecord = {},
                zoneId = ZoneOffset.UTC,
            )
        }

        composeRule
            .onNodeWithText("ADD RECORD")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Add record")
    }

    @Test
    fun homeActivePlan_compactPortraitAt200Percent_keepsStartAndWorkoutCardReachable() {
        setAdaptiveContent(COMPACT_PORTRAIT) {
            HomeContent(
                uiState =
                    HomeUiState.ActivePlan(
                        plan = plan,
                        workouts = listOf(workout),
                        planned = 1,
                        completed = 0,
                        todayWorkout = workout,
                        nextWorkout = workout,
                        hasActiveSession = false,
                    ),
                onNavigateToPlan = {},
                onNavigateToActive = {},
                onOpenWorkoutPreview = {},
            )
        }

        composeRule
            .onNodeWithText("START WORKOUT")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule
            .onNodeWithTag(TestTags.workout(WORKOUT_ID))
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun workoutPreview_compactLandscapeAt200Percent_keepsNavigationAndStartReachable() {
        setAdaptiveContent(COMPACT_LANDSCAPE) {
            WorkoutPreviewContent(
                uiState =
                    WorkoutPreviewUiState.Ready(
                        workout = workout,
                        exercises = listOf(plannedExercise),
                        canStart = true,
                        hasActiveSession = false,
                    ),
                onBack = {},
                onStart = {},
            )
        }

        composeRule
            .onNodeWithText("START WORKOUT")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText("Strength A").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun workoutLogDetail_compactPortraitAt200Percent_keepsSnapshotReachable() {
        setAdaptiveContent(COMPACT_PORTRAIT) {
            WorkoutLogDetailContent(
                uiState = WorkoutLogDetailUiState.Ready(workoutLogDetail),
                onBack = {},
                zoneId = ZoneOffset.UTC,
            )
        }

        composeRule.onNodeWithText("Strength A").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Squat").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("5 reps · 100 kg").performScrollTo().assertIsDisplayed()
    }

    private fun assertRecordFormReachable() {
        listOf(
                TestTags.RECORD_NAME,
                TestTags.RECORD_WEIGHT,
                TestTags.RECORD_DATE,
                TestTags.RECORD_NOTE,
            )
            .forEach { tag -> composeRule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed() }
        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("kg"),
                )
            )
            .performScrollTo()
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("SAVE")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Save")
        composeRule
            .onNodeWithText("CANCEL")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Cancel")
    }

    private fun assertActiveSessionReachable() {
        composeRule
            .onNodeWithTag(TestTags.setWeight(SET_ID))
            .performScrollTo()
            .assertIsDisplayed()
            .assertMinimumTouchTarget("Set weight")
        composeRule
            .onNodeWithTag(TestTags.setReps(SET_ID))
            .performScrollTo()
            .assertIsDisplayed()
            .assertMinimumTouchTarget("Set repetitions")
        composeRule
            .onNodeWithText("ADD SET")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Add set")
        composeRule
            .onNodeWithText("COMPLETE WORKOUT")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertMinimumTouchTarget("Complete workout")
    }

    @Composable
    private fun AddRecordContent() {
        AddRecordScreen(
            suggestions = emptyList(),
            today = LocalDate.parse("2026-07-16"),
            onSave = {},
            onCancel = {},
        )
    }

    @Composable
    private fun ActiveSessionContent() {
        ActiveContent(
            uiState =
                ActiveUiState.InSession(
                    session = session,
                    exercises = listOf(sessionExercise),
                    sets = listOf(sessionSet),
                ),
            elapsedSeconds = 65,
            nowMillis = { 1L },
            onNavigateToPlan = {},
            onStartSession = {},
            onUpdateSet = {},
            onAddSet = { _, _ -> },
            onFinishWorkout = {},
        )
    }

    @Composable
    private fun PlanReviewContent() {
        PlanContent(
            uiState =
                PlanUiState.Review(
                    GeneratedPlan(
                        plan = plan,
                        workouts = listOf(workout),
                        exercises = listOf(plannedExercise),
                    )
                ),
            selectedGoal = PlanningGoal.STRENGTH,
            selectedDays = setOf(1),
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

    @Composable
    private fun AiPlanReviewContent() {
        AiPlanReviewScreen(
            state = aiPlanReviewState(),
            onAddExercise = { _, _ -> },
            onReplaceExercise = { _, _, _ -> },
            onEditInputs = {},
            onRegenerate = {},
            onUseRuleFallback = {},
            onAccept = {},
        )
    }

    private fun aiPlanReviewState(): AiPlanReviewUiState {
        val targetMonday = LocalDate.parse("2026-07-20")
        val draft =
            PlanDraft(
                targetWeekStart = targetMonday,
                workouts =
                    listOf(
                        WorkoutDraft(
                            dayOfWeek = 1,
                            scheduledDate = targetMonday,
                            title = "Full body",
                            exercises =
                                listOf(
                                    ExerciseDraft(
                                        ExerciseCatalogIds.PUSH_UPS,
                                        sets = 3,
                                        reps = 10,
                                        targetWeightKg = 0.0,
                                    )
                                ),
                        )
                    ),
                rationale = "A simple week that leaves room to recover.",
                warnings = listOf("Adjust the session when form changes."),
                providerMetadata =
                    PlanningProviderMetadata(
                        PlanningEngineType.DEBUG_FAKE_AI,
                        generationDurationMillis = 20,
                        fallbackReason =
                            "On-device AI is unavailable, so the debug AI provider generated this draft.",
                    ),
            )
        val context =
            PlanValidationContext(
                expectedTargetWeekStart = targetMonday,
                invokedEngineType = PlanningEngineType.DEBUG_FAKE_AI,
                selectedDays = setOf(1),
                experience = TrainingExperience.BEGINNER,
                availableEquipment = setOf(Equipment.BODYWEIGHT),
            )
        val token =
            ValidatedPlanDraft.create(
                draft,
                context,
                java.time.Instant.parse("2026-07-19T12:00:00Z"),
            )
        return AiPlanReviewUiState(
            sourceToken = token,
            review = AiPlanDraftReviewState.Valid(token),
            eligibleExercises =
                DefaultExerciseCatalog().entries.filter {
                    it.requiredEquipment == setOf(Equipment.BODYWEIGHT) && it.beginnerSuitable
                },
        )
    }

    private fun setAdaptiveContent(
        configuration: TestConfiguration,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.FontScale(configuration.fontScale) then
                    DeviceConfigurationOverride.ForcedSize(
                        DpSize(configuration.widthDp.dp, configuration.heightDp.dp)
                    )
            ) {
                IronPathTheme { Surface(modifier = Modifier.fillMaxSize()) { content() } }
            }
        }
    }

    private fun SemanticsNodeInteraction.assertMinimumTouchTarget(
        label: String,
    ): SemanticsNodeInteraction {
        val node = fetchSemanticsNode()
        val bounds = node.touchBoundsInRoot
        // ForcedSize supplies a test-local density so the requested dp viewport fits the real
        // device surface. Use the node density; the rule density remains the host device density.
        val minimumPx = with(node.layoutInfo.density) { MIN_TOUCH_TARGET.toPx() }
        assertTrue(
            "$label touch width was ${bounds.width}px, expected at least ${minimumPx}px",
            bounds.width + TOUCH_TOLERANCE_PX >= minimumPx,
        )
        assertTrue(
            "$label touch height was ${bounds.height}px, expected at least ${minimumPx}px",
            bounds.height + TOUCH_TOLERANCE_PX >= minimumPx,
        )
        return this
    }

    private fun assertTouchTargetsDoNotOverlap(targets: List<Pair<String, Rect>>) {
        targets.forEachIndexed { index, (firstLabel, firstBounds) ->
            targets.drop(index + 1).forEach { (secondLabel, secondBounds) ->
                assertFalse(
                    "$firstLabel and $secondLabel expose overlapping touch targets",
                    firstBounds.overlaps(secondBounds),
                )
            }
        }
    }

    private val plan =
        WeeklyPlan(
            id = PLAN_ID,
            startDate = "2026-07-13",
            endDate = "2026-07-19",
            createdAt = 1L,
        )

    private val workout =
        PlannedWorkout(
            id = WORKOUT_ID,
            weeklyPlanId = PLAN_ID,
            dayOfWeek = 1,
            scheduledDate = "2026-07-13",
            title = "Strength A",
        )

    private val plannedExercise =
        PlannedExercise(
            id = PLANNED_EXERCISE_ID,
            plannedWorkoutId = WORKOUT_ID,
            name = "Squat",
            sets = 1,
            reps = 5,
            weightKg = 100.0,
            orderIndex = 0,
        )

    private val session =
        ActiveSession(
            id = SESSION_ID,
            sourcePlannedWorkoutId = WORKOUT_ID,
            workoutTitle = "Strength A",
            startedAt = 1L,
            lastUpdatedAt = 1L,
        )

    private val sessionExercise =
        SessionExercise(
            id = SESSION_EXERCISE_ID,
            activeSessionId = SESSION_ID,
            name = "Squat",
            plannedSets = 1,
            plannedReps = 5,
            plannedWeightKg = 100.0,
            orderIndex = 0,
        )

    private val sessionSet =
        SessionSet(
            id = SET_ID,
            sessionExerciseId = SESSION_EXERCISE_ID,
            setNumber = 1,
        )

    private val workoutLogDetail =
        WorkoutLogDetail(
            log =
                WorkoutLog(
                    id = "adaptive-log",
                    title = "Strength A",
                    startedAt = 1_784_159_700_000L,
                    completedAt = 1_784_160_000_000L,
                    durationMinutes = 5,
                    exerciseCount = 1,
                ),
            exercises =
                listOf(
                    LoggedExerciseDetail(
                        exercise =
                            LoggedExercise(
                                id = "adaptive-logged-exercise",
                                workoutLogId = "adaptive-log",
                                name = "Squat",
                                plannedSets = 1,
                                plannedReps = 5,
                                plannedWeightKg = 100.0,
                                orderIndex = 0,
                            ),
                        sets =
                            listOf(
                                LoggedSet(
                                    id = "adaptive-logged-set",
                                    loggedExerciseId = "adaptive-logged-exercise",
                                    setNumber = 1,
                                    reps = 5,
                                    weightKg = 100.0,
                                    completedAt = 1_784_160_000_000L,
                                )
                            ),
                    )
                ),
        )

    private data class TestConfiguration(
        val widthDp: Int,
        val heightDp: Int,
        val fontScale: Float,
    )

    private companion object {
        val COMPACT_PORTRAIT = TestConfiguration(widthDp = 320, heightDp = 640, fontScale = 2f)
        val COMPACT_LANDSCAPE = TestConfiguration(widthDp = 640, heightDp = 320, fontScale = 2f)
        val STANDARD_PORTRAIT = TestConfiguration(widthDp = 411, heightDp = 891, fontScale = 1f)
        val MIN_TOUCH_TARGET = 48.dp
        const val TOUCH_TOLERANCE_PX = 0.5f

        const val PLAN_ID = "adaptive-plan"
        const val WORKOUT_ID = "adaptive-workout"
        const val PLANNED_EXERCISE_ID = "adaptive-planned-exercise"
        const val SESSION_ID = "adaptive-session"
        const val SESSION_EXERCISE_ID = "adaptive-session-exercise"
        const val SET_ID = "adaptive-set"
    }
}
