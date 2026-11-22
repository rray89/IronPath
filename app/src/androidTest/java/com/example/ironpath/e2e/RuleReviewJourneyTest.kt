package com.example.ironpath.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
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
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RuleReviewJourneyTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var database: IronPathDatabase
    @Inject lateinit var clock: MutableTimeProvider
    @Inject lateinit var ids: SequenceIdProvider

    @Before
    fun inject() {
        hiltRule.inject()
        clock.reset()
    }

    @Test
    fun movedSwappedEditedAddedReorderedAndUndoneDraftPersistsAfterRecreation() {
        waitForTag(TestTags.ENTRY_GET_STARTED)
        ids.reset()
        composeRule.onNodeWithTag(TestTags.ENTRY_GET_STARTED).performScrollTo().performClick()
        waitForText("No workout plan yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).performClick()
        waitForTag(TestTags.PLAN_GENERATE)
        composeRule.onNodeWithTag(TestTags.planGoal(PlanningGoal.STRENGTH.slug)).performClick()
        composeRule.onNodeWithTag(TestTags.planDay(1)).performClick()
        composeRule.onNodeWithTag(TestTags.planDay(3)).performClick()
        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).performScrollTo().performClick()
        waitForText("WEEKLY PLAN")

        composeRule.onNodeWithTag(TestTags.planReviewDay("e2e-2")).performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_day_5").performScrollTo().performClick()
        composeRule.onNodeWithTag(TestTags.planReviewDay("e2e-6")).performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_day_5").performScrollTo().performClick()
        composeRule.onNodeWithTag(TestTags.planExercise("e2e-3")).performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_name").performTextReplacement("Custom lift")
        composeRule.onNodeWithTag("rule_sets").performScrollTo().performTextReplacement("20")
        composeRule.onNodeWithTag("rule_reps").performScrollTo().performTextReplacement("100")
        composeRule.onNodeWithTag("rule_weight").performScrollTo().performTextReplacement("0.5")
        composeRule.onNodeWithTag("rule_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_add_e2e-2").performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_name").performTextReplacement("Custom lift")
        composeRule.onNodeWithTag("rule_save").performScrollTo().performClick()
        composeRule.onNodeWithTag("rule_drag_e2e-10").performScrollTo().performClick()
        composeRule.onNodeWithText("Move up").performClick()
        composeRule.onNodeWithTag("rule_remove_e2e-3").performScrollTo().performClick()
        composeRule.onNodeWithText("Undo").performClick()
        composeRule
            .onNodeWithTag(TestTags.planExercise("e2e-3"))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("ACCEPT PLAN").performScrollTo().performClick()
        waitForText("2 WORKOUTS PLANNED  •  0 COMPLETED")
        composeRule.activityRule.scenario.recreate()
        waitForText("2 WORKOUTS PLANNED  •  0 COMPLETED")

        runBlocking {
            val dao = database.planDao()
            val plan = requireNotNull(dao.getActivePlan())
            val workouts = dao.getWorkoutsForPlan(plan.id)
            assertEquals("e2e-1", plan.id)
            assertEquals(listOf("e2e-2", "e2e-6"), workouts.map { it.id })
            assertEquals(listOf(3, 5), workouts.map { it.dayOfWeek })
            assertEquals(listOf("2026-07-22", "2026-07-24"), workouts.map { it.scheduledDate })
            assertEquals(listOf("Push A", "Pull A"), workouts.map { it.title })
            val edited = dao.getExercisesForWorkout("e2e-2")
            assertEquals(listOf("e2e-3", "e2e-4", "e2e-10", "e2e-5"), edited.map { it.id })
            assertEquals(listOf(0, 1, 2, 3), edited.map { it.orderIndex })
            assertEquals("Custom lift", edited.first().name)
            assertEquals(20, edited.first().sets)
            assertEquals(100, edited.first().reps)
            assertEquals(0.5, edited.first().weightKg, 0.0)
            assertEquals("Custom lift", edited[2].name)
            assertEquals(3, dao.getExercisesForWorkout("e2e-6").size)
            assertTrue(edited.all { it.plannedWorkoutId == "e2e-2" })
        }
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.PLAN)).performClick()
        composeRule.onNodeWithTag("rule_add_e2e-2").assertDoesNotExist()
        composeRule.onNodeWithTag(TestTags.planReviewDay("e2e-2")).assertDoesNotExist()
        composeRule.onNodeWithTag("rule_drag_e2e-3").assertDoesNotExist()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
