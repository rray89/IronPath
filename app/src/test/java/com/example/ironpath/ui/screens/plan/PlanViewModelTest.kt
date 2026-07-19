package com.example.ironpath.ui.screens.plan

import app.cash.turbine.test
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.TrainingGoal
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PlanViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var planRepository: PlanRepository
    private lateinit var planGenerator: PlanGenerator
    private lateinit var sessionRepository: SessionRepository
    private lateinit var viewModel: PlanViewModel
    private lateinit var timeProvider: FakeTimeProvider

    private fun makeWorkout(
        id: String,
        dayOfWeek: Int,
        planId: String = "plan1",
        scheduledDate: String = "2026-04-${13 + dayOfWeek}",
        status: WorkoutStatus = WorkoutStatus.Upcoming,
    ) =
        PlannedWorkout(
            id = id,
            weeklyPlanId = planId,
            dayOfWeek = dayOfWeek,
            scheduledDate = scheduledDate,
            title = "Workout $id",
            status = status,
        )

    private fun makeExercise(id: String, workoutId: String, orderIndex: Int = 0) =
        PlannedExercise(
            id = id,
            plannedWorkoutId = workoutId,
            name = "Exercise $id",
            sets = 3,
            reps = 10,
            weightKg = 20.0,
            orderIndex = orderIndex,
        )

    private fun makeGeneratedPlan(
        workouts: List<PlannedWorkout>,
        exercises: List<PlannedExercise>,
    ): GeneratedPlan {
        val plan =
            WeeklyPlan(
                id = "plan1",
                startDate = "2026-04-14",
                endDate = "2026-04-20",
                createdAt = timeProvider.epochMillis(),
            )
        return GeneratedPlan(plan = plan, workouts = workouts, exercises = exercises)
    }

    private fun setupReview(plan: GeneratedPlan) {
        every { planGenerator.generate(any(), any()) } returns plan
        viewModel.toggleDay(1)
        viewModel.generatePlan()
    }

    @Before
    fun setUp() {
        planRepository = mockk(relaxed = true)
        planGenerator = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        timeProvider = FakeTimeProvider()

        every { planRepository.observeActivePlan() } returns flowOf(null)
        every { planRepository.observeWorkoutsForPlan(any()) } returns flowOf(emptyList())
        every { sessionRepository.observeActiveSession() } returns flowOf(null)

        viewModel = PlanViewModel(planRepository, planGenerator, sessionRepository, timeProvider)
    }

    private suspend fun <T : PlanUiState> app.cash.turbine.TurbineTestContext<PlanUiState>
        .awaitState(clazz: Class<T>): T {
        var item = awaitItem()
        while (!clazz.isInstance(item)) item = awaitItem()
        @Suppress("UNCHECKED_CAST") return item as T
    }

    @Test
    fun `setGoal updates selected goal`() {
        viewModel.setGoal(TrainingGoal.Hypertrophy)

        assertEquals(TrainingGoal.Hypertrophy, viewModel.selectedGoal.value)
    }

    @Test
    fun `toggleDay adds and removes the selected day`() {
        viewModel.toggleDay(3)
        assertEquals(setOf(3), viewModel.selectedDays.value)

        viewModel.toggleDay(3)
        assertTrue(viewModel.selectedDays.value.isEmpty())
    }

    @Test
    fun `generatePlan sets generatedPlan returned by PlanGenerator`() = runTest {
        val expected =
            makeGeneratedPlan(
                workouts = listOf(makeWorkout("w1", 1)),
                exercises = listOf(makeExercise("ex1", "w1")),
            )
        every { planGenerator.generate(TrainingGoal.Strength, setOf(1)) } returns expected

        viewModel.toggleDay(1)
        viewModel.generatePlan()

        assertEquals(expected, viewModel.generatedPlan.value)
    }

    @Test
    fun `generatePlan does nothing when no days selected`() {
        viewModel.generatePlan()

        assertNull(viewModel.generatedPlan.value)
    }

    @Test
    fun `generatePlan does not query edit-only exercise suggestions`() = runTest {
        every { planGenerator.generate(any(), any()) } returns
            makeGeneratedPlan(listOf(makeWorkout("w1", 1)), emptyList())

        viewModel.toggleDay(1)
        viewModel.generatePlan()

        coVerify(exactly = 0) { planRepository.getAllExerciseNames() }
    }

    @Test
    fun `accepted state does not treat future same-weekday workout as today`() = runTest {
        val activePlan =
            WeeklyPlan(
                id = "plan1",
                startDate = "2026-04-14",
                endDate = "2026-04-20",
                createdAt = timeProvider.epochMillis(),
            )
        val activePlanFlow = MutableStateFlow<WeeklyPlan?>(null)
        val workoutsFlow = MutableStateFlow<List<PlannedWorkout>>(emptyList())
        val nextWeekSameDay = timeProvider.today().plusWeeks(1)
        val futureWorkout =
            makeWorkout(
                id = "future",
                dayOfWeek = nextWeekSameDay.dayOfWeek.value,
                scheduledDate = nextWeekSameDay.toString(),
            )

        every { planRepository.observeActivePlan() } returns activePlanFlow
        every { planRepository.observeWorkoutsForPlan("plan1") } returns workoutsFlow
        viewModel = PlanViewModel(planRepository, planGenerator, sessionRepository, timeProvider)

        viewModel.planUiState.test {
            activePlanFlow.value = activePlan
            workoutsFlow.value = listOf(futureWorkout)
            var state = awaitState(PlanUiState.Accepted::class.java)
            while (state.nextWorkout == null) state = awaitState(PlanUiState.Accepted::class.java)
            assertNull(state.todayWorkout)
            assertEquals(futureWorkout, state.nextWorkout)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `accepted state exposes completion count and active session`() = runTest {
        val activePlan =
            WeeklyPlan(
                id = "plan1",
                startDate = "2026-04-14",
                endDate = "2026-04-20",
                createdAt = timeProvider.epochMillis(),
            )
        val activePlanFlow = MutableStateFlow<WeeklyPlan?>(null)
        val workoutsFlow = MutableStateFlow<List<PlannedWorkout>>(emptyList())
        val activeSession =
            com.example.ironpath.data.local.entity.ActiveSession(
                id = "session",
                sourcePlannedWorkoutId = "w1",
                workoutTitle = "Workout",
                startedAt = 1L,
                lastUpdatedAt = 1L,
            )
        every { planRepository.observeActivePlan() } returns activePlanFlow
        every { planRepository.observeWorkoutsForPlan("plan1") } returns workoutsFlow
        every { sessionRepository.observeActiveSession() } returns flowOf(activeSession)
        viewModel = PlanViewModel(planRepository, planGenerator, sessionRepository, timeProvider)

        viewModel.planUiState.test {
            activePlanFlow.value = activePlan
            workoutsFlow.value =
                listOf(
                    makeWorkout("w1", 1, status = WorkoutStatus.Completed),
                    makeWorkout("w2", 2),
                )
            var state = awaitState(PlanUiState.Accepted::class.java)
            while (state.workouts.size < 2) state = awaitState(PlanUiState.Accepted::class.java)
            assertEquals(2, state.planned)
            assertEquals(1, state.completed)
            assertTrue(state.hasActiveSession)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleteWorkoutFromReview removes target workout and its exercises`() {
        val w1 = makeWorkout("w1", 1)
        val w2 = makeWorkout("w2", 3)
        val ex1 = makeExercise("ex1", "w1")
        val ex2 = makeExercise("ex2", "w2")
        setupReview(makeGeneratedPlan(listOf(w1, w2), listOf(ex1, ex2)))

        viewModel.deleteWorkoutFromReview("w1")

        val result = viewModel.generatedPlan.value!!
        assertEquals(listOf(w2), result.workouts)
        assertEquals(listOf(ex2), result.exercises)
    }

    @Test
    fun `deleteWorkoutFromReview on last workout retains empty review`() {
        val w1 = makeWorkout("w1", 1)
        setupReview(makeGeneratedPlan(listOf(w1), listOf(makeExercise("ex1", "w1"))))

        viewModel.deleteWorkoutFromReview("w1")

        assertNotNull(viewModel.generatedPlan.value)
        assertTrue(viewModel.generatedPlan.value!!.workouts.isEmpty())
        assertTrue(viewModel.generatedPlan.value!!.exercises.isEmpty())
    }

    @Test
    fun `deleteWorkoutFromReview with unknown id is a no-op`() {
        val generated = makeGeneratedPlan(listOf(makeWorkout("w1", 1)), emptyList())
        setupReview(generated)

        viewModel.deleteWorkoutFromReview("missing")

        assertEquals(generated, viewModel.generatedPlan.value)
    }

    @Test
    fun `backToSetup clears generated plan`() {
        setupReview(makeGeneratedPlan(listOf(makeWorkout("w1", 1)), emptyList()))

        viewModel.backToSetup()

        assertNull(viewModel.generatedPlan.value)
    }

    @Test
    fun `acceptPlan persists static review and resets setup`() = runTest {
        val generated =
            makeGeneratedPlan(
                workouts = listOf(makeWorkout("w1", 1)),
                exercises = listOf(makeExercise("ex1", "w1")),
            )
        setupReview(generated)
        coEvery { planRepository.createPlan(any(), any(), any()) } returns Unit
        var callbackCount = 0

        viewModel.acceptPlan { callbackCount += 1 }

        assertEquals(1, callbackCount)
        assertNull(viewModel.generatedPlan.value)
        assertTrue(viewModel.selectedDays.value.isEmpty())
        coVerify(exactly = 1) {
            planRepository.createPlan(generated.plan, generated.workouts, generated.exercises)
        }
    }

    @Test
    fun `acceptPlan ignores duplicate tap while create is in progress`() = runTest {
        val generated =
            makeGeneratedPlan(
                workouts = listOf(makeWorkout("w1", 1)),
                exercises = listOf(makeExercise("ex1", "w1")),
            )
        setupReview(generated)
        val createGate = CompletableDeferred<Unit>()
        coEvery { planRepository.createPlan(any(), any(), any()) } coAnswers { createGate.await() }
        var callbackCount = 0

        viewModel.acceptPlan { callbackCount += 1 }
        viewModel.acceptPlan { callbackCount += 1 }

        coVerify(exactly = 1) {
            planRepository.createPlan(generated.plan, generated.workouts, generated.exercises)
        }
        createGate.complete(Unit)
        assertEquals(1, callbackCount)
    }

    @Test
    fun `acceptPlan repository failure resets guard and retains review for retry`() = runTest {
        val generated =
            makeGeneratedPlan(
                workouts = listOf(makeWorkout("w1", 1)),
                exercises = listOf(makeExercise("ex1", "w1")),
            )
        setupReview(generated)
        coEvery { planRepository.createPlan(any(), any(), any()) } throws
            IllegalStateException("database unavailable")
        var callbackCount = 0

        viewModel.acceptPlan { callbackCount += 1 }

        assertEquals(0, callbackCount)
        assertEquals(generated, viewModel.generatedPlan.value)

        coEvery { planRepository.createPlan(any(), any(), any()) } returns Unit
        viewModel.acceptPlan { callbackCount += 1 }

        assertEquals(1, callbackCount)
        assertNull(viewModel.generatedPlan.value)
        coVerify(exactly = 2) {
            planRepository.createPlan(generated.plan, generated.workouts, generated.exercises)
        }
    }
}
