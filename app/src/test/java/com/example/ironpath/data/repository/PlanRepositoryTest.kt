package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class PlanRepositoryTest {

    private lateinit var planDao: PlanDao
    private lateinit var database: IronPathDatabase
    private lateinit var backupChangeTracker: BackupChangeTracker
    private lateinit var repository: PlanRepository

    private val plan =
        WeeklyPlan(
            id = "plan1",
            startDate = "2026-04-14",
            endDate = "2026-04-20",
            createdAt = 1_000L,
        )

    private val workout =
        PlannedWorkout(
            id = "w1",
            weeklyPlanId = "plan1",
            dayOfWeek = 1,
            scheduledDate = "2026-04-14",
            title = "Push A",
        )

    private val exercise =
        PlannedExercise(
            id = "ex1",
            plannedWorkoutId = "w1",
            name = "Bench Press",
            sets = 3,
            reps = 10,
            weightKg = 60.0,
            orderIndex = 0,
        )

    @Before
    fun setUp() {
        planDao = mockk()
        database = mockk()
        backupChangeTracker = mockk()
        coEvery { planDao.createPlanWithWorkouts(any(), any(), any()) } returns Unit
        coEvery { planDao.updateWorkout(any()) } returns Unit
        coEvery { planDao.deleteWorkout(any()) } returns Unit
        coEvery { planDao.getAllExerciseNames() } returns emptyList()
        coEvery { backupChangeTracker.markIncludedDataChanged() } returns Unit
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers
            {
                secondArg<suspend () -> Unit>().invoke()
            }
        repository = PlanRepository(planDao, database, backupChangeTracker)
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `observeActivePlan returns flow from planDao`() {
        val expected = flowOf(plan)
        every { planDao.observeActivePlan() } returns expected

        val result = repository.observeActivePlan()

        assertSame(expected, result)
    }

    @Test
    fun `observeWorkoutsForPlan returns flow from planDao`() {
        val expected = flowOf(listOf(workout))
        every { planDao.observeWorkoutsForPlan("plan1") } returns expected

        val result = repository.observeWorkoutsForPlan("plan1")

        assertSame(expected, result)
    }

    @Test
    fun `createPlan delegates to planDao createPlanWithWorkouts`() = runTest {
        val workouts = listOf(workout)
        val exercises = listOf(exercise)

        repository.createPlan(plan, workouts, exercises)

        coVerify(exactly = 1) { planDao.createPlanWithWorkouts(plan, workouts, exercises) }
    }

    @Test
    fun `updateWorkout delegates to planDao`() = runTest {
        repository.updateWorkout(workout)
        coVerify(exactly = 1) { planDao.updateWorkout(workout) }
    }

    @Test
    fun `deleteWorkout delegates to planDao`() = runTest {
        repository.deleteWorkout("w1")
        coVerify(exactly = 1) { planDao.deleteWorkout("w1") }
    }

    @Test
    fun `getAllExerciseNames delegates to planDao`() = runTest {
        repository.getAllExerciseNames()
        coVerify(exactly = 1) { planDao.getAllExerciseNames() }
    }
}
