package com.example.ironpath.accessibility

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticsContractTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun historyTabs_exposeTabRoleAndSelectedState() {
        val selectedTab = mutableStateOf(HistoryTab.Logs)
        setThemedContent {
            HistoryContent(
                selectedTab = selectedTab.value,
                logs = emptyList(),
                records = emptyList(),
                onTabSelected = { selectedTab.value = it },
                onAddRecord = {},
                zoneId = ZoneOffset.UTC,
            )
        }

        val logs = composeRule.onNodeWithText("LOGS")
        val records = composeRule.onNodeWithText("RECORDS")
        logs
            .assertIsSelectable()
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        records
            .assertIsSelectable()
            .assertIsNotSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))

        records.performClick()

        logs.assertIsNotSelected()
        records.assertIsSelected()
    }

    @Test
    fun planSetup_exposesOneGoalGroupAndIndependentWorkoutDayCheckboxes() {
        setThemedContent {
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

        composeRule
            .onNodeWithTag(TestTags.PLAN_GOAL_GROUP)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        composeRule
            .onNodeWithTag(TestTags.planGoal(PlanningGoal.STRENGTH.slug))
            .assertIsSelectable()
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule
            .onNodeWithTag(TestTags.planDay(1))
            .assertIsToggleable()
            .assertIsOn()
            .assertContentDescriptionEquals("Monday")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        composeRule
            .onNodeWithTag(TestTags.planDay(2))
            .assertIsToggleable()
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))

        composeRule
            .onNodeWithTag(TestTags.planExperience(TrainingExperience.INTERMEDIATE.name))
            .performScrollTo()
            .assertIsSelectable()
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        composeRule
            .onNodeWithTag(TestTags.planEquipment(Equipment.BARBELL.name))
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        composeRule
            .onNodeWithTag(TestTags.planCaution(ExerciseCautionTag.KNEE.name))
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOff()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
        composeRule
            .onNodeWithTag(TestTags.PLAN_INJURY_NOTES)
            .performScrollTo()
            .assert(hasAccessibleLabel("Injury notes"))
        composeRule
            .onNodeWithTag(TestTags.PLAN_PREFERENCES)
            .performScrollTo()
            .assert(hasAccessibleLabel("Exercise preferences"))
        composeRule
            .onNodeWithTag(TestTags.PLAN_DISLIKES)
            .performScrollTo()
            .assert(hasAccessibleLabel("Exercise dislikes"))
        composeRule
            .onNodeWithTag(TestTags.PLAN_REMOTE_AI_TOGGLE)
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        composeRule
            .onNodeWithTag(TestTags.PLAN_REMOTE_AI_KEY)
            .performScrollTo()
            .assert(hasAccessibleLabel("Gemini API key"))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule
            .onNodeWithTag(TestTags.PLAN_GENERATE_AI)
            .performScrollTo()
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule
            .onNodeWithTag(TestTags.PLAN_GENERATE)
            .performScrollTo()
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun addRecordFields_exposeProgrammaticLabels() {
        setAddRecordContent()

        composeRule.onNodeWithTag(TestTags.RECORD_NAME).assert(hasAccessibleLabel("Exercise name"))
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).assert(hasAccessibleLabel("Value"))
        composeRule.onNodeWithTag(TestTags.RECORD_DATE).assert(hasAccessibleLabel("Date"))
        composeRule
            .onNodeWithTag(TestTags.RECORD_NOTE)
            .assert(hasAccessibleLabel("Note (optional)"))
    }

    @Test
    fun addRecordValidation_associatesExactErrorsWithFields() {
        setAddRecordContent()

        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()

        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    "Exercise name is required",
                )
            )
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    "Weight must be a positive number",
                )
            )

        composeRule.onNodeWithTag(TestTags.RECORD_NAME).performTextReplacement("Squat")
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).performTextReplacement("100")
        composeRule.onNodeWithTag(TestTags.RECORD_DATE).performTextReplacement("not-a-date")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()

        composeRule
            .onNodeWithTag(TestTags.RECORD_DATE)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.Error,
                    "Invalid date format (use YYYY-MM-DD)",
                )
            )
    }

    @Test
    fun addRecordUnit_isLabeledAndReadOnly() {
        setAddRecordContent()

        composeRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.EditableText,
                    AnnotatedString("kg"),
                )
            )
            .assert(hasAccessibleLabel("Unit / type"))
            .assert(hasSetTextAction().not())
    }

    @Test
    fun addRecordExternalError_isAnnouncedAsAPoliteLiveRegion() {
        val message = "A record with this exercise, date, and weight already exists."
        setAddRecordContent(externalError = message)

        composeRule
            .onNodeWithText(message)
            .performScrollTo()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                )
            )
    }

    @Test
    fun activeSetRows_exposeCompletionAndExtraState() {
        setActiveSessionContent()

        composeRule
            .onNodeWithTag(TestTags.set(COMPLETED_SET_ID))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Squat set 1, complete",
                )
            )
        composeRule
            .onNodeWithTag(TestTags.set(INCOMPLETE_SET_ID))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Squat set 2, incomplete",
                )
            )
        composeRule
            .onNodeWithTag(TestTags.set(EXTRA_SET_ID))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Squat extra set 3, incomplete",
                )
            )
        composeRule
            .onNodeWithTag(TestTags.set(EXTRA_COMPLETE_SET_ID))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Squat extra set 4, complete",
                )
            )
        composeRule.onNodeWithContentDescription("Set complete").assertDoesNotExist()
    }

    @Test
    fun activeSetInputs_exposeExerciseAndSetSpecificLabels() {
        setActiveSessionContent()

        composeRule
            .onNodeWithTag(TestTags.setWeight(COMPLETED_SET_ID))
            .assert(hasAccessibleLabel("Weight for Squat set 1"))
            .assert(hasSetTextAction())
        composeRule
            .onNodeWithTag(TestTags.setReps(COMPLETED_SET_ID))
            .assert(hasAccessibleLabel("Repetitions for Squat set 1"))
            .assert(hasSetTextAction())
    }

    @Test
    fun addSet_usesOneMergedActionLabel() {
        setActiveSessionContent()

        composeRule.onNodeWithText("ADD SET").performScrollTo().assertHasClickAction()
        composeRule.onNodeWithContentDescription("Add set").assertDoesNotExist()
    }

    @Test
    fun entry_exposesGuestAndOptionalGoogleAccountActions() {
        var signInCount = 0
        setThemedContent { EntryScreen(onGetStarted = {}, onSignIn = { signInCount++ }) }

        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertHasClickAction()
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").assertHasClickAction()
        composeRule
            .onNodeWithText(
                "Signing in identifies your account. Your training data stays local until you " +
                    "choose a manual backup or restore."
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").performClick()
        composeRule.runOnIdle { assertEquals(1, signInCount) }
        composeRule.onNodeWithText("Terms of Service", substring = true).assertDoesNotExist()
    }

    @Test
    fun entry_whenExperiencePreviewIsDisabled_exposesOnlyLocalContinuation() {
        setThemedContent { EntryScreen(onGetStarted = {}, accountExperiencePreviewEnabled = false) }

        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertHasClickAction()
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").assertDoesNotExist()
    }

    @Test
    fun readOnlyPlanRows_haveNoActionWhileRemoveControlIsLabeled() {
        setPlanReviewContent()

        composeRule.onNodeWithTag(TestTags.planReviewDay(WORKOUT_ID)).assertHasNoClickAction()
        composeRule
            .onNodeWithTag(TestTags.planExercise(PLANNED_EXERCISE_ID))
            .assertHasNoClickAction()
        composeRule
            .onNodeWithContentDescription("Remove Strength A on Monday")
            .assertContentDescriptionContains("Remove Strength A on Monday")
            .assertHasClickAction()
    }

    @Test
    fun aiReview_editingActionsExposeLabelsRolesSelectionAndFieldState() {
        setAiPlanReviewContent()

        composeRule
            .onNodeWithTag(TestTags.planAiExercise(1, ExerciseCatalogIds.PUSH_UPS.value))
            .assertContentDescriptionContains("Push-ups", substring = true)
            .assertContentDescriptionContains("3×10", substring = true)
            .assertContentDescriptionContains("Edit prescription", substring = true)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeRule
            .onNodeWithTag(TestTags.planAiAddExercise(1))
            .performScrollTo()
            .assertHasClickAction()
        composeRule
            .onNodeWithTag(TestTags.planAiExercise(1, ExerciseCatalogIds.PUSH_UPS.value))
            .performClick()
        composeRule
            .onNodeWithTag(TestTags.PLAN_AI_EDITOR_LIST)
            .performScrollToNode(
                hasTestTag(TestTags.planAiCatalogExercise(ExerciseCatalogIds.PUSH_UPS.value))
            )
        composeRule
            .onNodeWithTag(TestTags.planAiCatalogExercise(ExerciseCatalogIds.PUSH_UPS.value))
            .assertIsSelectable()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
            .assertIsSelected()
        composeRule
            .onNodeWithTag(TestTags.PLAN_AI_EDITOR_LIST)
            .performScrollToNode(hasTestTag(TestTags.PLAN_AI_EDITOR_WEIGHT))
        composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_WEIGHT).assertIsNotEnabled()
        composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_CONFIRM).assertIsEnabled()
    }

    @Test
    fun workoutPreview_disabledStartDoesNotDuplicateSharedBackAction() {
        setThemedContent {
            WorkoutPreviewContent(
                uiState =
                    WorkoutPreviewUiState.Ready(
                        workout = workout,
                        exercises = listOf(plannedExercise),
                        canStart = false,
                        hasActiveSession = true,
                    ),
                onBack = {},
                onStart = {},
            )
        }

        composeRule.onNodeWithText("START WORKOUT").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun workoutLogReadyContent_doesNotDuplicateSharedBackAction() {
        setThemedContent {
            WorkoutLogDetailContent(
                uiState =
                    WorkoutLogDetailUiState.Ready(
                        WorkoutLogDetail(
                            log = TestSemanticsData.log,
                            exercises =
                                listOf(
                                    LoggedExerciseDetail(
                                        exercise = TestSemanticsData.loggedExercise,
                                        sets = listOf(TestSemanticsData.loggedSet),
                                    )
                                ),
                        )
                    ),
                onBack = {},
                zoneId = ZoneOffset.UTC,
            )
        }

        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    private fun setAddRecordContent(externalError: String? = null) {
        setThemedContent {
            AddRecordScreen(
                suggestions = emptyList(),
                today = LocalDate.parse("2026-07-16"),
                onSave = {},
                onCancel = {},
                externalError = externalError,
            )
        }
    }

    private fun setActiveSessionContent() {
        setThemedContent {
            ActiveContent(
                uiState =
                    ActiveUiState.InSession(
                        session = session,
                        exercises = listOf(sessionExercise),
                        sets =
                            listOf(
                                SessionSet(
                                    id = COMPLETED_SET_ID,
                                    sessionExerciseId = SESSION_EXERCISE_ID,
                                    setNumber = 1,
                                    reps = 5,
                                    weightKg = 100.0,
                                    completedAt = 1L,
                                ),
                                SessionSet(
                                    id = INCOMPLETE_SET_ID,
                                    sessionExerciseId = SESSION_EXERCISE_ID,
                                    setNumber = 2,
                                    weightKg = 100.0,
                                ),
                                SessionSet(
                                    id = EXTRA_SET_ID,
                                    sessionExerciseId = SESSION_EXERCISE_ID,
                                    setNumber = 3,
                                    isExtra = true,
                                ),
                                SessionSet(
                                    id = EXTRA_COMPLETE_SET_ID,
                                    sessionExerciseId = SESSION_EXERCISE_ID,
                                    setNumber = 4,
                                    reps = 5,
                                    weightKg = 100.0,
                                    completedAt = 1L,
                                    isExtra = true,
                                ),
                            ),
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
    }

    private fun setPlanReviewContent() {
        setThemedContent {
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
    }

    private fun setAiPlanReviewContent() {
        setThemedContent {
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
                providerMetadata = PlanningProviderMetadata(PlanningEngineType.DEBUG_FAKE_AI, 20),
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

    private fun setThemedContent(content: @Composable () -> Unit) {
        composeRule.setContent { IronPathTheme { Surface { content() } } }
    }

    private fun hasAccessibleLabel(label: String): SemanticsMatcher =
        SemanticsMatcher("has programmatic accessibility label '$label'") { node ->
            val descriptions =
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            val text = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            (descriptions + text).any { it.contains(label, ignoreCase = true) }
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
            sets = 3,
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
            plannedSets = 3,
            plannedReps = 5,
            plannedWeightKg = 100.0,
            orderIndex = 0,
        )

    private companion object {
        const val PLAN_ID = "semantics-plan"
        const val WORKOUT_ID = "semantics-workout"
        const val PLANNED_EXERCISE_ID = "semantics-planned-exercise"
        const val SESSION_ID = "semantics-session"
        const val SESSION_EXERCISE_ID = "semantics-session-exercise"
        const val COMPLETED_SET_ID = "semantics-set-complete"
        const val INCOMPLETE_SET_ID = "semantics-set-incomplete"
        const val EXTRA_SET_ID = "semantics-set-extra"
        const val EXTRA_COMPLETE_SET_ID = "semantics-set-extra-complete"
    }
}

private object TestSemanticsData {
    val log =
        com.example.ironpath.data.local.entity.WorkoutLog(
            id = "semantics-log",
            title = "Strength A",
            startedAt = 1L,
            completedAt = 2L,
            durationMinutes = 1,
            exerciseCount = 1,
        )

    val loggedExercise =
        LoggedExercise(
            id = "semantics-logged-exercise",
            workoutLogId = log.id,
            name = "Squat",
            plannedSets = 1,
            plannedReps = 5,
            plannedWeightKg = 100.0,
            orderIndex = 0,
        )

    val loggedSet =
        LoggedSet(
            id = "semantics-logged-set",
            loggedExerciseId = loggedExercise.id,
            setNumber = 1,
            reps = 5,
            weightKg = 100.0,
            completedAt = 2L,
        )
}
