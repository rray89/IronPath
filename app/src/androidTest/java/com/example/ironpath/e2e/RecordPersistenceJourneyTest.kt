package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithTag(TestTags.ENTRY_GET_STARTED)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule
            .onNodeWithTag(TestTags.ENTRY_GET_STARTED)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
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

    @Test
    fun manualRecord_editCancelUnchangedSaveRecreateAndConfirmedDelete() {
        enterApp()
        clickNavigationDestination(Route.HISTORY)
        composeRule.onNodeWithText("RECORDS").performClick()
        composeRule.onNodeWithText("ADD RECORD").performClick()
        fillRecordForm("Deadlift", "Original")
        saveRecord()
        waitForText("Deadlift")
        val original = runBlocking { database.recordDao().observeAllRecords().first().single() }
        composeRule.onNodeWithTag(TestTags.record(original.id)).performClick()
        waitForText("EDIT RECORD")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .assertTextEquals("Deadlift")
            .performTextReplacement("Canceled name")
        composeRule.onNodeWithText("CANCEL").performScrollTo().performClick()
        assertEquals(original, runBlocking { database.recordDao().getRecordById(original.id) })
        composeRule.onNodeWithTag(TestTags.record(original.id)).performClick()
        saveRecord()
        waitForText("Deadlift")
        composeRule.onNodeWithTag(TestTags.record(original.id)).performClick()
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performTextReplacement("  Romanian Deadlift  ")
        saveRecord()
        waitForText("Romanian Deadlift")
        val edited = runBlocking { database.recordDao().getRecordById(original.id)!! }
        assertEquals("romanian deadlift", edited.normalizedExerciseName)
        assertEquals(original.createdAt, edited.createdAt)
        composeRule.activityRule.scenario.recreate()
        waitForText("RECORDS")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("Romanian Deadlift")
        composeRule.onNodeWithTag(TestTags.record(original.id)).performClick()
        composeRule.onNodeWithText("DELETE RECORD").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete this record?").assertIsDisplayed()
        composeRule.onNodeWithTag("record_delete_cancel").performClick()
        assertEquals(edited, runBlocking { database.recordDao().getRecordById(original.id) })
        composeRule.onNodeWithText("DELETE RECORD").performScrollTo().performClick()
        composeRule.onNodeWithTag("record_delete_confirm").performClick()
        waitForText("No records yet")
        assertNull(runBlocking { database.recordDao().getRecordById(original.id) })
    }

    @Test
    fun completedLog_saveRecreateListAndReadOnlySource_roundTrip() {
        runBlocking {
            database
                .historyDao()
                .insertLog(
                    WorkoutLog(
                        "derived-log",
                        title = "Record Journey",
                        startedAt = 1L,
                        completedAt = 1784232000000L,
                        durationMinutes = 1,
                        exerciseCount = 1
                    )
                )
            database
                .historyDao()
                .insertLoggedExercises(
                    listOf(
                        LoggedExercise("derived-ex", "derived-log", "Bench Press", 2, 8, 60.0, 0)
                    )
                )
            database
                .historyDao()
                .insertLoggedSets(
                    listOf(
                        LoggedSet("derived-set", "derived-ex", 1, 8, 62.5),
                        LoggedSet("blank-set", "derived-ex", 2)
                    )
                )
        }
        enterApp()
        clickNavigationDestination(Route.HISTORY)
        waitForText("Record Journey")
        composeRule.onNodeWithTag(TestTags.log("derived-log")).performClick()
        waitForText("Bench Press")
        composeRule.onNodeWithTag("save_record_blank-set").assertDoesNotExist()
        composeRule.onNodeWithTag("save_record_derived-set").performScrollTo().performClick()
        waitForText("SAVED")
        val stored = runBlocking { database.recordDao().observeAllRecords().first().single() }
        assertEquals(RecordSource.Logged, stored.sourceType)
        assertEquals("derived-log", stored.sourceWorkoutLogId)
        composeRule.activityRule.scenario.recreate()
        waitForText("SAVED")
        composeRule.onNodeWithTag("save_record_derived-set").performScrollTo().assertIsNotEnabled()
        goBack()
        waitForText("RECORDS")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("LOGGED")
        composeRule.onNodeWithTag(TestTags.record(stored.id)).performClick()
        waitForText("Record Journey")
        composeRule.onNodeWithText("SAVE RECORD").assertDoesNotExist()
        composeRule.onNodeWithText("EDIT RECORD").assertDoesNotExist()
        goBack()
        waitForText("LOGGED")
        assertEquals(
            listOf(stored),
            runBlocking { database.recordDao().observeAllRecords().first() }
        )
        assertEquals(
            2,
            runBlocking {
                database.historyDao().getLoggedSetsForExercises(listOf("derived-ex")).size
            }
        )
    }

    @Test
    fun loggedMissingSources_showFallbackAndNeverManualEditor() {
        runBlocking {
            database
                .recordDao()
                .insertRecord(
                    PersonalRecord(
                        "null-source",
                        "No source",
                        "no source",
                        80.0,
                        FIXED_DATE,
                        sourceType = RecordSource.Logged,
                        createdAt = 1L
                    )
                )
            database
                .recordDao()
                .insertRecord(
                    PersonalRecord(
                        "dangling-source",
                        "Missing source",
                        "missing source",
                        90.0,
                        FIXED_DATE,
                        sourceType = RecordSource.Logged,
                        sourceWorkoutLogId = "missing",
                        createdAt = 2L
                    )
                )
        }
        enterApp()
        clickNavigationDestination(Route.HISTORY)
        composeRule.onNodeWithText("RECORDS").performClick()
        composeRule.onNodeWithTag(TestTags.record("null-source")).performClick()
        waitForText("Source workout unavailable")
        composeRule.onNodeWithText("EDIT RECORD").assertDoesNotExist()
        composeRule.onNodeWithText("OK").performClick()
        composeRule.onNodeWithTag(TestTags.record("dangling-source")).performClick()
        waitForText("LOG NOT FOUND")
        composeRule.onNodeWithText("GO BACK").performClick()
        waitForText("Personal Bests")
    }

    private fun goBack() {
        composeRule.runOnIdle { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun enterApp() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule
                .onAllNodesWithTag(TestTags.ENTRY_GET_STARTED)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag(TestTags.ENTRY_GET_STARTED).performScrollTo().performClick()
        waitForText("No workout plan yet")
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
