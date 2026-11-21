package com.example.ironpath.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.TestData
import com.example.ironpath.testutil.TestDatabaseRegistry
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class NextWeekJourneyTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var database: IronPathDatabase
    @Inject lateinit var timeProvider: MutableTimeProvider

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun lastWorkout_homeAndPlanSetup_cancelAcceptAndRecreate_preserveHistoryAcrossYearBoundary() {
        waitForTag(TestTags.ENTRY_GET_STARTED)
        timeProvider.setInstant(Instant.parse("2026-12-31T19:00:00Z"))
        runBlocking {
            database
                .planDao()
                .createPlanWithWorkouts(
                    TestData.plan(startDate = "2026-12-28", endDate = "2027-01-03"),
                    listOf(
                        TestData.workout(
                            id = "monday",
                            scheduledDate = "2026-12-28",
                            status = WorkoutStatus.Completed
                        ),
                        TestData.workout(
                            dayOfWeek = 4,
                            scheduledDate = "2026-12-31",
                            title = "Final workout"
                        ),
                    ),
                    listOf(TestData.plannedExercise(sets = 1)),
                )
            database.historyDao().insertLog(TestData.log(workoutId = "monday"))
            database.historyDao().insertLoggedExercises(listOf(TestData.loggedExercise()))
            database
                .historyDao()
                .insertLoggedSets(listOf(TestData.loggedSet(reps = 5, weightKg = 80.0)))
            database.recordDao().insertRecord(TestData.record())
            database
                .recordDao()
                .insertRecord(
                    TestData.record(
                        id = "logged-record",
                        exerciseName = "Squat",
                        normalizedExerciseName = "squat",
                        sourceType = RecordSource.Logged,
                        sourceWorkoutLogId = "log-a"
                    )
                )
        }
        composeRule.onNodeWithTag(TestTags.ENTRY_GET_STARTED).performScrollTo().performClick()
        // Preserve a nested Plan -> Preview stack when starting Active.
        waitForTag(TestTags.bottomNav(Route.PLAN))
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).performClick()
        waitForTag(TestTags.workout("workout-a"))
        composeRule.onNodeWithTag(TestTags.workout("workout-a")).performScrollTo().performClick()
        waitForText("START WORKOUT")
        composeRule.onNodeWithText("START WORKOUT").performClick()
        waitForDatabase { database.sessionDao().getActiveSession() != null }
        val exercise = runBlocking {
            database
                .sessionDao()
                .getExercisesForSession(checkNotNull(database.sessionDao().getActiveSession()).id)
                .single()
        }
        val set = runBlocking {
            database.sessionDao().getSetsForExercises(listOf(exercise.id)).single()
        }
        waitForTag(TestTags.setReps(set.id))
        composeRule.onNodeWithTag(TestTags.setWeight(set.id)).performTextReplacement("100")
        waitForDatabase {
            database.sessionDao().getSetsForExercises(listOf(exercise.id)).single().weightKg ==
                100.0
        }
        composeRule.onNodeWithTag(TestTags.setReps(set.id)).performTextReplacement("5")
        waitForDatabase {
            database.sessionDao().getSetsForExercises(listOf(exercise.id)).single().reps == 5
        }
        composeRule.onNodeWithText("COMPLETE WORKOUT").performScrollTo().performClick()
        waitForText("PLAN NEXT WEEK")
        val oldWorkouts = runBlocking { database.planDao().getWorkoutsForPlan("plan-a") }
        val logs = runBlocking { database.historyDao().observeAllLogs().first() }
        val records = runBlocking { database.recordDao().observeAllRecords().first() }
        val oldExercises = runBlocking { database.planDao().getExercisesForWorkout("workout-a") }
        val logExercises = runBlocking {
            logs.flatMap { database.historyDao().getLoggedExercisesForLog(it.id) }
        }
        val logSets = runBlocking {
            database.historyDao().getLoggedSetsForExercises(logExercises.map { it.id })
        }
        assertEquals(2, logs.size)
        assertTrue(oldWorkouts.all { it.status == WorkoutStatus.Completed })

        fun assertPreserved(db: IronPathDatabase, beforeAccept: Boolean) = runBlocking {
            if (beforeAccept) assertEquals("plan-a", db.planDao().getActivePlan()?.id)
            assertEquals(oldWorkouts, db.planDao().getWorkoutsForPlan("plan-a"))
            assertEquals(oldExercises, db.planDao().getExercisesForWorkout("workout-a"))
            assertEquals(logs, db.historyDao().observeAllLogs().first())
            assertEquals(records, db.recordDao().observeAllRecords().first())
            assertEquals(
                logExercises,
                logs.flatMap { db.historyDao().getLoggedExercisesForLog(it.id) }
            )
            assertEquals(
                logSets,
                db.historyDao().getLoggedSetsForExercises(logExercises.map { it.id })
            )
        }

        // Home's request must also enter Setup even when a saved Plan destination exists.
        composeRule.onNodeWithText("PLAN NEXT WEEK").performScrollTo().performClick()
        waitForText("Primary Goal")
        composeRule.onNodeWithTag(TestTags.planDay(1)).performScrollTo().performClick()
        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).performScrollTo().performClick()
        waitForText("WEEKLY PLAN")
        assertPreserved(database, true)
        pressBack()
        waitForText("Primary Goal")
        composeRule.onNodeWithText("Cancel Planning").performClick()
        waitForText("PLAN NEXT WEEK")
        assertPreserved(database, true)

        // The Plan tab has its own explicit next-week entry.
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).performClick()
        waitForText("PLAN NEXT WEEK")
        composeRule.onNodeWithText("PLAN NEXT WEEK").performScrollTo().performClick()
        waitForText("Primary Goal")
        assertPreserved(database, true)
        composeRule.onNodeWithText("Cancel Planning").performClick()
        waitForText("PLAN NEXT WEEK")

        composeRule.onNodeWithText("PLAN NEXT WEEK").performScrollTo().performClick()
        waitForText("Primary Goal")
        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).performScrollTo().performClick()
        waitForText("WEEKLY PLAN")
        composeRule.onNodeWithText("ACCEPT PLAN").performScrollTo().performClick()
        waitForText("1 WORKOUTS PLANNED  •  0 COMPLETED")
        val newPlan = runBlocking { checkNotNull(database.planDao().getActivePlan()) }
        assertEquals("2027-01-04", newPlan.startDate)
        assertEquals("2027-01-10", newPlan.endDate)
        assertPreserved(database, false)
        composeRule.activityRule.scenario.recreate()
        waitForText("1 WORKOUTS PLANNED  •  0 COMPLETED")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).performClick()
        waitForText("ACCEPTED WEEK")
        composeRule.onNodeWithText("Primary Goal").assertDoesNotExist()
        composeRule.activityRule.scenario.close()
        TestDatabaseRegistry.closeCurrent()
        val reopened =
            TestDatabaseRegistry.reopen(InstrumentationRegistry.getInstrumentation().targetContext)
        assertEquals(newPlan, runBlocking { reopened.planDao().getActivePlan() })
        assertPreserved(reopened, false)
        reopened.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM weekly_plans WHERE status = 'Active'")
            .use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        reopened.openHelper.readableDatabase
            .query("SELECT status FROM weekly_plans WHERE id = 'plan-a'")
            .use {
                it.moveToFirst()
                assertEquals("Archived", it.getString(0))
            }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForDatabase(condition: suspend () -> Boolean) {
        composeRule.waitUntil(5_000) { runBlocking { condition() } }
    }
}
