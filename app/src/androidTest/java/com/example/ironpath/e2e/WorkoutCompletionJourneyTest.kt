package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.TestDatabaseRegistry
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class WorkoutCompletionJourneyTest {
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
    fun completionJourney_persistsActiveInputAcrossRecreation_andSnapshotsHistoryAfterReopen() {
        val sessionStartedAt = timeProvider.epochMillis()
        seedAcceptedPlan(sessionStartedAt)

        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performClick()
        waitForText("2 WORKOUTS PLANNED  •  0 COMPLETED")

        waitForTag(TestTags.workout(TODAY_WORKOUT_ID))
        composeRule
            .onNodeWithTag(TestTags.workout(TODAY_WORKOUT_ID))
            .performScrollTo()
            .performClick()
        waitForText("WORKOUT PREVIEW")
        composeRule.onNodeWithText(TODAY_WORKOUT_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").performClick()
        waitForText(TODAY_WORKOUT_TITLE.uppercase())

        val activeSession = runBlocking { requireNotNull(database.sessionDao().getActiveSession()) }
        val sessionExercise = runBlocking {
            database.sessionDao().getExercisesForSession(activeSession.id).single()
        }
        val plannedSet = runBlocking {
            database.sessionDao().getSetsForExercises(listOf(sessionExercise.id)).single {
                !it.isExtra
            }
        }

        waitForTag(TestTags.setWeight(plannedSet.id))
        composeRule
            .onNodeWithTag(TestTags.setWeight(plannedSet.id))
            .performTextReplacement(COMPLETED_WEIGHT_TEXT)
        waitForPersistedSet(sessionExercise.id, plannedSet.id) {
            it.weightKg == COMPLETED_WEIGHT && it.reps == null
        }
        composeRule
            .onNodeWithTag(TestTags.setReps(plannedSet.id))
            .performTextReplacement(COMPLETED_REPS.toString())
        waitForPersistedSet(sessionExercise.id, plannedSet.id) {
            it.weightKg == COMPLETED_WEIGHT &&
                it.reps == COMPLETED_REPS &&
                it.completedAt == sessionStartedAt
        }

        composeRule.onNodeWithText("ADD SET").performScrollTo().performClick()
        waitForDatabase {
            database.sessionDao().getSetsForExercises(listOf(sessionExercise.id)).any { it.isExtra }
        }
        val unfinishedExtra = runBlocking {
            database.sessionDao().getSetsForExercises(listOf(sessionExercise.id)).single {
                it.isExtra
            }
        }
        assertNull(unfinishedExtra.reps)
        assertNull(unfinishedExtra.weightKg)
        assertNull(unfinishedExtra.completedAt)
        waitForTag(TestTags.set(unfinishedExtra.id))

        composeRule.activityRule.scenario.recreate()

        waitForText(TODAY_WORKOUT_TITLE.uppercase())
        waitForTag(TestTags.setWeight(plannedSet.id))
        composeRule
            .onNodeWithTag(TestTags.setWeight(plannedSet.id))
            .performScrollTo()
            .assertTextEquals(COMPLETED_WEIGHT_TEXT)
        composeRule
            .onNodeWithTag(TestTags.setReps(plannedSet.id))
            .assertTextEquals(COMPLETED_REPS.toString())
        composeRule
            .onNodeWithTag(TestTags.setWeight(unfinishedExtra.id))
            .performScrollTo()
            .assertTextEquals("")
        composeRule.onNodeWithTag(TestTags.setReps(unfinishedExtra.id)).assertTextEquals("")
        runBlocking {
            assertEquals(activeSession.id, database.sessionDao().getActiveSession()?.id)
            val recreatedSets =
                database.sessionDao().getSetsForExercises(listOf(sessionExercise.id))
            assertEquals(2, recreatedSets.size)
            assertEquals(COMPLETED_REPS, recreatedSets.single { !it.isExtra }.reps)
            assertEquals(
                COMPLETED_WEIGHT,
                recreatedSets.single { !it.isExtra }.weightKg ?: 0.0,
                0.0
            )
            assertNull(recreatedSets.single { it.isExtra }.completedAt)
        }

        timeProvider.setInstant(SESSION_COMPLETED_AT)
        composeRule.onNodeWithText("COMPLETE WORKOUT").performScrollTo().performClick()
        waitForText("2 WORKOUTS PLANNED  •  1 COMPLETED")
        waitForDatabase { database.sessionDao().getActiveSession() == null }
        composeRule.onNodeWithText("RETURN TO ACTIVE SESSION").assertDoesNotExist()

        val completedLog = runBlocking { database.historyDao().observeAllLogs().first().single() }
        clickNavigationDestination(Route.HISTORY)
        waitForTag(TestTags.log(completedLog.id))
        composeRule.onNodeWithText("65 MIN").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.log(completedLog.id)).performClick()

        waitForText("WORKOUT LOG")
        composeRule.onNodeWithText(TODAY_WORKOUT_TITLE).assertIsDisplayed()
        composeRule.onNodeWithText("Squat").performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithText("$COMPLETED_REPS reps · $COMPLETED_WEIGHT_TEXT kg")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText("SET 1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("EXTRA 2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Not logged").performScrollTo().assertIsDisplayed()

        composeRule.activityRule.scenario.close()
        TestDatabaseRegistry.closeCurrent()
        val reopened =
            TestDatabaseRegistry.reopen(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
        assertPersistedCompletion(
            reopened = reopened,
            sessionStartedAt = sessionStartedAt,
            sessionExerciseId = sessionExercise.id,
            plannedSetId = plannedSet.id,
            extraSetId = unfinishedExtra.id,
        )
    }

    private fun seedAcceptedPlan(createdAt: Long) {
        runBlocking {
            database
                .planDao()
                .createPlanWithWorkouts(
                    plan =
                        WeeklyPlan(
                            id = PLAN_ID,
                            startDate = "2026-07-13",
                            endDate = "2026-07-19",
                            createdAt = createdAt,
                        ),
                    workouts =
                        listOf(
                            PlannedWorkout(
                                id = TODAY_WORKOUT_ID,
                                weeklyPlanId = PLAN_ID,
                                dayOfWeek = 1,
                                scheduledDate = "2026-07-13",
                                title = TODAY_WORKOUT_TITLE,
                            ),
                            PlannedWorkout(
                                id = FUTURE_WORKOUT_ID,
                                weeklyPlanId = PLAN_ID,
                                dayOfWeek = 3,
                                scheduledDate = "2026-07-15",
                                title = "Future Pull",
                            ),
                        ),
                    exercises =
                        listOf(
                            PlannedExercise(
                                id = "planned-squat",
                                plannedWorkoutId = TODAY_WORKOUT_ID,
                                name = "Squat",
                                sets = 1,
                                reps = 5,
                                weightKg = 100.0,
                                orderIndex = 0,
                            ),
                            PlannedExercise(
                                id = "planned-row",
                                plannedWorkoutId = FUTURE_WORKOUT_ID,
                                name = "Barbell Row",
                                sets = 1,
                                reps = 8,
                                weightKg = 60.0,
                                orderIndex = 0,
                            ),
                        ),
                )
        }
    }

    private fun assertPersistedCompletion(
        reopened: IronPathDatabase,
        sessionStartedAt: Long,
        sessionExerciseId: String,
        plannedSetId: String,
        extraSetId: String,
    ) {
        runBlocking {
            assertNull(reopened.sessionDao().getActiveSession())

            val workouts = reopened.planDao().getWorkoutsForPlan(PLAN_ID)
            assertEquals(2, workouts.size)
            assertEquals(1, workouts.count { it.status == WorkoutStatus.Completed })
            assertEquals(
                WorkoutStatus.Completed,
                workouts.single { it.id == TODAY_WORKOUT_ID }.status,
            )
            assertEquals(
                WorkoutStatus.Upcoming,
                workouts.single { it.id == FUTURE_WORKOUT_ID }.status,
            )

            val logs = reopened.historyDao().observeAllLogs().first()
            assertEquals(1, logs.size)
            val log = logs.single()
            assertEquals(TODAY_WORKOUT_TITLE, log.title)
            assertEquals(TODAY_WORKOUT_ID, log.sourcePlannedWorkoutId)
            assertEquals(sessionStartedAt, log.startedAt)
            assertEquals(SESSION_COMPLETED_AT.toEpochMilli(), log.completedAt)
            assertEquals(65, log.durationMinutes)
            assertEquals(1, log.exerciseCount)

            val exercises = reopened.historyDao().getLoggedExercisesForLog(log.id)
            assertEquals(1, exercises.size)
            val exercise = exercises.single()
            assertEquals(sessionExerciseId, exercise.id)
            assertEquals("Squat", exercise.name)

            val sets = reopened.historyDao().getLoggedSetsForExercises(listOf(exercise.id))
            assertEquals(2, sets.size)
            val completedPlannedSet = sets.single { !it.isExtra }
            assertEquals(plannedSetId, completedPlannedSet.id)
            assertEquals(1, completedPlannedSet.setNumber)
            assertEquals(COMPLETED_REPS, completedPlannedSet.reps)
            assertEquals(COMPLETED_WEIGHT, completedPlannedSet.weightKg ?: 0.0, 0.0)
            assertEquals(sessionStartedAt, completedPlannedSet.completedAt)

            val persistedExtra = sets.single { it.isExtra }
            assertEquals(extraSetId, persistedExtra.id)
            assertEquals(2, persistedExtra.setNumber)
            assertTrue(persistedExtra.isExtra)
            assertNull(persistedExtra.reps)
            assertNull(persistedExtra.weightKg)
            assertNull(persistedExtra.completedAt)
            assertNotNull(reopened.historyDao().getLogById(log.id))
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickNavigationDestination(route: String) {
        val tag = TestTags.bottomNav(route)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).performClick()
    }

    private fun waitForDatabase(condition: suspend () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 5_000) { runBlocking { condition() } }
    }

    private fun waitForPersistedSet(
        exerciseId: String,
        setId: String,
        predicate: (com.example.ironpath.data.local.entity.SessionSet) -> Boolean,
    ) {
        waitForDatabase {
            database
                .sessionDao()
                .getSetsForExercises(listOf(exerciseId))
                .singleOrNull { it.id == setId }
                ?.let(predicate) == true
        }
    }

    private companion object {
        const val PLAN_ID = "plan-completion-journey"
        const val TODAY_WORKOUT_ID = "workout-today-completion-journey"
        const val FUTURE_WORKOUT_ID = "workout-future-completion-journey"
        const val TODAY_WORKOUT_TITLE = "Journey Strength"
        const val COMPLETED_WEIGHT_TEXT = "102.5"
        const val COMPLETED_WEIGHT = 102.5
        const val COMPLETED_REPS = 6
        val SESSION_COMPLETED_AT: Instant = Instant.parse("2026-07-13T18:05:00Z")
    }
}
