package com.example.ironpath.domain.planner

import com.example.ironpath.testutil.FakeTimeProvider
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPlanReviewEditorTest {
    private val catalog = DefaultExerciseCatalog()
    private val timeProvider = FakeTimeProvider()
    private val eligibilityPolicy = ExerciseEligibilityPolicy(catalog)
    private val validator = PlanValidator(catalog, timeProvider, eligibilityPolicy)
    private val editor = AiPlanReviewEditor(validator, eligibilityPolicy)
    private val targetMonday = LocalDate.parse("2026-07-20")

    @Test
    fun `start preserves validated token and exposes only eligible catalog entries`() {
        val token = validatedToken()

        val state = editor.start(token)

        assertTrue(state is AiPlanDraftReviewState.Valid)
        assertSame(token, (state as AiPlanDraftReviewState.Valid).validatedPlan)
        assertFalse(
            editor.eligibleEntries(state).any { it.id == ExerciseCatalogIds.WEIGHTED_PULL_UPS }
        )
    }

    @Test
    fun `adding an eligible catalog exercise revalidates with the frozen context`() {
        val initial = editor.start(validatedToken())
        val frozenContext = initial.context

        val edited =
            editor.addExercise(
                initial,
                workoutDay = 1,
                exercise = exercise(ExerciseCatalogIds.GLUTE_BRIDGES),
            )

        assertTrue(edited is AiPlanDraftReviewState.Valid)
        assertSame(frozenContext, edited.context)
        assertEquals(
            listOf(ExerciseCatalogIds.PUSH_UPS, ExerciseCatalogIds.GLUTE_BRIDGES),
            edited.draft.workouts.single().exercises.map(ExerciseDraft::catalogId),
        )
    }

    @Test
    fun `unknown duplicate ineligible and missing-day adds are ignored`() {
        val initial = editor.start(validatedToken())
        val attempts =
            listOf(
                editor.addExercise(initial, 1, exercise(ExerciseCatalogId("unknown"))),
                editor.addExercise(initial, 1, exercise(ExerciseCatalogIds.PUSH_UPS)),
                editor.addExercise(initial, 1, exercise(ExerciseCatalogIds.WEIGHTED_PULL_UPS)),
                editor.addExercise(initial, 7, exercise(ExerciseCatalogIds.HAMMER_CURLS)),
            )

        assertTrue(attempts.all { it === initial })
    }

    @Test
    fun `replace blocks duplicate and ineligible catalog entries`() {
        val withTwoExercises =
            editor.addExercise(
                editor.start(validatedToken()),
                workoutDay = 1,
                exercise = exercise(ExerciseCatalogIds.HAMMER_CURLS),
            )

        assertSame(
            withTwoExercises,
            editor.replaceExercise(
                withTwoExercises,
                workoutDay = 1,
                originalId = ExerciseCatalogIds.PUSH_UPS,
                replacement = exercise(ExerciseCatalogIds.HAMMER_CURLS),
            ),
        )
        assertSame(
            withTwoExercises,
            editor.replaceExercise(
                withTwoExercises,
                workoutDay = 1,
                originalId = ExerciseCatalogIds.PUSH_UPS,
                replacement = exercise(ExerciseCatalogIds.WEIGHTED_PULL_UPS),
            ),
        )
    }

    @Test
    fun `invalid prescription removes accept token and a later valid edit restores it`() {
        val initial = editor.start(validatedToken())

        val invalid =
            editor.replaceExercise(
                initial,
                workoutDay = 1,
                originalId = ExerciseCatalogIds.PUSH_UPS,
                replacement = exercise(ExerciseCatalogIds.PUSH_UPS, sets = 0),
            )

        assertTrue(invalid is AiPlanDraftReviewState.Invalid)
        assertFalse(invalid.canAccept)
        assertTrue(
            (invalid as AiPlanDraftReviewState.Invalid).violations.any {
                it.code == PlanViolationCode.INVALID_SET_COUNT
            }
        )

        val recovered =
            editor.replaceExercise(
                invalid,
                workoutDay = 1,
                originalId = ExerciseCatalogIds.PUSH_UPS,
                replacement = exercise(ExerciseCatalogIds.PUSH_UPS, sets = 3),
            )

        assertTrue(recovered is AiPlanDraftReviewState.Valid)
        assertTrue(recovered.canAccept)
    }

    @Test
    fun `revalidation never recomputes target week or intake constraints`() {
        val initial = editor.start(validatedToken())
        val originalContext = initial.context

        val edited =
            editor.replaceExercise(
                initial,
                workoutDay = 1,
                originalId = ExerciseCatalogIds.PUSH_UPS,
                replacement = exercise(ExerciseCatalogIds.PUSH_UPS, reps = 12),
            )

        assertSame(originalContext, edited.context)
        assertEquals(targetMonday, edited.context.expectedTargetWeekStart)
        assertEquals(setOf(Equipment.BODYWEIGHT), edited.context.availableEquipment)
    }

    private fun validatedToken(): ValidatedPlanDraft {
        val context =
            PlanValidationContext(
                expectedTargetWeekStart = targetMonday,
                invokedEngineType = PlanningEngineType.ON_DEVICE_AI,
                selectedDays = setOf(1),
                experience = TrainingExperience.BEGINNER,
                availableEquipment = setOf(Equipment.BODYWEIGHT),
            )
        val draft =
            PlanDraft(
                targetWeekStart = targetMonday,
                workouts =
                    listOf(
                        WorkoutDraft(
                            dayOfWeek = 1,
                            scheduledDate = targetMonday,
                            title = "Full body",
                            exercises = listOf(exercise(ExerciseCatalogIds.PUSH_UPS)),
                        )
                    ),
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = PlanningEngineType.ON_DEVICE_AI,
                        generationDurationMillis = 20,
                    ),
            )
        return (validator.validate(draft, context) as PlanValidationResult.Valid).validatedPlan
    }

    private fun exercise(
        id: ExerciseCatalogId,
        sets: Int = 3,
        reps: Int = 10,
    ) = ExerciseDraft(id, sets = sets, reps = reps, targetWeightKg = 0.0)
}
