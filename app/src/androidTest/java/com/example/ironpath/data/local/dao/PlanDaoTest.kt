package com.example.ironpath.data.local.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.PlanStatus
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanDaoTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    private val dao: PlanDao
        get() = databaseRule.database.planDao()

    @Test
    fun createPlanWithWorkouts_archivesPreviousActivePlan_andPersistsCompleteGraph() = runBlocking {
        val previousPlan = TestData.plan(id = "plan-previous")
        val previousWorkout = TestData.workout(id = "workout-previous", planId = previousPlan.id)
        val previousExercise =
            TestData.plannedExercise(
                id = "exercise-previous",
                workoutId = previousWorkout.id,
            )
        dao.createPlanWithWorkouts(
            previousPlan,
            listOf(previousWorkout),
            listOf(previousExercise),
        )

        val newPlan =
            TestData.plan(
                id = "plan-new",
                startDate = "2026-07-20",
                endDate = "2026-07-26",
                createdAt = TestData.BASE_TIME + 1,
            )
        val mondayWorkout =
            TestData.workout(
                id = "workout-monday",
                planId = newPlan.id,
                dayOfWeek = 1,
                scheduledDate = "2026-07-20",
            )
        val fridayWorkout =
            TestData.workout(
                id = "workout-friday",
                planId = newPlan.id,
                dayOfWeek = 5,
                scheduledDate = "2026-07-24",
                title = "Strength B",
            )
        val squat =
            TestData.plannedExercise(
                id = "exercise-squat",
                workoutId = mondayWorkout.id,
                orderIndex = 0,
            )
        val bench =
            TestData.plannedExercise(
                id = "exercise-bench",
                workoutId = fridayWorkout.id,
                name = "Bench Press",
                orderIndex = 0,
            )

        dao.createPlanWithWorkouts(
            newPlan,
            listOf(fridayWorkout, mondayWorkout),
            listOf(bench, squat),
        )

        assertEquals(newPlan, dao.getActivePlan())
        assertEquals(1, activePlanCount())
        assertEquals("Archived", planStatus(previousPlan.id))
        assertEquals(listOf(mondayWorkout, fridayWorkout), dao.getWorkoutsForPlan(newPlan.id))
        assertEquals(listOf(squat), dao.getExercisesForWorkout(mondayWorkout.id))
        assertEquals(listOf(bench), dao.getExercisesForWorkout(fridayWorkout.id))
        assertEquals(previousWorkout, dao.getWorkoutById(previousWorkout.id))
        assertEquals(listOf(previousExercise), dao.getExercisesForWorkout(previousWorkout.id))
    }

    @Test
    fun workoutsAndExercises_areReturnedInProductOrder() = runBlocking {
        val plan = TestData.plan()
        val monday =
            TestData.workout(
                id = "workout-monday",
                dayOfWeek = 1,
                scheduledDate = "2026-07-13",
            )
        val wednesday =
            TestData.workout(
                id = "workout-wednesday",
                dayOfWeek = 3,
                scheduledDate = "2026-07-15",
                title = "Strength B",
            )
        val friday =
            TestData.workout(
                id = "workout-friday",
                dayOfWeek = 5,
                scheduledDate = "2026-07-17",
                title = "Strength C",
            )
        val first =
            TestData.plannedExercise(
                id = "exercise-first",
                workoutId = monday.id,
                name = "Squat",
                orderIndex = 0,
            )
        val second =
            TestData.plannedExercise(
                id = "exercise-second",
                workoutId = monday.id,
                name = "Bench Press",
                orderIndex = 1,
            )
        val third =
            TestData.plannedExercise(
                id = "exercise-third",
                workoutId = monday.id,
                name = "Deadlift",
                orderIndex = 2,
            )

        dao.createPlanWithWorkouts(
            plan,
            listOf(friday, monday, wednesday),
            listOf(third, first, second),
        )

        assertEquals(
            listOf("workout-monday", "workout-wednesday", "workout-friday"),
            dao.getWorkoutsForPlan(plan.id).map { it.id },
        )
        assertEquals(
            listOf("workout-monday", "workout-wednesday", "workout-friday"),
            dao.observeWorkoutsForPlan(plan.id).first().map { it.id },
        )
        assertEquals(
            listOf("exercise-first", "exercise-second", "exercise-third"),
            dao.getExercisesForWorkout(monday.id).map { it.id },
        )
        assertEquals(
            listOf("exercise-first", "exercise-second", "exercise-third"),
            dao.observeExercisesForWorkout(monday.id).first().map { it.id },
        )
    }

    @Test
    fun deletingWorkout_cascadesItsExercisesOnly() = runBlocking {
        val plan = TestData.plan()
        val deletedWorkout = TestData.workout(id = "workout-deleted")
        val retainedWorkout =
            TestData.workout(
                id = "workout-retained",
                dayOfWeek = 3,
                scheduledDate = "2026-07-15",
                title = "Strength B",
            )
        val deletedExercise =
            TestData.plannedExercise(
                id = "exercise-deleted",
                workoutId = deletedWorkout.id,
            )
        val retainedExercise =
            TestData.plannedExercise(
                id = "exercise-retained",
                workoutId = retainedWorkout.id,
                name = "Bench Press",
            )
        dao.createPlanWithWorkouts(
            plan,
            listOf(deletedWorkout, retainedWorkout),
            listOf(deletedExercise, retainedExercise),
        )

        dao.deleteWorkout(deletedWorkout.id)

        assertNull(dao.getWorkoutById(deletedWorkout.id))
        assertTrue(dao.getExercisesForWorkout(deletedWorkout.id).isEmpty())
        assertEquals(retainedWorkout, dao.getWorkoutById(retainedWorkout.id))
        assertEquals(listOf(retainedExercise), dao.getExercisesForWorkout(retainedWorkout.id))
        assertEquals(plan, dao.getActivePlan())
    }

    @Test
    fun activePlanFlow_updatesAfterReplacement() = runBlocking {
        val previousPlan = TestData.plan(id = "plan-previous")
        dao.insertPlan(previousPlan)

        val firstEmissionObserved = CompletableDeferred<Unit>()
        val emissions = mutableListOf<WeeklyPlan>()
        val collection = launch {
            dao.observeActivePlan().filterNotNull().take(2).collect { plan ->
                emissions += plan
                if (emissions.size == 1) firstEmissionObserved.complete(Unit)
            }
        }
        withTimeout(5_000) { firstEmissionObserved.await() }

        val replacement =
            TestData.plan(
                id = "plan-replacement",
                startDate = "2026-07-20",
                endDate = "2026-07-26",
                createdAt = TestData.BASE_TIME + 1,
            )
        val replacementWorkout =
            TestData.workout(
                id = "workout-replacement",
                planId = replacement.id,
                scheduledDate = "2026-07-20",
            )
        val replacementExercise =
            TestData.plannedExercise(
                id = "exercise-replacement",
                workoutId = replacementWorkout.id,
            )
        dao.createPlanWithWorkouts(
            replacement,
            listOf(replacementWorkout),
            listOf(replacementExercise),
        )

        withTimeout(5_000) { collection.join() }
        assertEquals(listOf(previousPlan.id, replacement.id), emissions.map { it.id })
    }

    @Test
    fun duplicatePrimaryKey_rollsBackWholePlanTransaction() = runBlocking {
        val previousPlan = TestData.plan(id = "plan-previous")
        val previousWorkout = TestData.workout(id = "workout-previous", planId = previousPlan.id)
        val previousExercise =
            TestData.plannedExercise(
                id = "exercise-collision",
                workoutId = previousWorkout.id,
            )
        dao.createPlanWithWorkouts(
            previousPlan,
            listOf(previousWorkout),
            listOf(previousExercise),
        )

        val rejectedPlan =
            TestData.plan(
                id = "plan-rejected",
                startDate = "2026-07-20",
                endDate = "2026-07-26",
                createdAt = TestData.BASE_TIME + 1,
            )
        val rejectedWorkout =
            TestData.workout(
                id = "workout-rejected",
                planId = rejectedPlan.id,
                scheduledDate = "2026-07-20",
            )
        var failure: Throwable? = null

        try {
            dao.createPlanWithWorkouts(
                rejectedPlan,
                listOf(rejectedWorkout),
                listOf(
                    TestData.plannedExercise(
                        id = previousExercise.id,
                        workoutId = rejectedWorkout.id,
                    )
                ),
            )
        } catch (throwable: Throwable) {
            failure = throwable
        }

        assertNotNull("A duplicate primary key must reject the transaction", failure)
        assertEquals(previousPlan, dao.getActivePlan())
        assertEquals("Active", planStatus(previousPlan.id))
        assertEquals(0, planRowCount(rejectedPlan.id))
        assertNull(dao.getWorkoutById(rejectedWorkout.id))
        assertEquals(previousWorkout, dao.getWorkoutById(previousWorkout.id))
        assertEquals(listOf(previousExercise), dao.getExercisesForWorkout(previousWorkout.id))
    }

    @Test
    fun markWorkoutCompleted_updatesOnlyTargetWorkout() = runBlocking {
        val plan = TestData.plan()
        val target = TestData.workout(id = "workout-target")
        val other =
            TestData.workout(
                id = "workout-other",
                dayOfWeek = 3,
                scheduledDate = "2026-07-15",
                title = "Strength B",
            )
        dao.createPlanWithWorkouts(
            plan,
            listOf(target, other),
            listOf(
                TestData.plannedExercise(id = "exercise-target", workoutId = target.id),
                TestData.plannedExercise(id = "exercise-other", workoutId = other.id),
            ),
        )

        dao.markWorkoutCompleted(target.id)

        assertEquals(WorkoutStatus.Completed, dao.getWorkoutById(target.id)?.status)
        assertEquals(WorkoutStatus.Upcoming, dao.getWorkoutById(other.id)?.status)
        assertEquals(PlanStatus.Active, dao.getActivePlan()?.status)
    }

    private fun planStatus(planId: String): String =
        databaseRule.database.openHelper.readableDatabase
            .query("SELECT status FROM weekly_plans WHERE id = ?", arrayOf(planId))
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0)
            }

    private fun planRowCount(planId: String): Int =
        databaseRule.database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM weekly_plans WHERE id = ?", arrayOf(planId))
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }

    private fun activePlanCount(): Int =
        databaseRule.database.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM weekly_plans WHERE status = 'Active'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getInt(0)
            }
}
