package com.example.ironpath.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlanPersistenceJourneyTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()

    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: IronPathDatabase

    @Inject lateinit var timeProvider: MutableTimeProvider

    @Inject lateinit var idProvider: SequenceIdProvider

    @Before
    fun injectAndResetDeterministicProviders() {
        hiltRule.inject()
        timeProvider.reset()
        idProvider.reset()
    }

    @Test
    fun generatedPlan_survivesAcceptanceAndActivityRecreation() {
        composeRule.onNodeWithText("GET STARTED").performClick()
        waitForText("No workout plan yet")

        clickNavigationDestination(Route.PLAN)
        waitForText("Primary Goal")

        composeRule.onNodeWithTag(TestTags.planGoal("Strength")).performClick()
        composeRule.onNodeWithTag(TestTags.planDay(1)).performClick()
        composeRule.onNodeWithTag(TestTags.planDay(3)).performClick()
        composeRule.onNodeWithTag(TestTags.planDay(5)).performClick()
        composeRule.onNodeWithTag(TestTags.planGoal("Strength")).assertIsSelected()
        composeRule.onNodeWithTag(TestTags.planDay(1)).assertIsSelected()
        composeRule.onNodeWithTag(TestTags.planDay(3)).assertIsSelected()
        composeRule.onNodeWithTag(TestTags.planDay(5)).assertIsSelected()

        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).performClick()
        waitForText("WEEKLY PLAN")

        composeRule.onAllNodesWithContentDescription("Remove workout").assertCountEquals(3)
        composeRule.onNodeWithTag(TestTags.workout("e2e-2")).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.workout("e2e-6")).assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.workout("e2e-10")).assertIsDisplayed()
        composeRule.onNodeWithText("Barbell Bench Press").assertIsDisplayed()
        composeRule.onNodeWithText("Barbell Rows").assertIsDisplayed()
        composeRule.onNodeWithText("Barbell Squats").assertIsDisplayed()

        composeRule.onNodeWithText("ACCEPT PLAN").performClick()
        waitForText(HOME_SUMMARY)

        composeRule.activityRule.scenario.recreate()

        waitForText(HOME_SUMMARY)
        waitForTag(TestTags.bottomNav(Route.HOME))
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsDisplayed()

        runBlocking {
            val activePlan = requireNotNull(database.planDao().getActivePlan())
            val workouts = database.planDao().getWorkoutsForPlan(activePlan.id)
            val exerciseCount =
                workouts.sumOf { workout ->
                    database.planDao().getExercisesForWorkout(workout.id).size
                }

            assertEquals("2026-07-20", activePlan.startDate)
            assertEquals("2026-07-26", activePlan.endDate)
            assertEquals(3, workouts.size)
            assertEquals(listOf(1, 3, 5), workouts.map { it.dayOfWeek })
            assertEquals(9, exerciseCount)
        }
        assertEquals(1, tableCount("weekly_plans"))
        assertEquals(3, tableCount("planned_workouts"))
        assertEquals(9, tableCount("planned_exercises"))
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun clickNavigationDestination(route: String) {
        val tag = TestTags.bottomNav(route)
        waitForTag(tag)
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun tableCount(table: String): Int {
        check(table in TABLES)
        return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private companion object {
        const val HOME_SUMMARY = "3 WORKOUTS PLANNED  •  0 COMPLETED"
        val TABLES = setOf("weekly_plans", "planned_workouts", "planned_exercises")
    }
}
