package com.example.ironpath.ui.screens.plan

import app.cash.turbine.test
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.AiPlanDraftReviewState
import com.example.ironpath.domain.planner.AiPlanReviewEditor
import com.example.ironpath.domain.planner.DefaultExerciseCatalog
import com.example.ironpath.domain.planner.Equipment
import com.example.ironpath.domain.planner.ExerciseCatalogIds
import com.example.ironpath.domain.planner.ExerciseDraft
import com.example.ironpath.domain.planner.ExerciseEligibilityPolicy
import com.example.ironpath.domain.planner.PlanDraft
import com.example.ironpath.domain.planner.PlanEntityMapper
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.PlanValidationContext
import com.example.ironpath.domain.planner.PlanValidationResult
import com.example.ironpath.domain.planner.PlanValidator
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningProviderMetadata
import com.example.ironpath.domain.planner.TrainingExperience
import com.example.ironpath.domain.planner.ValidatedPlanDraft
import com.example.ironpath.domain.planner.ValidatedPlanDraftMapper
import com.example.ironpath.domain.planner.WorkoutDraft
import com.example.ironpath.domain.planner.requiresTargetLoad
import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelAiReviewTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val catalog = DefaultExerciseCatalog()
    private val timeProvider = FakeTimeProvider()
    private val eligibilityPolicy = ExerciseEligibilityPolicy(catalog)
    private val validator = PlanValidator(catalog, timeProvider, eligibilityPolicy)
    private lateinit var planRepository: PlanRepository
    private lateinit var viewModel: PlanViewModel

    @Before
    fun setUp() {
        planRepository = mockk(relaxed = true)
        every { planRepository.observeActivePlan() } returns flowOf(null)
        every { planRepository.observeWorkoutsForPlan(any()) } returns flowOf(emptyList())
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        every { sessionRepository.observeActiveSession() } returns flowOf(null)
        viewModel =
            PlanViewModel(
                planRepository = planRepository,
                planGenerator = mockk<PlanGenerator>(relaxed = true),
                sessionRepository = sessionRepository,
                timeProvider = timeProvider,
                aiPlanReviewEditor = AiPlanReviewEditor(validator, eligibilityPolicy),
                validatedPlanDraftMapper =
                    ValidatedPlanDraftMapper(
                        PlanEntityMapper(FakeIdProvider(), timeProvider, catalog)
                    ),
            )
    }

    @Test
    fun `validated handoff enters AI review and duplicate effect is idempotent`() = runTest {
        val token = validatedToken()

        assertTrue(viewModel.enterAiReview(token))
        val first = viewModel.aiReviewState.value
        assertTrue(viewModel.enterAiReview(token))

        assertSame(first, viewModel.aiReviewState.value)
        assertSame(
            token,
            (first!!.review as AiPlanDraftReviewState.Valid).validatedPlan,
        )
        viewModel.planUiState.test {
            var state = awaitItem()
            while (state !is PlanUiState.AiReview) state = awaitItem()
            assertSame(first, state.review)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `invalid edit removes acceptance token and cannot be persisted`() = runTest {
        viewModel.enterAiReview(validatedToken())

        viewModel.replaceAiExercise(
            workoutDay = 1,
            originalId = ExerciseCatalogIds.PUSH_UPS,
            replacement = exercise(ExerciseCatalogIds.PUSH_UPS, sets = 0),
        )
        viewModel.acceptPlan {}
        runCurrent()

        val state = viewModel.aiReviewState.value!!
        assertTrue(state.review is AiPlanDraftReviewState.Invalid)
        assertFalse(state.canAccept)
        coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any()) }
    }

    @Test
    fun `repository failure keeps review and retry uses the same mapped ids`() = runTest {
        val attempts =
            mutableListOf<Triple<WeeklyPlan, List<PlannedWorkout>, List<PlannedExercise>>>()
        coEvery { planRepository.createPlan(any(), any(), any()) } coAnswers
            {
                attempts += Triple(firstArg(), secondArg(), thirdArg())
                if (attempts.size == 1) error("database unavailable")
            }
        viewModel.enterAiReview(validatedToken())
        var callbackCount = 0

        viewModel.acceptPlan { callbackCount += 1 }
        runCurrent()

        assertEquals(0, callbackCount)
        assertNotNull(viewModel.aiReviewState.value)
        assertEquals(
            "Could not save this plan. Try again.",
            viewModel.aiReviewState.value!!.saveError
        )

        viewModel.acceptPlan { callbackCount += 1 }
        runCurrent()

        assertEquals(1, callbackCount)
        assertNull(viewModel.aiReviewState.value)
        assertEquals(2, attempts.size)
        assertEquals(attempts[0].first.id, attempts[1].first.id)
        assertEquals(attempts[0].second.map { it.id }, attempts[1].second.map { it.id })
        assertEquals(attempts[0].third.map { it.id }, attempts[1].third.map { it.id })
    }

    @Test
    fun `duplicate AI accept is ignored while persistence is running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { planRepository.createPlan(any(), any(), any()) } coAnswers { gate.await() }
        viewModel.enterAiReview(validatedToken())
        var callbackCount = 0

        viewModel.acceptPlan { callbackCount += 1 }
        viewModel.acceptPlan { callbackCount += 1 }
        runCurrent()

        assertTrue(viewModel.aiReviewState.value!!.isAccepting)
        coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any()) }
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, callbackCount)
    }

    @Test
    fun `replacement token is queued while persistence is running and shown after failure`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            coEvery { planRepository.createPlan(any(), any(), any()) } coAnswers
                {
                    gate.await()
                    error("database unavailable")
                }
            val original = validatedToken()
            val replacement = validatedToken(reps = 12)
            viewModel.enterAiReview(original)
            viewModel.acceptPlan {}
            runCurrent()

            assertTrue(viewModel.enterAiReview(replacement))
            assertSame(original, viewModel.aiReviewState.value!!.sourceToken)

            gate.complete(Unit)
            runCurrent()

            assertSame(replacement, viewModel.aiReviewState.value!!.sourceToken)
            assertTrue(viewModel.aiReviewState.value!!.canAccept)
        }

    @Test
    fun `nonbodyweight zero load cannot be accepted or persisted`() = runTest {
        val exerciseId = ExerciseCatalogIds.DUMBBELL_ROWS
        viewModel.enterAiReview(
            validatedToken(
                exerciseId = exerciseId,
                equipment = setOf(Equipment.DUMBBELL, Equipment.BENCH),
            )
        )
        viewModel.replaceAiExercise(
            workoutDay = 1,
            originalId = exerciseId,
            replacement =
                ExerciseDraft(
                    catalogId = exerciseId,
                    sets = 3,
                    reps = 10,
                    targetWeightKg = 0.0,
                ),
        )

        viewModel.acceptPlan {}
        runCurrent()

        assertFalse(viewModel.aiReviewState.value!!.canAccept)
        coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any()) }
    }

    @Test
    fun `zero load band exercise remains an intentional unloaded prescription`() = runTest {
        viewModel.enterAiReview(
            validatedToken(
                exerciseId = ExerciseCatalogIds.BAND_PULL_APARTS,
                equipment = setOf(Equipment.RESISTANCE_BAND),
            )
        )

        assertTrue(viewModel.aiReviewState.value!!.canAccept)
    }

    @Test
    fun `editing after save failure clears error and remaps the corrected draft`() = runTest {
        val attempts = mutableListOf<List<PlannedExercise>>()
        coEvery { planRepository.createPlan(any(), any(), any()) } coAnswers
            {
                attempts += thirdArg<List<PlannedExercise>>()
                if (attempts.size == 1) error("database unavailable")
            }
        viewModel.enterAiReview(validatedToken())
        viewModel.acceptPlan {}
        runCurrent()
        assertNotNull(viewModel.aiReviewState.value!!.saveError)

        viewModel.replaceAiExercise(
            workoutDay = 1,
            originalId = ExerciseCatalogIds.PUSH_UPS,
            replacement = exercise(ExerciseCatalogIds.PUSH_UPS, reps = 12),
        )

        assertNull(viewModel.aiReviewState.value!!.saveError)
        viewModel.acceptPlan {}
        runCurrent()
        assertEquals(10, attempts[0].single().reps)
        assertEquals(12, attempts[1].single().reps)
    }

    private fun validatedToken(
        exerciseId: com.example.ironpath.domain.planner.ExerciseCatalogId =
            ExerciseCatalogIds.PUSH_UPS,
        reps: Int = 10,
        equipment: Set<Equipment> = setOf(Equipment.BODYWEIGHT),
    ): ValidatedPlanDraft {
        val targetMonday = timeProvider.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val context =
            PlanValidationContext(
                expectedTargetWeekStart = targetMonday,
                invokedEngineType = PlanningEngineType.DEBUG_FAKE_AI,
                selectedDays = setOf(1),
                experience = TrainingExperience.BEGINNER,
                availableEquipment = equipment,
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
                            exercises = listOf(exercise(exerciseId, reps = reps)),
                        )
                    ),
                rationale = "A measured return to training.",
                warnings = listOf("Adjust the load if the session feels too demanding."),
                providerMetadata =
                    PlanningProviderMetadata(
                        engineType = PlanningEngineType.DEBUG_FAKE_AI,
                        generationDurationMillis = 25,
                    ),
            )
        return (validator.validate(draft, context) as PlanValidationResult.Valid).validatedPlan
    }

    private fun exercise(
        id: com.example.ironpath.domain.planner.ExerciseCatalogId,
        sets: Int = 3,
        reps: Int = 10,
    ) =
        ExerciseDraft(
            id,
            sets,
            reps,
            targetWeightKg = if (catalog.require(id).requiresTargetLoad()) 10.0 else 0.0,
        )
}
