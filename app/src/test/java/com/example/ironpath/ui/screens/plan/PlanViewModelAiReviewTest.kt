package com.example.ironpath.ui.screens.plan

import app.cash.turbine.test
import com.example.ironpath.data.local.StaleProfileGenerationException
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.account.ProfileGenerationToken
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
import com.example.ironpath.domain.planner.PlanViolationCode
import com.example.ironpath.domain.planner.PlanningEngineType
import com.example.ironpath.domain.planner.PlanningProviderMetadata
import com.example.ironpath.domain.planner.RemotePlanningExperiment
import com.example.ironpath.domain.planner.RemotePlanningExperimentState
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
import java.time.Duration
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
        viewModel = createViewModel()
    }

    private fun createViewModel(
        profileToken: ProfileGenerationToken? = null,
        remoteState: MutableStateFlow<RemotePlanningExperimentState>? = null,
    ): PlanViewModel {
        val sessionRepository = mockk<SessionRepository>(relaxed = true)
        every { sessionRepository.observeActiveSession() } returns flowOf(null)
        val experiment =
            remoteState?.let { state ->
                mockk<RemotePlanningExperiment> { every { this@mockk.state } returns state }
            }
        return PlanViewModel(
            planRepository = planRepository,
            planGenerator = mockk<PlanGenerator>(relaxed = true),
            sessionRepository = sessionRepository,
            timeProvider = timeProvider,
            aiPlanReviewEditor = AiPlanReviewEditor(validator, eligibilityPolicy),
            validatedPlanDraftMapper =
                ValidatedPlanDraftMapper(PlanEntityMapper(FakeIdProvider(), timeProvider, catalog)),
            profileGenerationToken = profileToken,
            remotePlanningExperiment = experiment,
        )
    }

    @Test
    fun `AI acceptance waits for captured profile generation and forwards it to the write gate`() =
        runTest {
            var generation: Long? = null
            val initialization = CompletableDeferred<Unit>()
            val profileToken = mockk<ProfileGenerationToken>()
            every { profileToken.current() } answers { generation }
            coEvery { profileToken.initialize() } coAnswers
                {
                    initialization.await()
                    7L.also { generation = it }
                }
            viewModel = createViewModel(profileToken)
            viewModel.enterAiReview(validatedToken())

            viewModel.acceptPlan {}
            coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
            initialization.complete(Unit)
            runCurrent()
            viewModel.acceptPlan {}
            runCurrent()

            coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any(), 7L) }
        }

    @Test
    fun `profile generation rejection retains the reviewed draft and cannot report acceptance`() =
        runTest {
            val profileToken = mockk<ProfileGenerationToken>()
            every { profileToken.current() } returns 7L
            coEvery { profileToken.initialize() } returns 7L
            coEvery { planRepository.createPlan(any(), any(), any(), 7L) } throws
                StaleProfileGenerationException()
            viewModel = createViewModel(profileToken)
            val token = validatedToken()
            viewModel.enterAiReview(token)
            var accepted = false

            viewModel.acceptPlan { accepted = true }
            runCurrent()

            assertFalse(accepted)
            assertEquals(token.draft, viewModel.aiReviewState.value!!.review.draft)
            assertNotNull(viewModel.aiReviewState.value!!.saveError)
            assertFalse(viewModel.aiReviewState.value!!.isAccepting)
            coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any(), 7L) }
        }

    @Test
    fun `stale remote metadata cannot enter review or replace a current draft`() = runTest {
        val remoteState = configuredRemoteState()
        viewModel = createViewModel(remoteState = remoteState)
        val current = validatedToken(remoteRevision = remoteState.value.revision)
        assertTrue(viewModel.enterAiReview(current))

        assertFalse(viewModel.enterAiReview(validatedToken(remoteRevision = 0)))

        assertSame(current, viewModel.aiReviewState.value!!.sourceToken)
        coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
    }

    @Test
    fun `fallback handoff from an older remote configuration is rejected`() = runTest {
        val remoteState = configuredRemoteState()
        viewModel = createViewModel(remoteState = remoteState)
        val fallback = validatedToken()
        assertFalse(viewModel.enterAiReview(fallback, configurationRevision = 0))
        assertNull(viewModel.aiReviewState.value)
        assertTrue(viewModel.enterAiReview(fallback, configurationRevision = 1))
    }

    @Test
    fun `queued fallback cannot survive configuration change during failed save`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remoteState = configuredRemoteState()
        viewModel = createViewModel(remoteState = remoteState)
        viewModel.enterAiReview(validatedToken(remoteRevision = 1))
        runCurrent()
        val fallback = validatedToken()
        coEvery { planRepository.createPlan(any(), any(), any(), any()) } coAnswers
            {
                assertTrue(viewModel.enterAiReview(fallback, configurationRevision = 1))
                remoteState.value = remoteState.value.copy(revision = 2)
                error("Synthetic write failure")
            }

        viewModel.acceptPlan {}
        runCurrent()

        assertNull(viewModel.aiReviewState.value)
    }

    @Test
    fun `queued current fallback survives delayed configuration collector after failed save`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val remoteState = configuredRemoteState()
            viewModel = createViewModel(remoteState = remoteState)
            viewModel.enterAiReview(validatedToken(remoteRevision = 1))
            runCurrent()
            val fallback = validatedToken()
            coEvery { planRepository.createPlan(any(), any(), any(), any()) } coAnswers
                {
                    remoteState.value = remoteState.value.copy(revision = 2)
                    assertTrue(viewModel.enterAiReview(fallback, configurationRevision = 2))
                    error("Synthetic write failure")
                }

            viewModel.acceptPlan {}
            runCurrent()

            assertSame(fallback, viewModel.aiReviewState.value!!.sourceToken)
        }

    @Test
    fun `remote metadata cannot enter review without a matching experiment`() = runTest {
        assertFalse(viewModel.enterAiReview(validatedToken(remoteRevision = 1)))

        assertNull(viewModel.aiReviewState.value)
        coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
    }

    @Test
    fun `key provider and disable revisions each invalidate an unaccepted draft`() = runTest {
        val initial = configuredRemoteState().value
        val changes =
            listOf(
                initial.copy(apiKey = "replacement-synthetic-key", revision = 2),
                initial.copy(optionId = "provider-b", revision = 2),
                initial.copy(enabled = false, apiKey = "", revision = 2),
            )
        for (changed in changes) {
            val remoteState = MutableStateFlow(initial)
            viewModel = createViewModel(remoteState = remoteState)
            viewModel.enterAiReview(validatedToken(remoteRevision = initial.revision))
            runCurrent()

            remoteState.value = changed
            runCurrent()

            assertNull(viewModel.aiReviewState.value)
        }
        coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
    }

    @Test
    fun `accept sees a changed revision before the config collector and performs zero writes`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val remoteState = configuredRemoteState()
            viewModel = createViewModel(remoteState = remoteState)
            viewModel.enterAiReview(validatedToken(remoteRevision = 1))
            runCurrent()
            remoteState.value = remoteState.value.copy(revision = 2)
            var callbackCount = 0

            viewModel.acceptPlan { callbackCount += 1 }

            assertNull(viewModel.aiReviewState.value)
            runCurrent()
            assertEquals(0, callbackCount)
            coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
        }

    @Test
    fun `revision change after the click but before the save coroutine performs zero writes`() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val remoteState = configuredRemoteState()
            viewModel = createViewModel(remoteState = remoteState)
            viewModel.enterAiReview(validatedToken(remoteRevision = 1))
            runCurrent()
            var callbackCount = 0
            viewModel.acceptPlan { callbackCount += 1 }

            remoteState.value = remoteState.value.copy(revision = 2)
            runCurrent()

            assertNull(viewModel.aiReviewState.value)
            assertEquals(0, callbackCount)
            coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
        }

    @Test
    fun `configuration change cancels a suspended save without an accepted callback`() = runTest {
        val remoteState = configuredRemoteState()
        viewModel = createViewModel(remoteState = remoteState)
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { planRepository.createPlan(any(), any(), any(), any()) } coAnswers
            {
                try {
                    gate.await()
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                }
            }
        viewModel.enterAiReview(validatedToken(remoteRevision = 1))
        var callbackCount = 0
        viewModel.acceptPlan { callbackCount += 1 }
        runCurrent()
        assertTrue(viewModel.aiReviewState.value!!.isAccepting)

        remoteState.value = remoteState.value.copy(revision = 2)
        runCurrent()
        gate.complete(Unit)
        runCurrent()

        assertTrue(cancelled)
        assertNull(viewModel.aiReviewState.value)
        assertEquals(0, callbackCount)
        coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any(), any()) }
    }

    @Test
    fun `late return from a cancelled save cannot report acceptance`() = runTest {
        val remoteState = configuredRemoteState()
        viewModel = createViewModel(remoteState = remoteState)
        val gate = CompletableDeferred<Unit>()
        var cancelled = false
        coEvery { planRepository.createPlan(any(), any(), any(), any()) } coAnswers
            {
                try {
                    gate.await()
                } catch (_: CancellationException) {
                    cancelled = true
                    // Deliberately emulate a dependency returning after cancellation.
                }
            }
        viewModel.enterAiReview(validatedToken(remoteRevision = 1))
        var callbackCount = 0
        viewModel.acceptPlan { callbackCount += 1 }
        runCurrent()

        remoteState.value = remoteState.value.copy(revision = 2)
        runCurrent()

        assertTrue(cancelled)
        assertNull(viewModel.aiReviewState.value)
        assertEquals(0, callbackCount)
        coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any(), any()) }
    }

    @Test
    fun `current revision draft survives delayed configuration collection`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remoteState = configuredRemoteState()
        viewModel = createViewModel(remoteState = remoteState)
        viewModel.enterAiReview(validatedToken(remoteRevision = 1))
        runCurrent()
        remoteState.value = remoteState.value.copy(revision = 2)
        val current = validatedToken(remoteRevision = 2)

        assertTrue(viewModel.enterAiReview(current))
        runCurrent()

        assertSame(current, viewModel.aiReviewState.value!!.sourceToken)
        assertTrue(viewModel.aiReviewState.value!!.canAccept)
        coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
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
    fun `accept revalidates a formerly valid draft against the current day before any write`() =
        runTest {
            val token = validatedToken()
            viewModel.enterAiReview(token)
            timeProvider.advanceBy(Duration.ofDays(5))
            assertTrue(token.draft.workouts.single().scheduledDate.isBefore(timeProvider.today()))
            var callbackCount = 0

            viewModel.acceptPlan { callbackCount += 1 }
            runCurrent()

            val state = viewModel.aiReviewState.value!!
            assertTrue(state.review is AiPlanDraftReviewState.Invalid)
            assertFalse(state.canAccept)
            assertFalse(state.isAccepting)
            assertEquals(token.draft, state.review.draft)
            assertEquals(token.context, state.review.context)
            assertTrue(state.review.violations.any { it.code == PlanViolationCode.WORKOUT_IN_PAST })
            assertEquals(0, callbackCount)
            coVerify(exactly = 0) { planRepository.createPlan(any(), any(), any(), any()) }
        }

    @Test
    fun `retry revalidates before reusing previously mapped ids`() = runTest {
        coEvery { planRepository.createPlan(any(), any(), any(), any()) } throws
            IllegalStateException("database unavailable")
        viewModel.enterAiReview(validatedToken())
        viewModel.acceptPlan {}
        runCurrent()
        assertNotNull(viewModel.aiReviewState.value!!.saveError)
        timeProvider.advanceBy(Duration.ofDays(5))
        var callbackCount = 0

        viewModel.acceptPlan { callbackCount += 1 }
        runCurrent()

        val state = viewModel.aiReviewState.value!!
        assertFalse(state.canAccept)
        assertNull(state.saveError)
        assertTrue(state.review.violations.any { it.code == PlanViolationCode.WORKOUT_IN_PAST })
        assertEquals(0, callbackCount)
        coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any(), any()) }
    }

    @Test
    fun `accept permits a workout scheduled today and issues a fresh validation token`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { planRepository.createPlan(any(), any(), any(), any()) } coAnswers { gate.await() }
        val token = validatedToken()
        viewModel.enterAiReview(token)
        timeProvider.advanceBy(Duration.ofDays(4))
        assertEquals(token.draft.workouts.single().scheduledDate, timeProvider.today())

        viewModel.acceptPlan {}
        runCurrent()

        val validated =
            (viewModel.aiReviewState.value!!.review as AiPlanDraftReviewState.Valid).validatedPlan
        assertEquals(token.draft, validated.draft)
        assertEquals(token.context, validated.context)
        assertEquals(timeProvider.now(), validated.validatedAt)
        assertFalse(token === validated)
        coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any(), any()) }
        gate.complete(Unit)
        runCurrent()
    }

    @Test
    fun `cancelled persistence retains review without treating cancellation as a save error`() =
        runTest {
            coEvery { planRepository.createPlan(any(), any(), any(), any()) } throws
                CancellationException("cancelled")
            viewModel.enterAiReview(validatedToken())
            var callbackCount = 0

            viewModel.acceptPlan { callbackCount += 1 }
            runCurrent()

            assertFalse(viewModel.aiReviewState.value!!.isAccepting)
            assertNull(viewModel.aiReviewState.value!!.saveError)
            assertTrue(viewModel.aiReviewState.value!!.canAccept)
            assertEquals(0, callbackCount)
            coVerify(exactly = 1) { planRepository.createPlan(any(), any(), any(), any()) }
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
        assertFalse(attempts[0].single().id == attempts[1].single().id)
    }

    private fun validatedToken(
        exerciseId: com.example.ironpath.domain.planner.ExerciseCatalogId =
            ExerciseCatalogIds.PUSH_UPS,
        reps: Int = 10,
        equipment: Set<Equipment> = setOf(Equipment.BODYWEIGHT),
        remoteRevision: Long? = null,
    ): ValidatedPlanDraft {
        val engineType =
            if (remoteRevision == null) PlanningEngineType.DEBUG_FAKE_AI
            else PlanningEngineType.DEBUG_REMOTE_AI
        val targetMonday = timeProvider.today().with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val context =
            PlanValidationContext(
                expectedTargetWeekStart = targetMonday,
                invokedEngineType = engineType,
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
                        engineType = engineType,
                        generationDurationMillis = 25,
                        remoteConfigurationRevision = remoteRevision,
                    ),
            )
        return (validator.validate(draft, context) as PlanValidationResult.Valid).validatedPlan
    }

    private fun configuredRemoteState() =
        MutableStateFlow(
            RemotePlanningExperimentState(
                available = true,
                enabled = true,
                apiKey = "synthetic-key",
                optionId = "provider-a",
                revision = 1,
            )
        )

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
