package com.example.ironpath.ui.screens.home

import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val composeRule = createComposeRule()

    private val plan =
        WeeklyPlan(
            id = "plan-a",
            startDate = "2026-07-13",
            endDate = "2026-07-19",
            createdAt = 1L,
        )

    private val todayWorkout =
        PlannedWorkout(
            id = "workout-today",
            weeklyPlanId = plan.id,
            dayOfWeek = 1,
            scheduledDate = "2026-07-13",
            title = "Squat Day",
        )

    private val nextWorkout =
        PlannedWorkout(
            id = "workout-next",
            weeklyPlanId = plan.id,
            dayOfWeek = 3,
            scheduledDate = "2026-07-15",
            title = "Bench Day",
        )

    @Test
    fun loading_showsProgressState() {
        setContent(HomeUiState.Loading)

        composeRule.onNodeWithTag(TestTags.HOME_LOADING).assertIsDisplayed()
    }

    @Test
    fun noPlan_showsOpenPlan_andInvokesCallback() {
        var calls = 0
        setContent(HomeUiState.NoPlan, onNavigateToPlan = { calls++ })

        composeRule.onNodeWithText("NO WORKOUT PLAN YET", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("OPEN PLAN").performClick()

        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    @Test
    fun activePlan_showsCountsTodayAndNextWorkout_andOpensTaggedWorkout() {
        var openedId: String? = null
        setContent(
            HomeUiState.ActivePlan(
                plan = plan,
                workouts = listOf(todayWorkout, nextWorkout),
                planned = 2,
                completed = 0,
                todayWorkout = todayWorkout,
                nextWorkout = todayWorkout,
                hasActiveSession = false,
            ),
            onOpenWorkoutPreview = { openedId = it },
        )

        composeRule.onNodeWithText("2 WORKOUTS PLANNED  •  0 COMPLETED").assertIsDisplayed()
        composeRule.onNodeWithText("Today: MON  •  Squat Day").assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.workout(nextWorkout.id)).performClick()

        composeRule.runOnIdle { assertEquals(nextWorkout.id, openedId) }
    }

    @Test
    fun activeSession_showsReturnToActiveInsteadOfStart() {
        var activeCalls = 0
        setContent(
            HomeUiState.ActivePlan(
                plan = plan,
                workouts = listOf(todayWorkout),
                planned = 1,
                completed = 0,
                todayWorkout = todayWorkout,
                nextWorkout = todayWorkout,
                hasActiveSession = true,
            ),
            onNavigateToActive = { activeCalls++ },
        )

        composeRule.onNodeWithText("START WORKOUT").assertDoesNotExist()
        composeRule.onNodeWithText("RETURN TO ACTIVE SESSION").performClick()

        composeRule.runOnIdle { assertEquals(1, activeCalls) }
    }

    @Test
    fun completedWeek_showsPlanNextWeek_andInvokesCallback() {
        var calls = 0
        setContent(HomeUiState.WeekComplete(planned = 3, completed = 3), { calls++ })

        composeRule.onNodeWithText("WEEK COMPLETE!", ignoreCase = true).assertIsDisplayed()
        composeRule.onNodeWithText("PLAN NEXT WEEK").performClick()

        composeRule.runOnIdle { assertEquals(1, calls) }
    }

    private fun setContent(
        state: HomeUiState,
        onNavigateToPlan: () -> Unit = {},
        onNavigateToActive: () -> Unit = {},
        onOpenWorkoutPreview: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            IronPathTheme {
                Surface {
                    HomeContent(
                        uiState = state,
                        onNavigateToPlan = onNavigateToPlan,
                        onNavigateToActive = onNavigateToActive,
                        onOpenWorkoutPreview = onOpenWorkoutPreview,
                    )
                }
            }
        }
    }
}
