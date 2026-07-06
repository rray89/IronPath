package com.example.ironpath.ui.screens.home

import app.cash.turbine.test
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var planRepository: PlanRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var viewModel: HomeViewModel

    private val planFlow = MutableStateFlow<WeeklyPlan?>(null)
    private val workoutsFlow = MutableStateFlow<List<PlannedWorkout>>(emptyList())
    private val sessionFlow = MutableStateFlow<ActiveSession?>(null)

    private val activePlan =
        WeeklyPlan(id = "plan1", startDate = "2026-04-14", endDate = "2026-04-20")

    private val fakeSession =
        ActiveSession(
            id = "session1",
            sourcePlannedWorkoutId = "workout1",
            workoutTitle = "Push A",
        )

    private fun makeWorkout(
        id: String,
        dayOfWeek: Int,
        status: WorkoutStatus = WorkoutStatus.Upcoming,
        scheduledDate: String = "2026-04-${13 + dayOfWeek}",
    ) =
        PlannedWorkout(
            id = id,
            weeklyPlanId = "plan1",
            dayOfWeek = dayOfWeek,
            scheduledDate = scheduledDate,
            title = "Workout $id",
            status = status,
        )

    @Before
    fun setUp() {
        planRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)

        every { planRepository.observeActivePlan() } returns planFlow
        every { planRepository.observeWorkoutsForPlan(any()) } returns workoutsFlow
        every { sessionRepository.observeActiveSession() } returns sessionFlow

        viewModel = HomeViewModel(planRepository, sessionRepository)
    }

    // Helper: advance through flow emissions until we see the expected state type.
    private suspend fun <T : HomeUiState> app.cash.turbine.TurbineTestContext<HomeUiState>
        .awaitState(clazz: Class<T>): T {
        var item = awaitItem()
        while (!clazz.isInstance(item)) item = awaitItem()
        @Suppress("UNCHECKED_CAST") return item as T
    }

    @Test
    fun `uiState initial value is Loading`() {
        assertEquals(HomeUiState.Loading, viewModel.uiState.value)
    }

    @Test
    fun `uiState emits NoPlan when activePlan is null`() = runTest {
        // planFlow starts as null — the upstream will emit NoPlan once WhileSubscribed starts it.
        viewModel.uiState.test {
            awaitState(HomeUiState.NoPlan::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits ActivePlan when plan and workouts exist`() = runTest {
        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = listOf(makeWorkout("w1", 1))
            awaitState(HomeUiState.ActivePlan::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState ActivePlan has correct planned and completed counts`() = runTest {
        val workouts =
            listOf(
                makeWorkout("w1", 1, WorkoutStatus.Completed),
                makeWorkout("w2", 3, WorkoutStatus.Completed),
                makeWorkout("w3", 5, WorkoutStatus.Upcoming),
            )
        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = workouts
            // Drain until we get an ActivePlan with all 3 workouts loaded.
            var state = awaitState(HomeUiState.ActivePlan::class.java)
            while (state.planned != 3) state = awaitState(HomeUiState.ActivePlan::class.java)
            assertEquals(3, state.planned)
            assertEquals(2, state.completed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits WeekComplete when all workouts are Completed`() = runTest {
        val workouts =
            listOf(
                makeWorkout("w1", 1, WorkoutStatus.Completed),
                makeWorkout("w2", 3, WorkoutStatus.Completed),
            )
        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = workouts
            val state = awaitState(HomeUiState.WeekComplete::class.java)
            assertEquals(2, state.planned)
            assertEquals(2, state.completed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState hasActiveSession is true when session is non-null`() = runTest {
        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = listOf(makeWorkout("w1", 1))
            sessionFlow.value = fakeSession
            // Drain until we get ActivePlan with hasActiveSession = true.
            var state = awaitState(HomeUiState.ActivePlan::class.java)
            while (!state.hasActiveSession) state = awaitState(HomeUiState.ActivePlan::class.java)
            assertTrue(state.hasActiveSession)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState hasActiveSession is false when session is null`() = runTest {
        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = listOf(makeWorkout("w1", 1))
            // sessionFlow is already null from setUp
            val state = awaitState(HomeUiState.ActivePlan::class.java)
            assertFalse(state.hasActiveSession)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState todayWorkout is set when a workout is scheduled today`() = runTest {
        val today = LocalDate.now()
        val todayWorkout =
            makeWorkout(
                "today",
                today.dayOfWeek.value,
                WorkoutStatus.Upcoming,
                scheduledDate = today.toString(),
            )
        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = listOf(todayWorkout)
            // Drain until ActivePlan with the today workout populated.
            var state = awaitState(HomeUiState.ActivePlan::class.java)
            while (state.todayWorkout == null) state =
                awaitState(HomeUiState.ActivePlan::class.java)
            assertEquals(todayWorkout, state.todayWorkout)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState todayWorkout is null when no workout is scheduled today`() = runTest {
        val todayDow = LocalDate.now().dayOfWeek.value
        val otherDow = if (todayDow == 1) 2 else 1
        val otherWorkout = makeWorkout("other", otherDow, WorkoutStatus.Upcoming)
        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = listOf(otherWorkout)
            val state = awaitState(HomeUiState.ActivePlan::class.java)
            assertNull(state.todayWorkout)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState does not treat future same-weekday workout as today`() = runTest {
        val nextWeekSameDay = LocalDate.now().plusWeeks(1)
        val futureWorkout =
            makeWorkout(
                "future",
                nextWeekSameDay.dayOfWeek.value,
                WorkoutStatus.Upcoming,
                scheduledDate = nextWeekSameDay.toString(),
            )

        viewModel.uiState.test {
            planFlow.value = activePlan
            workoutsFlow.value = listOf(futureWorkout)
            var state = awaitState(HomeUiState.ActivePlan::class.java)
            while (state.nextWorkout == null) state = awaitState(HomeUiState.ActivePlan::class.java)
            assertNull(state.todayWorkout)
            assertEquals(futureWorkout, state.nextWorkout)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
