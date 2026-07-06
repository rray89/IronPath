package com.example.ironpath.ui.screens.active

import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ActiveViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private lateinit var sessionRepository: SessionRepository
    private lateinit var planRepository: PlanRepository
    private lateinit var viewModel: ActiveViewModel

    private val activeSessionFlow = MutableStateFlow<ActiveSession?>(null)
    private val activePlanFlow = MutableStateFlow<WeeklyPlan?>(null)
    private val workoutsFlow = MutableStateFlow<List<PlannedWorkout>>(emptyList())

    private val plan = WeeklyPlan(id = "plan1", startDate = "2026-04-14", endDate = "2026-04-20")

    private val session =
        ActiveSession(
            id = "session1",
            sourcePlannedWorkoutId = "workout1",
            workoutTitle = "Push A",
            startedAt = System.currentTimeMillis() - 60_000,
        )

    private fun makeWorkout(
        id: String = "workout1",
        dayOfWeek: Int = 1,
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

    private fun makePlannedExercise(id: String = "ex1", workoutId: String = "workout1") =
        PlannedExercise(
            id = id,
            plannedWorkoutId = workoutId,
            name = "Bench Press",
            sets = 3,
            reps = 10,
            weightKg = 60.0,
            orderIndex = 0,
        )

    private fun makeSessionExercise(id: String = "sex1", sets: Int = 3) =
        SessionExercise(
            id = id,
            activeSessionId = "session1",
            name = "Bench Press",
            plannedSets = sets,
            plannedReps = 10,
            plannedWeightKg = 60.0,
            orderIndex = 0,
        )

    private fun dayOfWeekFull(dow: Int): String =
        when (dow) {
            1 -> "Monday"
            2 -> "Tuesday"
            3 -> "Wednesday"
            4 -> "Thursday"
            5 -> "Friday"
            6 -> "Saturday"
            7 -> "Sunday"
            else -> "?"
        }

    @Before
    fun setUp() {
        sessionRepository = mockk(relaxed = true)
        planRepository = mockk(relaxed = true)

        every { sessionRepository.observeActiveSession() } returns activeSessionFlow
        every { sessionRepository.observeExercisesForSession(any()) } returns flowOf(emptyList())
        every { sessionRepository.observeSetsForExercises(any()) } returns flowOf(emptyList())
        every { planRepository.observeActivePlan() } returns activePlanFlow
        every { planRepository.observeWorkoutsForPlan(any()) } returns workoutsFlow
        coEvery { sessionRepository.getActiveSession() } returns null

        viewModel = ActiveViewModel(sessionRepository, planRepository)
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        clearAllMocks()
        unmockkAll()
    }

    /**
     * Runs the test block and then cancels the ViewModel's scope before runTest drains virtual
     * time. Required because ActiveViewModel has an infinite timer loop (delay + while(isActive));
     * without cancellation, runTest's post-block advanceUntilIdle() would spin it forever and OOM.
     */
    private fun runTestCancelling(block: suspend TestScope.() -> Unit) = runTest {
        block()
        viewModel.viewModelScope.cancel()
    }

    // -- uiState transitions --

    @Test
    fun `uiState emits NoPlan when no active plan and no session`() = runTestCancelling {
        viewModel.uiState.test {
            var state = awaitItem()
            while (state !is ActiveUiState.NoPlan) state = awaitItem()
            assertEquals(ActiveUiState.NoPlan, state)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits InSession when active session is present`() = runTestCancelling {
        viewModel.uiState.test {
            awaitItem() // Loading
            activeSessionFlow.value = session
            var state = awaitItem()
            while (state !is ActiveUiState.InSession) state = awaitItem()
            assertEquals(session, (state as ActiveUiState.InSession).session)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits ReadyToStart when plan has today workout and no active session`() =
        runTestCancelling {
            val today = LocalDate.now()
            val todayWorkout =
                makeWorkout(
                    dayOfWeek = today.dayOfWeek.value,
                    status = WorkoutStatus.Upcoming,
                    scheduledDate = today.toString(),
                )

            viewModel.uiState.test {
                awaitItem() // Loading
                activePlanFlow.value = plan
                workoutsFlow.value = listOf(todayWorkout)
                var state = awaitItem()
                while (state !is ActiveUiState.ReadyToStart) state = awaitItem()
                assertEquals(todayWorkout, (state as ActiveUiState.ReadyToStart).workout)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `uiState emits RestDay when same-weekday workout is scheduled in the future`() =
        runTestCancelling {
            val nextWeekSameDay = LocalDate.now().plusWeeks(1)
            val futureWorkout =
                makeWorkout(
                    dayOfWeek = nextWeekSameDay.dayOfWeek.value,
                    status = WorkoutStatus.Upcoming,
                    scheduledDate = nextWeekSameDay.toString(),
                )

            viewModel.uiState.test {
                awaitItem() // Loading
                activePlanFlow.value = plan
                workoutsFlow.value = listOf(futureWorkout)
                var state = awaitItem()
                while (state !is ActiveUiState.RestDay || state.nextWorkoutDay == null) {
                    state = awaitItem()
                }
                assertEquals(
                    dayOfWeekFull(nextWeekSameDay.dayOfWeek.value),
                    state.nextWorkoutDay,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `uiState emits RestDay when plan exists but no workout for today`() = runTestCancelling {
        val todayDow = LocalDate.now().dayOfWeek.value
        val otherDow = if (todayDow == 1) 2 else 1
        val otherWorkout = makeWorkout(dayOfWeek = otherDow, status = WorkoutStatus.Upcoming)

        viewModel.uiState.test {
            awaitItem() // Loading
            activePlanFlow.value = plan
            workoutsFlow.value = listOf(otherWorkout)
            var state = awaitItem()
            while (state !is ActiveUiState.RestDay) state = awaitItem()
            assertTrue(state is ActiveUiState.RestDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // -- startSession --

    @Test
    fun `startSession calls sessionRepository startSession with correct session and exercises`() =
        runTestCancelling {
            val workout = makeWorkout()
            coEvery { planRepository.getExercisesForWorkout("workout1") } returns
                listOf(makePlannedExercise())
            coEvery { sessionRepository.getExercisesForSession(any()) } returns emptyList()

            viewModel.startSession(workout)

            coVerify {
                sessionRepository.startSession(
                    match {
                        it.sourcePlannedWorkoutId == "workout1" &&
                            it.workoutTitle == "Workout workout1"
                    },
                    match { exercises ->
                        exercises.size == 1 &&
                            exercises[0].name == "Bench Press" &&
                            exercises[0].plannedSets == 3
                    },
                )
            }
        }

    @Test
    fun `startSession pre-populates one SessionSet per planned set`() = runTestCancelling {
        val workout = makeWorkout()
        val sessionExercise = makeSessionExercise(sets = 3)

        coEvery { planRepository.getExercisesForWorkout("workout1") } returns
            listOf(makePlannedExercise())
        coEvery { sessionRepository.getExercisesForSession(any()) } returns listOf(sessionExercise)

        viewModel.startSession(workout)

        coVerify(exactly = 3) { sessionRepository.insertSet(any()) }
    }

    // -- updateSet --

    @Test
    fun `updateSet delegates to sessionRepository`() = runTestCancelling {
        val set = SessionSet(sessionExerciseId = "sex1", setNumber = 1, reps = 10, weightKg = 60.0)

        viewModel.updateSet(set)

        coVerify(exactly = 1) { sessionRepository.updateSet(set) }
    }

    // -- addExtraSet --

    @Test
    fun `addExtraSet inserts SessionSet with isExtra true and incremented setNumber`() =
        runTestCancelling {
            val capturedSet = slot<SessionSet>()
            coEvery { sessionRepository.insertSet(capture(capturedSet)) } returns Unit

            viewModel.addExtraSet("sex1", currentSetCount = 3)

            assertTrue(capturedSet.captured.isExtra)
            assertEquals(4, capturedSet.captured.setNumber)
            assertEquals("sex1", capturedSet.captured.sessionExerciseId)
        }

    // -- finishWorkout --

    @Test
    fun `finishWorkout calls completeSession with log containing correct title and duration`() =
        runTestCancelling {
            val startedAt = System.currentTimeMillis() - 120_000 // 2 minutes ago
            val activeSession = session.copy(startedAt = startedAt)

            coEvery { sessionRepository.getActiveSession() } returns activeSession
            coEvery { sessionRepository.getExercisesForSession(any()) } returns
                listOf(makeSessionExercise())
            coEvery { sessionRepository.countCompletedSets(any()) } returns 3

            var callbackInvoked = false
            viewModel.finishWorkout { callbackInvoked = true }

            coVerify {
                sessionRepository.completeSession(
                    activeSession.id,
                    match { log ->
                        log.title == "Push A" && log.durationMinutes >= 1 && log.exerciseCount == 1
                    },
                )
            }
            assertTrue(callbackInvoked)
        }

    @Test
    fun `finishWorkout marks planned workout Completed when completedSets greater than 0`() =
        runTestCancelling {
            val workout = makeWorkout()
            coEvery { sessionRepository.getActiveSession() } returns session
            coEvery { sessionRepository.getExercisesForSession(any()) } returns
                listOf(makeSessionExercise())
            coEvery { sessionRepository.countCompletedSets(any()) } returns 2
            coEvery { planRepository.getWorkoutById("workout1") } returns workout

            viewModel.finishWorkout {}

            coVerify {
                planRepository.updateWorkout(match { it.status == WorkoutStatus.Completed })
            }
        }

    @Test
    fun `finishWorkout does NOT update workout when completedSets is 0`() = runTestCancelling {
        coEvery { sessionRepository.getActiveSession() } returns session
        coEvery { sessionRepository.getExercisesForSession(any()) } returns
            listOf(makeSessionExercise())
        coEvery { sessionRepository.countCompletedSets(any()) } returns 0

        viewModel.finishWorkout {}

        coVerify(exactly = 0) { planRepository.updateWorkout(any()) }
    }

    @Test
    fun `finishWorkout resets elapsedSeconds to 0`() = runTestCancelling {
        coEvery { sessionRepository.getActiveSession() } returns session
        coEvery { sessionRepository.getExercisesForSession(any()) } returns emptyList()
        coEvery { sessionRepository.countCompletedSets(any()) } returns 0

        viewModel.finishWorkout {}

        assertEquals(0L, viewModel.elapsedSeconds.value)
    }

    @Test
    fun `finishWorkout does nothing when no active session`() = runTestCancelling {
        coEvery { sessionRepository.getActiveSession() } returns null

        viewModel.finishWorkout {}

        coVerify(exactly = 0) { sessionRepository.completeSession(any(), any()) }
    }
}
