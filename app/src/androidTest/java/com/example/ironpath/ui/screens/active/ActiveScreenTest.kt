package com.example.ironpath.ui.screens.active

import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val workout =
        PlannedWorkout(
            id = "workout-a",
            weeklyPlanId = "plan-a",
            dayOfWeek = 1,
            scheduledDate = "2026-07-13",
            title = "Strength A",
        )

    private val session =
        ActiveSession(
            id = "session-a",
            sourcePlannedWorkoutId = workout.id,
            workoutTitle = workout.title,
            startedAt = 1L,
            lastUpdatedAt = 1L,
        )

    private val squat =
        SessionExercise(
            id = "exercise-squat",
            activeSessionId = session.id,
            name = "Squat",
            plannedSets = 1,
            plannedReps = 5,
            plannedWeightKg = 100.0,
            orderIndex = 0,
        )

    @Test
    fun loading_showsProgressState() {
        setContent(ActiveUiState.Loading)

        composeRule.onNodeWithTag(TestTags.ACTIVE_LOADING).assertIsDisplayed()
    }

    @Test
    fun noPlanAndRestDay_renderCorrectActions() {
        var planCalls = 0
        setContent(ActiveUiState.NoPlan, onNavigateToPlan = { planCalls++ })

        composeRule.onNodeWithText("NO WORKOUT READY YET").assertIsDisplayed()
        composeRule.onNodeWithText("OPEN PLAN").performClick()
        composeRule.runOnIdle { assertEquals(1, planCalls) }
    }

    @Test
    fun restDay_withAndWithoutNextDate_rendersContract() {
        setContent(ActiveUiState.RestDay("Wednesday"))

        composeRule.onNodeWithText("NO WORKOUT TODAY").assertIsDisplayed()
        composeRule.onNodeWithText("Your next workout is on Wednesday.").assertIsDisplayed()
    }

    @Test
    fun readyState_startsTheProvidedWorkout() {
        var started: PlannedWorkout? = null
        setContent(ActiveUiState.ReadyToStart(workout), onStartSession = { started = it })

        composeRule.onNodeWithText("Strength A").assertIsDisplayed()
        composeRule.onNodeWithText("START WORKOUT").performClick()

        composeRule.runOnIdle { assertEquals(workout, started) }
    }

    @Test
    fun inSession_ordersExercisesAndSets_andExposesExtraSetSemantics() {
        val bench = squat.copy(id = "exercise-bench", name = "Bench Press", orderIndex = 1)
        val planned = SessionSet("set-planned", squat.id, 1, reps = 5, weightKg = 100.0)
        val extra =
            SessionSet(
                id = "set-extra",
                sessionExerciseId = squat.id,
                setNumber = 2,
                isExtra = true,
            )
        setContent(ActiveUiState.InSession(session, listOf(squat, bench), listOf(extra, planned)))

        composeRule.onNodeWithText("1. Squat").assertIsDisplayed()
        composeRule.onNodeWithText("2. Bench Press").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.setWeight(planned.id)).assertTextEquals("100")
        composeRule
            .onNodeWithTag(TestTags.set(extra.id))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Squat extra set 2, incomplete",
                )
            )
    }

    @Test
    fun enteringBothValidValues_marksSetComplete_withInjectedTimestamp() {
        val initialSet = SessionSet("set-a", squat.id, 1)
        val state =
            mutableStateOf<ActiveUiState>(
                ActiveUiState.InSession(session, listOf(squat), listOf(initialSet))
            )
        var latest: SessionSet? = null
        setStatefulContent(state, nowMillis = { 4_242L }) { updated ->
            latest = updated
            val current = state.value as ActiveUiState.InSession
            state.value =
                current.copy(sets = current.sets.map { if (it.id == updated.id) updated else it })
        }

        composeRule
            .onNodeWithTag(TestTags.setWeight(initialSet.id))
            .assert(hasSetTextAction())
            .performTextReplacement("100")
        composeRule
            .onNodeWithTag(TestTags.setReps(initialSet.id))
            .assert(hasSetTextAction())
            .performTextReplacement("5")

        composeRule.runOnIdle {
            assertEquals(100.0, latest?.weightKg ?: 0.0, 0.0)
            assertEquals(5, latest?.reps)
            assertEquals(4_242L, latest?.completedAt)
        }
        composeRule
            .onNodeWithTag(TestTags.set(initialSet.id))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Squat set 1, complete",
                )
            )
    }

    @Test
    fun clearingEitherValue_clearsCompletion() {
        val completed =
            SessionSet(
                id = "set-complete",
                sessionExerciseId = squat.id,
                setNumber = 1,
                reps = 5,
                weightKg = 100.0,
                completedAt = 9L,
            )
        var latest: SessionSet? = null
        setContent(
            ActiveUiState.InSession(session, listOf(squat), listOf(completed)),
            onUpdateSet = { latest = it },
        )

        composeRule.onNodeWithTag(TestTags.setWeight(completed.id)).performTextReplacement("")

        composeRule.runOnIdle {
            assertNull(latest?.weightKg)
            assertNull(latest?.completedAt)
        }
    }

    @Test
    fun addSetAndCompleteWorkout_invokeExactlyOnce() {
        val set = SessionSet("set-a", squat.id, 1)
        var addExerciseId: String? = null
        var addExistingCount = -1
        var completeCalls = 0
        setContent(
            ActiveUiState.InSession(session, listOf(squat), listOf(set)),
            onAddSet = { exerciseId, count ->
                addExerciseId = exerciseId
                addExistingCount = count
            },
            onFinishWorkout = { completeCalls++ },
        )

        composeRule.onAllNodesWithText("ADD SET")[0].performClick()
        composeRule.onNodeWithTag(TestTags.ACTIVE_COMPLETE).assertHasClickAction().performClick()

        composeRule.runOnIdle {
            assertEquals(squat.id, addExerciseId)
            assertEquals(1, addExistingCount)
            assertEquals(1, completeCalls)
        }
    }

    private fun setContent(
        state: ActiveUiState,
        nowMillis: () -> Long = { 1L },
        onNavigateToPlan: () -> Unit = {},
        onStartSession: (PlannedWorkout) -> Unit = {},
        onUpdateSet: (SessionSet) -> Unit = {},
        onAddSet: (String, Int) -> Unit = { _, _ -> },
        onFinishWorkout: () -> Unit = {},
    ) {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    ActiveContent(
                        uiState = state,
                        elapsedSeconds = 65,
                        nowMillis = nowMillis,
                        onNavigateToPlan = onNavigateToPlan,
                        onStartSession = onStartSession,
                        onUpdateSet = onUpdateSet,
                        onAddSet = onAddSet,
                        onFinishWorkout = onFinishWorkout,
                    )
                }
            }
        }
    }

    private fun setStatefulContent(
        state: androidx.compose.runtime.MutableState<ActiveUiState>,
        nowMillis: () -> Long,
        onUpdateSet: (SessionSet) -> Unit,
    ) {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    ActiveContent(
                        uiState = state.value,
                        elapsedSeconds = 65,
                        nowMillis = nowMillis,
                        onNavigateToPlan = {},
                        onStartSession = {},
                        onUpdateSet = onUpdateSet,
                        onAddSet = { _, _ -> },
                        onFinishWorkout = {},
                    )
                }
            }
        }
    }
}
