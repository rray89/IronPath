package com.example.ironpath.ui.screens.plan

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCatalogIds
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanDraft
import com.example.ironpath.domain.planner.PlanValidationContext
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningFailure
import com.example.ironpath.domain.planner.PlanningGoal
import com.example.ironpath.domain.planner.PlanningProviderMetadata
import com.example.ironpath.domain.planner.TrainingExperience
import com.example.ironpath.domain.planner.ValidatedPlanDraft
import com.example.ironpath.domain.planner.WorkoutDraft
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val plan =
        WeeklyPlan(
            id = "plan-1",
            startDate = "2026-07-20",
            endDate = "2026-07-26",
            createdAt = 1L,
        )

    private val mondayWorkout =
        PlannedWorkout(
            id = "workout-monday",
            weeklyPlanId = plan.id,
            dayOfWeek = 1,
            scheduledDate = "2026-07-20",
            title = "Upper Body",
            status = WorkoutStatus.Upcoming,
        )

    private val mondayExercise =
        PlannedExercise(
            id = "exercise-bench",
            plannedWorkoutId = mondayWorkout.id,
            name = "Bench Press",
            sets = 3,
            reps = 10,
            weightKg = 20.0,
            orderIndex = 0,
        )

    private val generated =
        GeneratedPlan(
            plan = plan,
            workouts = listOf(mondayWorkout),
            exercises = listOf(mondayExercise),
        )

    private fun setPlanContent(
        uiState: PlanUiState,
        selectedGoal: PlanningGoal = PlanningGoal.STRENGTH,
        selectedDays: Set<Int> = emptySet(),
        onGoalSelected: (PlanningGoal) -> Unit = {},
        onDayToggled: (Int) -> Unit = {},
        aiAvailable: Boolean = false,
        aiGenerationState: AiGenerationUiState = AiGenerationUiState.Idle,
        intakeOverride: PlannerIntakeUiState? = null,
        onExperienceSelected: (TrainingExperience) -> Unit = {},
        onEquipmentToggled: (Equipment) -> Unit = {},
        onInjuryNotesChanged: (String) -> Unit = {},
        onPreferencesChanged: (String) -> Unit = {},
        onDislikesChanged: (String) -> Unit = {},
        onGenerateWithAi: () -> Unit = {},
        onGenerate: () -> Unit = {},
        onDeleteWorkout: (String) -> Unit = {},
        onBackToSetup: () -> Unit = {},
        onAccept: () -> Unit = {},
        onStartWorkout: () -> Unit = {},
        onOpenWorkoutPreview: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            var intakeState by remember {
                mutableStateOf(
                    intakeOverride
                        ?: PlannerIntakeUiState(
                            goal = selectedGoal,
                            selectedDays = selectedDays,
                        )
                )
            }
            IronPathTheme {
                Surface {
                    PlanContent(
                        uiState = uiState,
                        intakeState = intakeState,
                        aiAvailable = aiAvailable,
                        aiGenerationState = aiGenerationState,
                        onGoalSelected = {
                            intakeState = intakeState.copy(goal = it)
                            onGoalSelected(it)
                        },
                        onDayToggled = {
                            intakeState =
                                intakeState.copy(
                                    selectedDays =
                                        if (it in intakeState.selectedDays) {
                                            intakeState.selectedDays - it
                                        } else {
                                            intakeState.selectedDays + it
                                        }
                                )
                            onDayToggled(it)
                        },
                        onExperienceSelected = {
                            intakeState = intakeState.copy(experience = it)
                            onExperienceSelected(it)
                        },
                        onEquipmentToggled = {
                            intakeState =
                                intakeState.copy(
                                    availableEquipment =
                                        if (it in intakeState.availableEquipment) {
                                            intakeState.availableEquipment - it
                                        } else {
                                            intakeState.availableEquipment + it
                                        }
                                )
                            onEquipmentToggled(it)
                        },
                        onCautionTagToggled = {},
                        onInjuryNotesChanged = {
                            intakeState = intakeState.copy(injuryNotes = it)
                            onInjuryNotesChanged(it)
                        },
                        onPreferencesChanged = {
                            intakeState = intakeState.copy(exercisePreferences = it)
                            onPreferencesChanged(it)
                        },
                        onDislikesChanged = {
                            intakeState = intakeState.copy(exerciseDislikes = it)
                            onDislikesChanged(it)
                        },
                        onGenerate = onGenerate,
                        onGenerateWithAi = onGenerateWithAi,
                        onCancelAiGeneration = {},
                        onClearAiResult = {},
                        onDeleteWorkout = onDeleteWorkout,
                        onBackToSetup = onBackToSetup,
                        onAccept = onAccept,
                        onStartWorkout = onStartWorkout,
                        onOpenWorkoutPreview = onOpenWorkoutPreview,
                    )
                }
            }
        }
    }

    @Test
    fun loading_showsProgressState() {
        setPlanContent(PlanUiState.Loading)

        composeRule.onNodeWithTag(TestTags.PLAN_LOADING).assertIsDisplayed()
    }

    @Test
    fun setup_exposesSelectedGoalAndDays() {
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedGoal = PlanningGoal.HYPERTROPHY,
            selectedDays = setOf(1, 5),
        )

        composeRule
            .onNodeWithTag(TestTags.planGoal(PlanningGoal.HYPERTROPHY.slug))
            .assertIsSelected()
        composeRule
            .onNodeWithTag(TestTags.planGoal(PlanningGoal.STRENGTH.slug))
            .assertIsNotSelected()
        composeRule.onNodeWithTag(TestTags.planDay(1)).assertIsOn()
        composeRule.onNodeWithTag(TestTags.planDay(2)).assertIsOff()
        composeRule.onNodeWithTag(TestTags.planDay(5)).assertIsOn()
    }

    @Test
    fun setup_withoutDays_disablesGenerate() {
        var callbackCount = 0
        setPlanContent(
            uiState = PlanUiState.Setup,
            onGenerate = { callbackCount += 1 },
        )

        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).assertIsNotEnabled()

        assertEquals(0, callbackCount)
    }

    @Test
    fun setup_withDay_enablesGenerateAndForwardsSelections() {
        var selectedGoal: PlanningGoal? = null
        var selectedDay: Int? = null
        var generateCount = 0
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedDays = setOf(1),
            onGoalSelected = { selectedGoal = it },
            onDayToggled = { selectedDay = it },
            onGenerate = { generateCount += 1 },
        )

        composeRule
            .onNodeWithTag(TestTags.planGoal(PlanningGoal.GENERAL_FITNESS.slug))
            .performClick()
        composeRule.onNodeWithTag(TestTags.planDay(3)).performClick()
        composeRule
            .onNodeWithTag(TestTags.PLAN_GENERATE)
            .performScrollTo()
            .assertIsEnabled()
            .assertHasClickAction()
            .performClick()

        assertEquals(PlanningGoal.GENERAL_FITNESS, selectedGoal)
        assertEquals(3, selectedDay)
        assertEquals(1, generateCount)
    }

    @Test
    fun setup_collectsStructuredIntakeAndExposesDebugAiAction() {
        var experience: TrainingExperience? = null
        var toggledEquipment: Equipment? = null
        var injuryNotes = ""
        var preferences = ""
        var dislikes = ""
        var aiGenerateCount = 0
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedDays = setOf(1),
            aiAvailable = true,
            onExperienceSelected = { experience = it },
            onEquipmentToggled = { toggledEquipment = it },
            onInjuryNotesChanged = { injuryNotes = it },
            onPreferencesChanged = { preferences = it },
            onDislikesChanged = { dislikes = it },
            onGenerateWithAi = { aiGenerateCount += 1 },
        )

        composeRule
            .onNodeWithTag(TestTags.planExperience(TrainingExperience.BEGINNER.name))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(TestTags.planEquipment(Equipment.BARBELL.name))
            .performScrollTo()
            .performClick()
        composeRule
            .onNodeWithTag(TestTags.PLAN_INJURY_NOTES)
            .performScrollTo()
            .performTextReplacement("Shoulder")
        composeRule
            .onNodeWithTag(TestTags.PLAN_PREFERENCES)
            .performScrollTo()
            .performTextReplacement("Rows")
        composeRule
            .onNodeWithTag(TestTags.PLAN_DISLIKES)
            .performScrollTo()
            .performTextReplacement("Burpees")
        composeRule
            .onNodeWithTag(TestTags.PLAN_GENERATE_AI)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals(TrainingExperience.BEGINNER, experience)
        assertEquals(Equipment.BARBELL, toggledEquipment)
        assertEquals("Shoulder", injuryNotes)
        assertEquals("Rows", preferences)
        assertEquals("Burpees", dislikes)
        assertEquals(1, aiGenerateCount)
    }

    @Test
    fun setup_showsSixDayLimitAndKeepsRuleBasedFallbackWithoutEquipment() {
        setPlanContent(
            uiState = PlanUiState.Setup,
            aiAvailable = true,
            intakeOverride =
                PlannerIntakeUiState(
                    selectedDays = (1..6).toSet(),
                    availableEquipment = emptySet(),
                    daySelectionMessage =
                        "Choose up to six workout days so the week keeps a rest day.",
                ),
        )

        composeRule
            .onNodeWithText("Choose up to six workout days so the week keeps a rest day.")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).performScrollTo().assertIsEnabled()
        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE_AI).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun setup_rendersAiLoadingState() {
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedDays = setOf(1),
            aiAvailable = true,
            aiGenerationState = AiGenerationUiState.Generating(1),
        )
        composeRule.onNodeWithTag(TestTags.PLAN_AI_GENERATING).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun setup_rendersAiFailureState() {
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedDays = setOf(1),
            aiAvailable = true,
            aiGenerationState =
                AiGenerationUiState.Failed(PlanningFailure.ProviderError("offline")),
        )
        composeRule.onNodeWithText("Draft unavailable").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun setup_rendersValidatedAiState() {
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedDays = setOf(1),
            aiAvailable = true,
            aiGenerationState = AiGenerationUiState.Validated(validatedDraft()),
        )
        composeRule.onNodeWithText("Draft ready").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("DEBUG FAKE AI", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun setup_rendersStaleAiState() {
        setPlanContent(
            uiState = PlanUiState.Setup,
            selectedDays = setOf(1),
            aiAvailable = true,
            aiGenerationState = AiGenerationUiState.Stale,
        )

        composeRule.onNodeWithText("Plan inputs changed").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("Generate again to build a fresh draft.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun review_isStaticAndOmitsForbiddenEditingControls() {
        setPlanContent(PlanUiState.Review(generated))

        composeRule.onNodeWithTag(TestTags.workout(mondayWorkout.id)).assertIsDisplayed()
        composeRule.onNodeWithText("Upper Body", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Bench Press").assertIsDisplayed()
        composeRule.onNodeWithText("3×10 · 20kg").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.planReviewDay(mondayWorkout.id)).assertHasNoClickAction()
        composeRule.onNodeWithTag(TestTags.planExercise(mondayExercise.id)).assertHasNoClickAction()
        composeRule.onNodeWithText("ADD EXERCISE").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Remove exercise").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Move up").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Move down").assertDoesNotExist()
    }

    @Test
    fun review_wholeWorkoutDeleteForwardsWorkoutId() {
        var deletedWorkoutId: String? = null
        setPlanContent(
            uiState = PlanUiState.Review(generated),
            onDeleteWorkout = { deletedWorkoutId = it },
        )

        composeRule.onNodeWithContentDescription("Remove Upper Body on Monday").performClick()

        assertEquals(mondayWorkout.id, deletedWorkoutId)
    }

    @Test
    fun review_acceptAndRegenerateInvokeTheirCallbacks() {
        var acceptCount = 0
        var regenerateCount = 0
        setPlanContent(
            uiState = PlanUiState.Review(generated),
            onAccept = { acceptCount += 1 },
            onBackToSetup = { regenerateCount += 1 },
        )

        composeRule.onNodeWithText("ACCEPT PLAN").performScrollTo().performClick()
        composeRule.onNodeWithText("REGENERATE").performScrollTo().performClick()

        assertEquals(1, acceptCount)
        assertEquals(1, regenerateCount)
    }

    @Test
    fun review_withoutWorkouts_disablesAccept() {
        setPlanContent(
            PlanUiState.Review(generated.copy(workouts = emptyList(), exercises = emptyList()))
        )

        composeRule.onNodeWithText("ACCEPT PLAN").assertIsNotEnabled()
    }

    @Test
    fun acceptedWithActiveSession_showsResumeGuidanceWithoutStart() {
        setPlanContent(
            PlanUiState.Accepted(
                planned = 1,
                completed = 0,
                workouts = listOf(mondayWorkout),
                todayWorkout = mondayWorkout,
                nextWorkout = mondayWorkout,
                hasActiveSession = true,
            )
        )

        composeRule.onNodeWithText("SESSION IN PROGRESS").assertIsDisplayed()
        composeRule
            .onNodeWithText("Switch to the Active tab to continue your workout.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").assertDoesNotExist()
    }

    @Test
    fun acceptedRestDay_showsNextWorkoutAndCounts() {
        setPlanContent(
            PlanUiState.Accepted(
                planned = 3,
                completed = 1,
                workouts = listOf(mondayWorkout),
                todayWorkout = null,
                nextWorkout = mondayWorkout,
                hasActiveSession = false,
            )
        )

        composeRule.onNodeWithText("NO WORKOUT TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("Next workout: MON · Upper Body").assertIsDisplayed()
        composeRule.onNodeWithText("3 workouts planned · 1 completed").assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").assertDoesNotExist()
    }

    @Test
    fun acceptedWorkoutDay_startsAndOpensTheSelectedWorkout() {
        var startCount = 0
        var openedWorkoutId: String? = null
        setPlanContent(
            uiState =
                PlanUiState.Accepted(
                    planned = 1,
                    completed = 0,
                    workouts = listOf(mondayWorkout),
                    todayWorkout = mondayWorkout,
                    nextWorkout = mondayWorkout,
                    hasActiveSession = false,
                ),
            onStartWorkout = { startCount += 1 },
            onOpenWorkoutPreview = { openedWorkoutId = it },
        )

        composeRule.onNodeWithText("WORKOUT DAY TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").performClick()
        composeRule
            .onNodeWithTag(TestTags.workout(mondayWorkout.id))
            .performScrollTo()
            .performClick()

        assertEquals(1, startCount)
        assertEquals(mondayWorkout.id, openedWorkoutId)
    }

    private fun validatedDraft(): ValidatedPlanDraft {
        val draft =
            PlanDraft(
                targetWeekStart = java.time.LocalDate.parse("2026-07-20"),
                workouts =
                    listOf(
                        WorkoutDraft(
                            dayOfWeek = 1,
                            scheduledDate = java.time.LocalDate.parse("2026-07-20"),
                            title = "Draft",
                            exercises =
                                listOf(
                                    ExerciseDraft(
                                        ExerciseCatalogIds.PLANK_HOLD,
                                        2,
                                        1,
                                        0.0,
                                    )
                                ),
                        )
                    ),
                providerMetadata = PlanningProviderMetadata(PlanningEngineType.DEBUG_FAKE_AI, 1),
            )
        return ValidatedPlanDraft.create(
            draft,
            PlanValidationContext(
                expectedTargetWeekStart = draft.targetWeekStart,
                invokedEngineType = PlanningEngineType.DEBUG_FAKE_AI,
                selectedDays = setOf(1),
                experience = TrainingExperience.INTERMEDIATE,
                availableEquipment = Equipment.entries.toSet(),
            ),
            java.time.Instant.parse("2026-07-19T12:00:00Z"),
        )
    }
}
