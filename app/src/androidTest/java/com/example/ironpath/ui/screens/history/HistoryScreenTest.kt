package com.example.ironpath.ui.screens.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emptyLogs_renderProductionCopy() {
        setHistoryContent(selectedTab = HistoryTab.Logs)

        composeRule.onNodeWithText("No workout logs yet").assertIsDisplayed()
        composeRule.onNodeWithText("Completed workouts will appear here.").assertIsDisplayed()
    }

    @Test
    fun populatedLogs_renderAndInvokeTheSelectedLog() {
        val log = workoutLog(id = "log-1", title = "Push A")
        var openedLog: WorkoutLog? = null
        setHistoryContent(
            selectedTab = HistoryTab.Logs,
            logs = listOf(log),
            onLogClick = { openedLog = it },
        )

        composeRule.onNodeWithTag(TestTags.log("log-1")).assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Push A").assertIsDisplayed()

        assertEquals(log, openedLog)
    }

    @Test
    fun tabSelectionAndEmptyRecordAddActionInvokeCallbacks() {
        var selectedTab: HistoryTab? = null
        var addCount = 0
        setHistoryContent(
            selectedTab = HistoryTab.Records,
            onTabSelected = { selectedTab = it },
            onAddRecord = { addCount++ },
        )

        composeRule.onNodeWithText("LOGS").performClick()
        composeRule.onNodeWithText("ADD RECORD").performClick()

        assertEquals(HistoryTab.Logs, selectedTab)
        assertEquals(1, addCount)
    }

    @Test
    fun recordsAreDisplayOnly_showBothSourceBadges_andPreserveDecimalWeights() {
        val manual =
            personalRecord(
                id = "manual",
                name = "Bench Press",
                weightKg = 62.5,
                source = RecordSource.Manual,
            )
        val logged =
            personalRecord(
                id = "logged",
                name = "Squat",
                weightKg = 100.0,
                source = RecordSource.Logged,
            )
        setHistoryContent(
            selectedTab = HistoryTab.Records,
            records = listOf(manual, logged),
        )

        composeRule.onNodeWithTag(TestTags.record("manual")).assertHasNoClickAction()
        composeRule.onNodeWithTag(TestTags.record("logged")).assertHasNoClickAction()
        composeRule.onAllNodesWithText("MANUAL").assertCountEquals(1)
        composeRule.onAllNodesWithText("LOGGED").assertCountEquals(1)
        composeRule.onNodeWithText("62.5 kg").assertIsDisplayed()
        composeRule.onNodeWithText("100 kg").assertIsDisplayed()
    }

    @Test
    fun recordsRenderInRepositoryOrder_andAddNewRecordInvokesOnce() {
        val newest = personalRecord(id = "newest", name = "Deadlift", weightKg = 180.0)
        val older = personalRecord(id = "older", name = "Press", weightKg = 50.0)
        var addCount = 0
        setHistoryContent(
            selectedTab = HistoryTab.Records,
            records = listOf(newest, older),
            onAddRecord = { addCount++ },
        )

        val newestY =
            composeRule
                .onNodeWithTag(TestTags.record("newest"))
                .fetchSemanticsNode()
                .positionInRoot
                .y
        val olderY =
            composeRule
                .onNodeWithTag(TestTags.record("older"))
                .fetchSemanticsNode()
                .positionInRoot
                .y
        assertTrue(newestY < olderY)

        composeRule.onNodeWithText("ADD NEW RECORD").performClick()
        assertEquals(1, addCount)
    }

    private fun setHistoryContent(
        selectedTab: HistoryTab,
        logs: List<WorkoutLog> = emptyList(),
        records: List<PersonalRecord> = emptyList(),
        onTabSelected: (HistoryTab) -> Unit = {},
        onAddRecord: () -> Unit = {},
        onLogClick: (WorkoutLog) -> Unit = {},
    ) {
        composeRule.setContent {
            IronPathTheme {
                HistoryContent(
                    selectedTab = selectedTab,
                    logs = logs,
                    records = records,
                    onTabSelected = onTabSelected,
                    onAddRecord = onAddRecord,
                    onLogClick = onLogClick,
                    zoneId = ZoneOffset.UTC,
                )
            }
        }
    }

    private fun workoutLog(id: String, title: String) =
        WorkoutLog(
            id = id,
            title = title,
            startedAt = 1_000L,
            completedAt = 2_000L,
            durationMinutes = 1,
            exerciseCount = 1,
        )

    private fun personalRecord(
        id: String,
        name: String,
        weightKg: Double,
        source: RecordSource = RecordSource.Manual,
    ) =
        PersonalRecord(
            id = id,
            exerciseName = name,
            normalizedExerciseName = name.lowercase(),
            weightKg = weightKg,
            achievedOn = "2026-07-16",
            sourceType = source,
            sourceWorkoutLogId = if (source == RecordSource.Logged) "log-1" else null,
            createdAt = 1_000L,
        )
}
