package com.example.ironpath.accessibility

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.TestData
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Platform Accessibility Test Framework coverage. The Compose integration requires API 34. */
@SdkSuppress(minSdkVersion = 34)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PlatformAccessibilityChecksTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()

    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: IronPathDatabase

    @Inject lateinit var timeProvider: MutableTimeProvider

    @Before
    fun setUp() {
        hiltRule.inject()
        // AndroidComposeTestRule launches MainActivity before @Before. Enabling here still
        // guarantees
        // validation is active before this test performs its first UI action.
        composeRule.enableAccessibilityChecks()
        waitForText("GET STARTED")
    }

    @Test
    fun emptyAndGeneratedStates_passPlatformChecksAcrossEveryTopLevelRoute() {
        checkCurrentSurface()

        composeRule.onNodeWithText("GET STARTED").performClick()
        waitForTagToDisappear(TestTags.HOME_LOADING)
        waitForText("No workout plan yet")
        checkCurrentSurface()

        navigateTo(Route.PLAN)
        waitForTagToDisappear(TestTags.PLAN_LOADING)
        waitForTag(TestTags.planDay(1))
        checkCurrentSurface()

        composeRule.onNodeWithTag(TestTags.planDay(1)).performClick()
        composeRule.onNodeWithTag(TestTags.PLAN_GENERATE).performScrollTo().performClick()
        waitForText("WEEKLY PLAN")
        composeRule.onNodeWithText("ACCEPT PLAN").performScrollTo().assertIsDisplayed()
        checkCurrentSurface()

        navigateTo(Route.ACTIVE)
        waitForTagToDisappear(TestTags.ACTIVE_LOADING)
        waitForText("NO WORKOUT READY YET")
        checkCurrentSurface()

        navigateTo(Route.HISTORY)
        waitForText("No workout logs yet")
        checkCurrentSurface()

        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("No records yet")
        checkCurrentSurface()

        composeRule.onNodeWithText("ADD RECORD").performClick()
        waitForText("ADD RECORD")
        composeRule.onNodeWithText("SAVE").performScrollTo().assertIsDisplayed()
        checkCurrentSurface()
    }

    @Test
    fun persistedStates_passPlatformChecksAcrossHomePlanActiveAndDetailRoutes() {
        seedPersistedGraph()

        checkCurrentSurface()
        composeRule.onNodeWithText("GET STARTED").performClick()
        waitForTagToDisappear(TestTags.HOME_LOADING)
        waitForTag(TestTags.workout(WORKOUT_ID))
        checkCurrentSurface()

        composeRule.onNodeWithTag(TestTags.workout(WORKOUT_ID)).performScrollTo().performClick()
        waitForText("WORKOUT PREVIEW")
        checkCurrentSurface()

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForTag(TestTags.workout(WORKOUT_ID))

        navigateTo(Route.PLAN)
        waitForTagToDisappear(TestTags.PLAN_LOADING)
        waitForText("SESSION IN PROGRESS")
        checkCurrentSurface()

        navigateTo(Route.ACTIVE)
        waitForTagToDisappear(TestTags.ACTIVE_LOADING)
        waitForTag(TestTags.set(SESSION_SET_ID))
        checkCurrentSurface()
        composeRule.onNodeWithText("COMPLETE WORKOUT").performScrollTo().assertIsDisplayed()
        checkCurrentSurface()

        navigateTo(Route.HISTORY)
        waitForTag(TestTags.log(LOG_ID))
        checkCurrentSurface()

        composeRule.onNodeWithTag(TestTags.log(LOG_ID)).performScrollTo().performClick()
        waitForText("WORKOUT LOG")
        checkCurrentSurface()
        composeRule.onNodeWithText("SET 1").performScrollTo().assertIsDisplayed()
        checkCurrentSurface()
    }

    private fun seedPersistedGraph() {
        val now = timeProvider.epochMillis()
        runBlocking {
            database
                .planDao()
                .createPlanWithWorkouts(
                    plan = TestData.plan(id = PLAN_ID, createdAt = now),
                    workouts =
                        listOf(
                            TestData.workout(
                                id = WORKOUT_ID,
                                planId = PLAN_ID,
                                title = WORKOUT_TITLE,
                            )
                        ),
                    exercises =
                        listOf(
                            TestData.plannedExercise(
                                id = PLANNED_EXERCISE_ID,
                                workoutId = WORKOUT_ID,
                            )
                        ),
                )
            database
                .sessionDao()
                .startNewSession(
                    session =
                        TestData.session(
                            id = SESSION_ID,
                            workoutId = WORKOUT_ID,
                            title = WORKOUT_TITLE,
                            startedAt = now - 60_000,
                            lastUpdatedAt = now,
                        ),
                    exercises =
                        listOf(
                            TestData.sessionExercise(
                                id = SESSION_EXERCISE_ID,
                                sessionId = SESSION_ID,
                            )
                        ),
                )
            database
                .sessionDao()
                .insertSet(
                    TestData.sessionSet(
                        id = SESSION_SET_ID,
                        exerciseId = SESSION_EXERCISE_ID,
                        reps = 5,
                        weightKg = 100.0,
                        completedAt = now,
                    )
                )

            database
                .historyDao()
                .insertLog(
                    TestData.log(
                        id = LOG_ID,
                        title = WORKOUT_TITLE,
                        workoutId = WORKOUT_ID,
                        startedAt = now - 3_600_000,
                        completedAt = now,
                    )
                )
            database
                .historyDao()
                .insertLoggedExercises(
                    listOf(
                        TestData.loggedExercise(
                            id = LOGGED_EXERCISE_ID,
                            logId = LOG_ID,
                        )
                    )
                )
            database
                .historyDao()
                .insertLoggedSets(
                    listOf(
                        TestData.loggedSet(
                            id = LOGGED_SET_ID,
                            exerciseId = LOGGED_EXERCISE_ID,
                            reps = 5,
                            weightKg = 100.0,
                            completedAt = now,
                        )
                    )
                )
        }
    }

    private fun navigateTo(route: String) {
        val tag = TestTags.bottomNav(route)
        waitForTag(tag)
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun checkCurrentSurface() {
        composeRule.waitForIdle()
        composeRule.onRoot().tryPerformAccessibilityChecks()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForTagToDisappear(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private companion object {
        const val PLAN_ID = "accessibility-plan"
        const val WORKOUT_ID = "accessibility-workout"
        const val WORKOUT_TITLE = "Accessible Strength"
        const val PLANNED_EXERCISE_ID = "accessibility-planned-exercise"
        const val SESSION_ID = "accessibility-session"
        const val SESSION_EXERCISE_ID = "accessibility-session-exercise"
        const val SESSION_SET_ID = "accessibility-session-set"
        const val LOG_ID = "accessibility-log"
        const val LOGGED_EXERCISE_ID = "accessibility-logged-exercise"
        const val LOGGED_SET_ID = "accessibility-logged-set"
    }
}
