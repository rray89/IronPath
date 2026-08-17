package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.domain.planner.ExerciseCatalogIds
import com.example.ironpath.domain.planner.PlanningGoal
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
class AiPlanReviewJourneyTest {
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
    fun aiDraft_editValidateAcceptAndRecreate_persistsOneCanonicalWeek() {
        waitForTag(TestTags.ENTRY_GET_STARTED)
        composeRule
            .onNodeWithTag(TestTags.ENTRY_GET_STARTED)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForText("No workout plan yet")
        clickNavigationDestination(Route.PLAN)
        waitForText("Primary Goal")

        composeRule.onNodeWithTag(TestTags.planGoal(PlanningGoal.STRENGTH.slug)).performClick()
        composeRule.onNodeWithTag(TestTags.planDay(1)).performClick()
        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE_AI).performScrollTo().performClick()

        waitForTag(TestTags.PLAN_AI_REVIEW)
        composeRule.onNodeWithText("DEBUG FAKE AI", substring = true).assertIsDisplayed()
        composeRule
            .onNodeWithText("Training guidance only. This is not medical advice.")
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(TestTags.planAiExercise(1, ExerciseCatalogIds.BARBELL_BENCH_PRESS.value))
            .performScrollTo()
            .performClick()

        val editor = composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_LIST)
        editor.performScrollToNode(hasTestTag(TestTags.PLAN_AI_EDITOR_WEIGHT))
        composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_WEIGHT).performTextReplacement("50")
        editor.performScrollToNode(hasTestTag(TestTags.PLAN_AI_EDITOR_CONFIRM))
        composeRule.onNodeWithTag(TestTags.PLAN_AI_EDITOR_CONFIRM).performClick()
        composeRule.onNodeWithText("3×6 · 50kg").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(TestTags.PLAN_AI_ACCEPT).performScrollTo().performClick()
        waitForText(HOME_SUMMARY)

        composeRule.activityRule.scenario.recreate()
        waitForText(HOME_SUMMARY)

        runBlocking {
            val activePlan = requireNotNull(database.planDao().getActivePlan())
            val workouts = database.planDao().getWorkoutsForPlan(activePlan.id)
            val exercises = database.planDao().getExercisesForWorkout(workouts.single().id)

            assertEquals("2026-07-20", activePlan.startDate)
            assertEquals("2026-07-26", activePlan.endDate)
            assertEquals(listOf(1), workouts.map { it.dayOfWeek })
            assertEquals("Barbell Bench Press", exercises.single().name)
            assertEquals(3, exercises.single().sets)
            assertEquals(6, exercises.single().reps)
            assertEquals(50.0, exercises.single().weightKg, 0.0)
        }
        assertEquals(1, tableCount("weekly_plans"))
        assertEquals(1, tableCount("planned_workouts"))
        assertEquals(1, tableCount("planned_exercises"))
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
        const val HOME_SUMMARY = "1 WORKOUTS PLANNED  •  0 COMPLETED"
        val TABLES = setOf("weekly_plans", "planned_workouts", "planned_exercises")
    }
}
