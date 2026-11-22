package com.example.ironpath.ui.screens.workoutpreview

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.session.StartPlannedWorkoutUseCase
import com.example.ironpath.domain.session.StartWorkoutResult
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutPreviewViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var planRepository: PlanRepository
    private lateinit var sessionRepository: SessionRepository
    private lateinit var startPlannedWorkout: StartPlannedWorkoutUseCase
    private val timeProvider = FakeTimeProvider()

    private val exercisesFlow = MutableStateFlow<List<PlannedExercise>>(emptyList())
    private val activeSessionFlow = MutableStateFlow<ActiveSession?>(null)

    private fun savedStateHandle(workoutId: String): SavedStateHandle =
        SavedStateHandle(mapOf(Route.WORKOUT_ID_ARG to workoutId))

    private fun workout(
        id: String = "workout1",
        scheduledDate: String = timeProvider.today().toString(),
        status: WorkoutStatus = WorkoutStatus.Upcoming,
    ) =
        PlannedWorkout(
            id = id,
            weeklyPlanId = "plan1",
            dayOfWeek =
                runCatching { LocalDate.parse(scheduledDate).dayOfWeek.value }.getOrDefault(1),
            scheduledDate = scheduledDate,
            title = "Push A",
            status = status,
        )

    private fun activeSession() =
        ActiveSession(
            id = "session1",
            sourcePlannedWorkoutId = "activeWorkout",
            workoutTitle = "Pull A",
            startedAt = 1_000L,
            lastUpdatedAt = 1_000L,
        )

    private fun exercise(id: String, orderIndex: Int) =
        PlannedExercise(
            id = id,
            plannedWorkoutId = "workout1",
            name = "Exercise $id",
            sets = 3,
            reps = 10,
            weightKg = 50.0,
            orderIndex = orderIndex,
        )

    @Before
    fun setUp() {
        planRepository = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        startPlannedWorkout = mockk(relaxed = true)
        coEvery { startPlannedWorkout(any(), any()) } returns StartWorkoutResult.Started("session1")

        every { planRepository.observeExercisesForWorkout("workout1") } returns exercisesFlow
        every { sessionRepository.observeActiveSession() } returns activeSessionFlow
        coEvery { startPlannedWorkout.invoke(any()) } returns StartWorkoutResult.Started("session1")
    }

    @Test
    fun `uiState loads workout with ordered exercises and allows starting when scheduled today`() =
        runTest {
            val todayWorkout = workout()
            val laterExercise = exercise("2", orderIndex = 1)
            val firstExercise = exercise("1", orderIndex = 0)
            coEvery { planRepository.getWorkoutById("workout1") } returns todayWorkout
            exercisesFlow.value = listOf(laterExercise, firstExercise)

            val viewModel =
                WorkoutPreviewViewModel(
                    savedStateHandle = savedStateHandle("workout1"),
                    planRepository = planRepository,
                    sessionRepository = sessionRepository,
                    startPlannedWorkout = startPlannedWorkout,
                    timeProvider = timeProvider,
                )

            viewModel.uiState.test {
                var state = awaitItem()
                while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
                assertEquals(todayWorkout, state.workout)
                assertEquals(listOf(firstExercise, laterExercise), state.exercises)
                assertTrue(state.canStart)
                assertFalse(state.hasActiveSession)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `uiState does not allow starting a future workout`() = runTest {
        val futureWorkout = workout(scheduledDate = timeProvider.today().plusDays(2).toString())
        coEvery { planRepository.getWorkoutById("workout1") } returns futureWorkout

        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = savedStateHandle("workout1"),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
            assertFalse(state.canStart)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState does not allow starting when an active session exists`() = runTest {
        val todayWorkout = workout()
        activeSessionFlow.value = activeSession()
        coEvery { planRepository.getWorkoutById("workout1") } returns todayWorkout

        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = savedStateHandle("workout1"),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
            assertFalse(state.canStart)
            assertTrue(state.hasActiveSession)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState does not allow starting completed workouts`() = runTest {
        coEvery { planRepository.getWorkoutById("workout1") } returns
            workout(status = WorkoutStatus.Completed)

        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = savedStateHandle("workout1"),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
            assertFalse(state.canStart)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState does not allow starting workouts with invalid scheduled dates`() = runTest {
        coEvery { planRepository.getWorkoutById("workout1") } returns
            workout(scheduledDate = "not-a-date")

        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = savedStateHandle("workout1"),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
            assertFalse(state.canStart)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startWorkout starts the session and invokes callback only when workout can start`() =
        runTest {
            val todayWorkout = workout()
            coEvery { planRepository.getWorkoutById("workout1") } returns todayWorkout
            val viewModel =
                WorkoutPreviewViewModel(
                    savedStateHandle = savedStateHandle("workout1"),
                    planRepository = planRepository,
                    sessionRepository = sessionRepository,
                    startPlannedWorkout = startPlannedWorkout,
                    timeProvider = timeProvider,
                )
            var callbackInvoked = false

            viewModel.uiState.test {
                var state = awaitItem()
                while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
                viewModel.startWorkout { callbackInvoked = true }
                advanceUntilIdle()

                coVerify(exactly = 1) { startPlannedWorkout(todayWorkout.id) }
                assertTrue(callbackInvoked)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `startWorkout does nothing when workout cannot start`() = runTest {
        coEvery { planRepository.getWorkoutById("workout1") } returns
            workout(scheduledDate = timeProvider.today().plusDays(1).toString())
        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = savedStateHandle("workout1"),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )
        var callbackInvoked = false

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
            viewModel.startWorkout { callbackInvoked = true }
            advanceUntilIdle()

            coVerify(exactly = 0) { startPlannedWorkout(any()) }
            assertFalse(callbackInvoked)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startWorkout ignores repeated taps while a start is already in progress`() = runTest {
        val todayWorkout = workout()
        val startCanComplete = CompletableDeferred<Unit>()
        coEvery { planRepository.getWorkoutById("workout1") } returns todayWorkout
        coEvery { startPlannedWorkout.invoke(any()) } coAnswers
            {
                startCanComplete.await()
                StartWorkoutResult.Started("session1")
            }
        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = savedStateHandle("workout1"),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutPreviewUiState.Ready) state = awaitItem()
            viewModel.startWorkout {}
            viewModel.startWorkout {}
            runCurrent()

            coVerify(exactly = 1) { startPlannedWorkout(todayWorkout.id) }
            assertTrue(viewModel.startState.value.isStarting)

            startCanComplete.complete(Unit)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `existing result offers continuation without reporting a newly started workout`() =
        runTest {
            coEvery { planRepository.getWorkoutById("workout1") } returns workout()
            coEvery { startPlannedWorkout(any(), any()) } returns
                StartWorkoutResult.ExistingSession("old")
            val vm =
                WorkoutPreviewViewModel(
                    savedStateHandle("workout1"),
                    planRepository,
                    sessionRepository,
                    startPlannedWorkout,
                    timeProvider
                )
            advanceUntilIdle()
            var navigations = 0
            vm.startWorkout { navigations++ }
            advanceUntilIdle()
            assertTrue(vm.startState.value.hasExistingSession)
            assertEquals(0, navigations)
            vm.startWorkout { navigations++ }
            assertEquals(1, navigations)
            coVerify(exactly = 1) { startPlannedWorkout(any(), any()) }
        }

    @Test
    fun `failure and stale source keep preview with actionable feedback and retry`() = runTest {
        coEvery { planRepository.getWorkoutById("workout1") } returns workout()
        val vm =
            WorkoutPreviewViewModel(
                savedStateHandle("workout1"),
                planRepository,
                sessionRepository,
                startPlannedWorkout,
                timeProvider
            )
        advanceUntilIdle()
        var navigations = 0
        coEvery { startPlannedWorkout(any(), any()) } throws IllegalStateException("write blocked")
        vm.startWorkout { navigations++ }
        advanceUntilIdle()
        assertTrue(vm.startState.value.error!!.contains("Could not start"))
        coEvery { startPlannedWorkout(any(), any()) } returns StartWorkoutResult.NotStartable
        vm.startWorkout { navigations++ }
        advanceUntilIdle()
        assertTrue(vm.startState.value.error!!.contains("no longer available"))
        assertEquals(0, navigations)
        coEvery { startPlannedWorkout(any(), any()) } returns StartWorkoutResult.Started("new")
        vm.startWorkout { navigations++ }
        advanceUntilIdle()
        assertEquals(1, navigations)
        assertEquals(null, vm.startState.value.error)
    }

    @Test
    fun `uiState emits NotFound when workout does not exist`() = runTest {
        coEvery { planRepository.getWorkoutById("missing") } returns null

        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = savedStateHandle("missing"),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )

        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is WorkoutPreviewUiState.NotFound) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun missingWorkoutRouteArgument_loadsNotFound() = runTest {
        coEvery { planRepository.getWorkoutById("") } returns null
        val viewModel =
            WorkoutPreviewViewModel(
                savedStateHandle = SavedStateHandle(),
                planRepository = planRepository,
                sessionRepository = sessionRepository,
                startPlannedWorkout = startPlannedWorkout,
                timeProvider = timeProvider,
            )

        advanceUntilIdle()

        assertEquals(WorkoutPreviewUiState.NotFound, viewModel.uiState.value)
    }
}
