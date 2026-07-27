package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RecordPersistenceJourneyTest {
    @get:Rule(order = 0) val databaseRule = HiltTestDatabaseRule()

    @get:Rule(order = 1) val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 2) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: IronPathDatabase

    @Before
    fun inject() {
        hiltRule.inject()
    }

    @Test
    fun recordJourney_normalizesPersistsAndRejectsAnExactDuplicate() {
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertIsDisplayed().performClick()
        waitForText("No workout plan yet")

        clickNavigationDestination(Route.HISTORY)
        waitForText("No workout logs yet")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("No records yet")
        composeRule.onNodeWithText("ADD RECORD").performClick()
        waitForText("ADD RECORD")

        fillRecordForm(
            exerciseName = "  Deadlift  ",
            note = "Smooth lockout",
        )
        saveRecord()

        waitForText("Deadlift")
        assertRecordIsDisplayed()
        val storedRecord = runBlocking { database.recordDao().observeAllRecords().first().single() }
        assertEquals("Deadlift", storedRecord.exerciseName)
        assertEquals("deadlift", storedRecord.normalizedExerciseName)
        assertEquals(180.5, storedRecord.weightKg, 0.0)
        assertEquals(FIXED_DATE, storedRecord.achievedOn)
        assertEquals("Smooth lockout", storedRecord.note)
        assertEquals(RecordSource.Manual, storedRecord.sourceType)

        composeRule.activityRule.scenario.recreate()
        waitForText("RECORDS")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("Deadlift")
        assertRecordIsDisplayed()

        composeRule.onNodeWithText("ADD NEW RECORD").performScrollTo().performClick()
        waitForText("ADD RECORD")
        fillRecordForm(
            exerciseName = "  DEADLIFT  ",
            note = "Duplicate attempt",
        )
        saveRecord()

        waitForText(DUPLICATE_MESSAGE)
        composeRule.onNodeWithText(DUPLICATE_MESSAGE).performScrollTo().assertIsDisplayed()
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals("  DEADLIFT  ")
        composeRule.onNodeWithText("ADD RECORD").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TestTags.RECORD_WEIGHT).assertTextContains("180.5")
        composeRule.onNodeWithTag(TestTags.RECORD_DATE).assertTextContains(FIXED_DATE)
        composeRule.onNodeWithTag(TestTags.RECORD_NOTE).assertTextContains("Duplicate attempt")

        val recordsAfterDuplicate = runBlocking { database.recordDao().observeAllRecords().first() }
        assertEquals(1, recordsAfterDuplicate.size)
        assertEquals(storedRecord, recordsAfterDuplicate.single())
    }

    private fun fillRecordForm(exerciseName: String, note: String) {
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .performTextReplacement(exerciseName)
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .performTextReplacement("180.5")
        composeRule
            .onNodeWithTag(TestTags.RECORD_DATE)
            .performScrollTo()
            .performTextReplacement(FIXED_DATE)
        composeRule
            .onNodeWithTag(TestTags.RECORD_NOTE)
            .performScrollTo()
            .performTextReplacement(note)
    }

    private fun saveRecord() {
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
    }

    private fun assertRecordIsDisplayed() {
        composeRule.onNodeWithText("Deadlift").assertIsDisplayed()
        composeRule.onNodeWithText(FIXED_DATE).assertIsDisplayed()
        composeRule.onNodeWithText("MANUAL").assertIsDisplayed()
        composeRule.onNodeWithText("180.5 kg").assertIsDisplayed()
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun clickNavigationDestination(route: String) {
        val tag = TestTags.bottomNav(route)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(tag).performClick()
    }

    private companion object {
        const val FIXED_DATE = "2026-07-13"
        const val DUPLICATE_MESSAGE =
            "A record with this exercise, date, and weight already exists."
    }
}
