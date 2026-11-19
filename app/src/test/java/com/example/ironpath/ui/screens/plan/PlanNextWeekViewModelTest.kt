package com.example.ironpath.ui.screens.plan

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.example.ironpath.data.local.entity.*
import com.example.ironpath.data.repository.ActiveSessionBlocksPlanException
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.*
import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlanNextWeekViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val clock = FakeTimeProvider(Instant.parse("2026-12-31T19:00:00Z"))
    private val plans = mockk<PlanRepository>(relaxed = true)
    private val sessions = mockk<SessionRepository>(relaxed = true)
    private val old =
        WeeklyPlan("old", startDate = "2026-12-28", endDate = "2027-01-03", createdAt = 1)
    private val activePlan = MutableStateFlow<WeeklyPlan?>(old)
    private val workouts =
        MutableStateFlow(
            listOf(PlannedWorkout("last", "old", 4, "2026-12-31", "Last", WorkoutStatus.Completed))
        )
    private val session = MutableStateFlow<ActiveSession?>(null)
    private val catalog = DefaultExerciseCatalog()

    private fun vm(handle: SavedStateHandle = SavedStateHandle()): PlanViewModel {
        every { plans.observeActivePlan() } returns activePlan
        every { plans.observeWorkoutsForPlan(any()) } returns workouts
        every { sessions.observeActiveSession() } returns session
        return PlanViewModel(
            plans,
            PlanGenerator(
                clock,
                RuleBasedPlanFactory(catalog),
                PlanEntityMapper(FakeIdProvider(), clock, catalog)
            ),
            sessions,
            clock,
            mockk(relaxed = true),
            mockk(relaxed = true),
            rulePlanReviewEditor =
                com.example.ironpath.domain.planner.RulePlanReviewEditor(
                    com.example.ironpath.testutil.FakeIdProvider()
                ),
            recordRepository = mockk(relaxed = true),
            exerciseCatalog = com.example.ironpath.domain.planner.DefaultExerciseCatalog(),
            savedStateHandle = handle
        )
    }

    @Test
    fun `setup intent survives fresh view model and cancel never replaces old week`() = runTest {
        val handle = SavedStateHandle()
        val first = vm(handle)
        first.planUiState.test {
            mainDispatcherRule.testDispatcher.scheduler.runCurrent()
            assertTrue(first.beginNextWeekPlanning())
            mainDispatcherRule.testDispatcher.scheduler.runCurrent()
            assertEquals(PlanUiState.Setup, first.planUiState.value)
            first.generatePlan(PlanningGoal.STRENGTH, setOf(1))
            assertEquals("2027-01-04", first.generatedPlan.value!!.plan.startDate)
            assertEquals("2027-01-10", first.generatedPlan.value!!.plan.endDate)
            cancelAndIgnoreRemainingEvents()
        }
        val restored = vm(handle)
        restored.planUiState.test {
            mainDispatcherRule.testDispatcher.scheduler.runCurrent()
            assertEquals(PlanUiState.Setup, restored.planUiState.value)
            assertNull(restored.generatedPlan.value)
            assertTrue(restored.cancelPlanning())
            mainDispatcherRule.testDispatcher.scheduler.runCurrent()
            assertTrue(restored.planUiState.value is PlanUiState.Accepted)
            coVerify(exactly = 0) { plans.createPlan(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `old setup intent cannot hide a different accepted plan after restore`() = runTest {
        val handle = SavedStateHandle(mapOf("next_week_setup_source" to "old"))
        activePlan.value = old.copy(id = "new", startDate = "2027-01-04", endDate = "2027-01-10")
        workouts.value =
            listOf(
                workouts.value
                    .single()
                    .copy(
                        id = "new-workout",
                        weeklyPlanId = "new",
                        scheduledDate = "2027-01-04",
                        status = WorkoutStatus.Upcoming
                    )
            )
        val restored = vm(handle)
        restored.planUiState.test {
            mainDispatcherRule.testDispatcher.scheduler.runCurrent()
            assertTrue(restored.planUiState.value is PlanUiState.Accepted)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `active training blocks setup and failed acceptance preserves draft for retry`() = runTest {
        val model = vm()
        session.value = ActiveSession("active", "last", "Last", 1, 1)
        mainDispatcherRule.testDispatcher.scheduler.runCurrent()
        assertTrue(model.beginNextWeekPlanning())
        assertTrue(model.saveState.value.error!!.contains("Finish the active workout"))
        session.value = null
        mainDispatcherRule.testDispatcher.scheduler.runCurrent()
        model.beginNextWeekPlanning()
        model.generatePlan(PlanningGoal.STRENGTH, setOf(1))
        val draft = model.generatedPlan.value
        coEvery { plans.createPlan(any(), any(), any(), any()) } throws
            ActiveSessionBlocksPlanException()
        var accepted = 0
        model.acceptPlan { accepted++ }
        assertEquals(draft, model.generatedPlan.value)
        assertTrue(model.saveState.value.error!!.contains("Finish the active workout"))
        assertEquals(0, accepted)
        coEvery { plans.createPlan(any(), any(), any(), any()) } returns Unit
        model.acceptPlan { accepted++ }
        assertEquals(1, accepted)
        assertNull(model.generatedPlan.value)
        assertFalse(model.saveState.value.isSaving)
    }

    @Test
    fun `busy acceptance rejects repeat cancel and draft edits`() = runTest {
        val model = vm()
        model.beginNextWeekPlanning()
        model.generatePlan(PlanningGoal.STRENGTH, setOf(1))
        val draft = model.generatedPlan.value!!
        val gate = CompletableDeferred<Unit>()
        coEvery { plans.createPlan(any(), any(), any(), any()) } coAnswers { gate.await() }
        model.acceptPlan {}
        assertTrue(model.saveState.value.isSaving)
        model.acceptPlan {}
        model.backToSetup()
        model.deleteWorkoutFromReview(draft.workouts.single().id)
        model.generatePlan(PlanningGoal.GENERAL_FITNESS, setOf(2))
        assertFalse(model.cancelPlanning())
        assertEquals(draft, model.generatedPlan.value)
        coVerify(exactly = 1) { plans.createPlan(any(), any(), any(), any()) }
        gate.complete(Unit)
    }

    @Test
    fun `target follows future completed week and advances with current clock`() = runTest {
        activePlan.value = old.copy(endDate = "2027-01-10")
        val model = vm()
        assertEquals(LocalDate.parse("2027-01-11"), model.targetWeekStart())
        clock.advanceBy(java.time.Duration.ofDays(18))
        assertEquals(LocalDate.parse("2027-01-25"), model.targetWeekStart())
    }
}
