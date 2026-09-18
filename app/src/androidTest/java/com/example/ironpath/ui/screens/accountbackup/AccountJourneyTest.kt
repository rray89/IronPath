package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.testutil.AccountFilesUnchangedRule
import com.example.ironpath.testutil.FakeAccountSessionAdapter
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.TestData
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccountJourneyTest {
    @get:Rule(order = 0) val accountFilesRule = AccountFilesUnchangedRule()
    @get:Rule(order = 1) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 2) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 3) val composeRule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var database: IronPathDatabase
    @Inject lateinit var session: FakeAccountSessionAdapter

    @Before fun inject() = hiltRule.inject()

    @Test
    fun localWorkoutDataAndPendingIdentity_surviveRecreationUntilExplicitCancel() {
        waitForText("CONTINUE ON THIS DEVICE")
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")
        val before = runBlocking {
            database.historyDao().insertLog(TestData.log(id = "account-journey-log"))
            database.backupDao().getMetadata()
        }
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Back up your training data").performClick()
        waitForText("YOUR ACCOUNT")
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").performScrollTo().performClick()
        waitForPendingChoice()
        assertNotNull(session.session)

        composeRule.activityRule.scenario.recreate()

        waitForPendingChoice()
        assertNotNull(session.session)
        Espresso.pressBack()
        waitForText("No workout plan yet")
        assertNull(session.session)
        runBlocking {
            assertEquals(before, database.backupDao().getMetadata())
            assertEquals(
                listOf("account-journey-log"),
                database.backupDao().getWorkoutLogs().map { it.id }
            )
        }
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForPendingChoice() {
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithTag(TestTags.ACCOUNT_STATUS)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.StateDescription) == "Data choice required"
        }
        composeRule.onNodeWithTag(TestTags.ACCOUNT_STATUS).assertIsDisplayed()
    }
}
