package com.example.ironpath.accessibility

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.example.ironpath.data.local.entity.*
import com.example.ironpath.data.repository.*
import com.example.ironpath.ui.screens.history.*
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordsAccessibilityTest {
    @get:Rule val composeRule = createComposeRule()
    private val record =
        PersonalRecord(
            "r",
            "Deadlift",
            "deadlift",
            180.5,
            "2026-07-16",
            note = "Original",
            createdAt = 1L
        )

    @Test
    fun editAt200Percent_prefillsAndValidatesAndConfirmsDelete() {
        var deletes = 0
        var saves = 0
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    AddRecordScreen(
                        emptyList(),
                        LocalDate.parse("2026-07-16"),
                        { saves++ },
                        {},
                        record = record,
                        onDelete = { deletes++ }
                    )
                }
            }
        }
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .assertTextEquals("Deadlift")
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .assertTextEquals("180.5")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NOTE)
            .performScrollTo()
            .assertTextEquals("Original")
        composeRule
            .onNodeWithTag(TestTags.RECORD_DATE)
            .performScrollTo()
            .performTextReplacement("2027-01-01")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
        composeRule
            .onNodeWithText("Date cannot be in the future")
            .performScrollTo()
            .assertIsDisplayed()
        assertEquals(0, saves)
        composeRule
            .onNodeWithText("DELETE RECORD")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        composeRule.onNodeWithText("Delete this record?").assertIsDisplayed()
        composeRule.onNodeWithText("This cannot be undone.").assertIsDisplayed()
        composeRule.onNodeWithTag("record_delete_cancel").performClick()
        assertEquals(0, deletes)
        composeRule.onNodeWithText("DELETE RECORD").performScrollTo().performClick()
        composeRule.onNodeWithTag("record_delete_confirm").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun derivedSaveAt200Percent_remainsReachableAndIdentifiesSet() {
        val set = LoggedSet("set", "ex", 1, 8, 62.5)
        val detail =
            WorkoutLogDetail(
                WorkoutLog(
                    "log",
                    title = "Push",
                    startedAt = 1L,
                    completedAt = 2L,
                    durationMinutes = 1,
                    exerciseCount = 1
                ),
                listOf(
                    LoggedExerciseDetail(
                        LoggedExercise("ex", "log", "Bench Press", 1, 8, 60.0, 0),
                        listOf(set)
                    )
                )
            )
        var saved: String? = null
        composeRule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(640.dp, 320.dp)) then
                    DeviceConfigurationOverride.FontScale(2f)
            ) {
                IronPathTheme {
                    WorkoutLogDetailContent(
                        WorkoutLogDetailUiState.Ready(detail),
                        {},
                        ZoneOffset.UTC,
                        onSaveRecord = { saved = it }
                    )
                }
            }
        }
        composeRule
            .onNodeWithTag("save_record_set")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertIsEnabled()
            .performClick()
        assertEquals("set", saved)
    }
}
