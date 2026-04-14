package com.example.ironpath.ui.screens.plan

import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.planner.GeneratedPlan
import com.example.ironpath.domain.planner.PlanGenerator
import com.example.ironpath.domain.planner.TrainingGoal
import com.example.ironpath.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
    private lateinit var recordRepository: RecordRepository
    private lateinit var viewModel: PlanViewModel

    // -- Builder helpers --

    private fun makeWorkout(id: String, dayOfWeek: Int, planId: String = "plan1") =
        PlannedWorkout(
            id = id,
            weeklyPlanId = planId,
            dayOfWeek = dayOfWeek,
            scheduledDate = "2026-04-${13 + dayOfWeek}", // Mon 4/14 = dayOfWeek 1
            title = "Workout $id",
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
        val plan = WeeklyPlan(id = "plan1", startDate = "2026-04-14", endDate = "2026-04-20")
        return GeneratedPlan(plan = plan, workouts = workouts, exercises = exercises)
    }

    /** Drives the ViewModel into Review state with the given plan. */
    private fun setupReview(plan: GeneratedPlan) {
        every { planGenerator.generate(any(), any()) } returns plan
        viewModel.toggleDay(1) // ensure selectedDays non-empty
        viewModel.generatePlan()
    }

    @Before
    fun setUp() {
        planRepository = mockk(relaxed = true)
        planGenerator = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        recordRepository = mockk(relaxed = true)

        every { planRepository.observeActivePlan() } returns flowOf(null)
        every { planRepository.observeWorkoutsForPlan(any()) } returns flowOf(emptyList())
        every { sessionRepository.observeActiveSession() } returns flowOf(null)
        coEvery { recordRepository.getAllRecordExerciseNames() } returns emptyList()
        coEvery { planRepository.getAllExerciseNames() } returns emptyList()

        viewModel =
            PlanViewModel(planRepository, planGenerator, sessionRepository, recordRepository)
    }

    // -- generatePlan --

    @Test
    fun `generatePlan sets generatedPlan returned by PlanGenerator`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1")
        val expected = makeGeneratedPlan(listOf(w1), listOf(ex1))
        every { planGenerator.generate(TrainingGoal.Strength, setOf(1)) } returns expected

        viewModel.toggleDay(1)
        viewModel.generatePlan()

        assertEquals(expected, viewModel.generatedPlan.value)
    }

    @Test
    fun `generatePlan does nothing when no days selected`() = runTest {
        viewModel.generatePlan()
        assertNull(viewModel.generatedPlan.value)
    }

    // -- deleteWorkoutFromReview --

    @Test
    fun `deleteWorkoutFromReview removes target workout and its exercises`() = runTest {
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
    fun `deleteWorkoutFromReview on last workout keeps generatedPlan non-null with empty list`() =
        runTest {
            val w1 = makeWorkout("w1", 1)
            val ex1 = makeExercise("ex1", "w1")
            setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1)))

            viewModel.deleteWorkoutFromReview("w1")

            assertNotNull(viewModel.generatedPlan.value)
            assertTrue(viewModel.generatedPlan.value!!.workouts.isEmpty())
        }

    // -- reassignWorkoutDay --

    @Test
    fun `reassignWorkoutDay to empty slot updates dayOfWeek and re-sorts`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val w2 = makeWorkout("w2", 3)
        setupReview(makeGeneratedPlan(listOf(w1, w2), emptyList()))

        viewModel.reassignWorkoutDay("w1", 5) // move Monday workout to Friday

        val workouts = viewModel.generatedPlan.value!!.workouts
        assertEquals(5, workouts.find { it.id == "w1" }!!.dayOfWeek)
        assertEquals(listOf(3, 5), workouts.map { it.dayOfWeek }) // sorted
    }

    @Test
    fun `reassignWorkoutDay to occupied slot swaps both workouts`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val w2 = makeWorkout("w2", 3)
        setupReview(makeGeneratedPlan(listOf(w1, w2), emptyList()))

        viewModel.reassignWorkoutDay("w1", 3) // move w1 to w2's slot

        val workouts = viewModel.generatedPlan.value!!.workouts
        assertEquals(3, workouts.find { it.id == "w1" }!!.dayOfWeek)
        assertEquals(1, workouts.find { it.id == "w2" }!!.dayOfWeek)
    }

    // -- removeExerciseFromReview --

    @Test
    fun `removeExerciseFromReview removes exercise and records it in undoExercise`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1", orderIndex = 0)
        val ex2 = makeExercise("ex2", "w1", orderIndex = 1)
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1, ex2)))

        viewModel.removeExerciseFromReview("ex1")

        val plan = viewModel.generatedPlan.value!!
        assertTrue(plan.exercises.none { it.id == "ex1" })
        assertEquals(ex1, viewModel.undoExercise.value?.first)
        assertNull(viewModel.undoExercise.value?.second) // workout not removed
    }

    @Test
    fun `removeExerciseFromReview of last exercise also removes workout`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1")
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1)))

        viewModel.removeExerciseFromReview("ex1")

        val plan = viewModel.generatedPlan.value!!
        assertTrue(plan.workouts.none { it.id == "w1" })
        assertEquals(ex1, viewModel.undoExercise.value?.first)
        assertEquals(w1, viewModel.undoExercise.value?.second) // workout captured in undo
    }

    // -- undoRemoveExercise --

    @Test
    fun `undoRemoveExercise restores exercise and clears undo state`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1", 0)
        val ex2 = makeExercise("ex2", "w1", 1)
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1, ex2)))
        viewModel.removeExerciseFromReview("ex1")

        viewModel.undoRemoveExercise()

        val plan = viewModel.generatedPlan.value!!
        assertTrue(plan.exercises.any { it.id == "ex1" })
        assertNull(viewModel.undoExercise.value)
    }

    @Test
    fun `undoRemoveExercise also restores removed workout`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1")
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1)))
        viewModel.removeExerciseFromReview("ex1")

        viewModel.undoRemoveExercise()

        val plan = viewModel.generatedPlan.value!!
        assertTrue(plan.workouts.any { it.id == "w1" })
        assertTrue(plan.exercises.any { it.id == "ex1" })
        assertNull(viewModel.undoExercise.value)
    }

    // -- addExerciseToReview --

    @Test
    fun `addExerciseToReview appends exercise with orderIndex max plus 1`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1", orderIndex = 0)
        val ex2 = makeExercise("ex2", "w1", orderIndex = 1)
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1, ex2)))

        viewModel.addExerciseToReview("w1", makeExercise("new", "w1"))

        val exercises = viewModel.generatedPlan.value!!.exercises
        assertEquals(3, exercises.size)
        assertEquals(2, exercises.last().orderIndex) // max(0, 1) + 1 = 2
    }

    @Test
    fun `addExerciseToReview to empty workout starts at orderIndex 0`() = runTest {
        val w1 = makeWorkout("w1", 1)
        setupReview(makeGeneratedPlan(listOf(w1), emptyList()))

        viewModel.addExerciseToReview("w1", makeExercise("new", "w1"))

        val exercises = viewModel.generatedPlan.value!!.exercises
        assertEquals(1, exercises.size)
        assertEquals(0, exercises.first().orderIndex)
    }

    // -- moveExerciseInReview --

    @Test
    fun `moveExerciseInReview up swaps orderIndex with predecessor`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1", orderIndex = 0)
        val ex2 = makeExercise("ex2", "w1", orderIndex = 1)
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1, ex2)))

        viewModel.moveExerciseInReview("ex2", -1) // move ex2 up

        val exercises = viewModel.generatedPlan.value!!.exercises
        assertEquals(0, exercises.find { it.id == "ex2" }!!.orderIndex)
        assertEquals(1, exercises.find { it.id == "ex1" }!!.orderIndex)
    }

    @Test
    fun `moveExerciseInReview down swaps orderIndex with successor`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1", orderIndex = 0)
        val ex2 = makeExercise("ex2", "w1", orderIndex = 1)
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1, ex2)))

        viewModel.moveExerciseInReview("ex1", +1) // move ex1 down

        val exercises = viewModel.generatedPlan.value!!.exercises
        assertEquals(1, exercises.find { it.id == "ex1" }!!.orderIndex)
        assertEquals(0, exercises.find { it.id == "ex2" }!!.orderIndex)
    }

    @Test
    fun `moveExerciseInReview at boundary does nothing`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1", orderIndex = 0)
        setupReview(makeGeneratedPlan(listOf(w1), listOf(ex1)))

        viewModel.moveExerciseInReview("ex1", -1) // already at top

        assertEquals(0, viewModel.generatedPlan.value!!.exercises.first().orderIndex)
    }

    // -- acceptPlan --

    @Test
    fun `acceptPlan calls createPlan and clears generatedPlan`() = runTest {
        val w1 = makeWorkout("w1", 1)
        val ex1 = makeExercise("ex1", "w1")
        val plan = makeGeneratedPlan(listOf(w1), listOf(ex1))
        setupReview(plan)
        coEvery { planRepository.createPlan(any(), any(), any()) } returns Unit

        var callbackInvoked = false
        viewModel.acceptPlan { callbackInvoked = true }

        assertTrue(callbackInvoked)
        assertNull(viewModel.generatedPlan.value)
        coVerify(exactly = 1) {
            planRepository.createPlan(plan.plan, plan.workouts, plan.exercises)
        }
    }
}
