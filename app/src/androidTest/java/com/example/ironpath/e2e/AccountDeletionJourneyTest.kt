package com.example.ironpath.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.MainActivity
import com.example.ironpath.data.backup.DebugBackupDirectory
import com.example.ironpath.data.backup.RemoteBackupRead
import com.example.ironpath.data.backup.RemoteBackupStore
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.AccountDeletionJournal
import com.example.ironpath.domain.account.AccountDeletionStage
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.account.AccountProfile
import com.example.ironpath.testutil.AccountFilesUnchangedRule
import com.example.ironpath.testutil.FakeAccountSessionAdapter
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.TestData
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AccountDeletionJourneyTest {
    @get:Rule(order = 0) val accountFilesRule = AccountFilesUnchangedRule()
    @get:Rule(order = 1) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 2) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 3) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: IronPathDatabase
    @Inject lateinit var remote: RemoteBackupStore
    @Inject lateinit var session: FakeAccountSessionAdapter
    @Inject @DebugBackupDirectory lateinit var remoteDirectory: File

    @Before fun inject() = hiltRule.inject()

    @Test
    fun pendingDeletionSurvivesActivityRecreation_retryCompletesAndReturnsHome() {
        waitForText("CONTINUE ON THIS DEVICE")
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")

        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("No workout logs yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).performClick()
        waitForText("No workout plan yet")

        val accountId = AccountId("test-athlete")
        val profile = AccountProfile(accountId, "Test Athlete", "test@example.invalid")
        session.session = profile
        session.failDemoAccountDeletion = true
        val initialMetadata = runBlocking { requireNotNull(database.backupDao().getMetadata()) }
        runBlocking {
            database
                .backupDao()
                .updateMetadata(
                    initialMetadata.copy(
                        ownerUid = accountId.opaqueValue,
                        localChangeRevision = 1,
                    )
                )
            database
                .backupDao()
                .insertWorkoutLogs(listOf(TestData.log(id = "pending-deletion-journey-log")))
            database
                .accountDeletionDao()
                .save(
                    AccountDeletionJournal(
                        operationId = "pending-deletion-journey",
                        accountId = accountId.opaqueValue,
                        sessionEpoch = 0,
                        profileGeneration = 0,
                        stage = AccountDeletionStage.PREPARED.name,
                        createdAtEpochMillis = 1,
                    )
                )
        }
        val corruptRemoteState = File(remoteDirectory, "${digest(accountId.opaqueValue)}.json")
        assertTrue(remoteDirectory.isDirectory || remoteDirectory.mkdirs())
        corruptRemoteState.writeText("not a valid backup index")

        composeRule.activityRule.scenario.recreate()
        waitForText("Finishing account deletion")
        composeRule.onNodeWithText("RETRY DELETION").assertIsDisplayed()
        assertEquals(accountId, session.session?.id)
        assertEquals(
            listOf("pending-deletion-journey-log"),
            runBlocking { database.backupDao().getWorkoutLogs().map { it.id } },
        )
        assertTrue(runBlocking { remote.latest(accountId) is RemoteBackupRead.Absent })

        session.failDemoAccountDeletion = false
        composeRule.onNodeWithText("RETRY DELETION").performClick()
        waitForText("No workout plan yet", timeoutMillis = 10_000)

        runBlocking {
            assertNull(session.readSession())
            assertTrue(database.backupDao().getWorkoutLogs().isEmpty())
            assertNull(database.backupDao().getMetadata()?.ownerUid)
            assertEquals(1L, database.backupDao().getMetadata()?.profileGeneration)
            assertEquals(
                AccountDeletionStage.COMPLETE.name,
                database.accountDeletionDao().getJournal()?.stage,
            )
        }
        assertTrue(runBlocking { remote.latest(accountId) is RemoteBackupRead.Absent })

        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsSelected()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("No workout logs yet")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("No records yet")
        composeRule.onNodeWithText("ADD RECORD").performScrollTo().performClick()
        waitForText("ADD RECORD")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .performTextReplacement("Post-recovery squat")
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .performTextReplacement("100")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
        waitForText("Post-recovery squat")
        assertEquals(
            "Post-recovery squat",
            runBlocking { database.backupDao().getPersonalRecords().single().exerciseName },
        )
    }

    @Test
    fun signedInDemoCanDeleteUnclaimedLocalTrainingData() {
        waitForText("CONTINUE ON THIS DEVICE")
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("No workout logs yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).performClick()
        waitForText("No workout plan yet")

        val accountId = AccountId("test-athlete")
        val profile = AccountProfile(accountId, "Test Athlete", "test@example.invalid")
        runBlocking {
            database
                .backupDao()
                .insertWorkoutLogs(listOf(TestData.log(id = "unclaimed-deletion-log")))
        }
        assertNull(runBlocking { database.backupDao().getMetadata()?.ownerUid })

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Back up your training data").performClick()
        waitForText("YOUR ACCOUNT")
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").performScrollTo().performClick()
        waitForAnyText("Data choice required")
        assertEquals(profile, session.session)
        composeRule.onNodeWithText("DELETE ACCOUNT").performScrollTo().performClick()
        waitForText("Delete account and data?")
        composeRule.onNodeWithText("CONTINUE").performClick()
        waitForText("Confirm account deletion")
        composeRule
            .onNodeWithText("all unclaimed local training data", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText("DELETE ACCOUNT AND ALL DATA").performClick()
        waitForText("No workout plan yet", timeoutMillis = 10_000)

        runBlocking {
            assertNull(session.readSession())
            assertTrue(database.backupDao().getWorkoutLogs().isEmpty())
            assertNull(database.backupDao().getMetadata()?.ownerUid)
            assertEquals(1L, database.backupDao().getMetadata()?.profileGeneration)
            assertEquals(
                AccountDeletionStage.COMPLETE.name,
                database.accountDeletionDao().getJournal()?.stage,
            )
        }
        assertTrue(runBlocking { remote.latest(accountId) is RemoteBackupRead.Absent })

        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsSelected()
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("No workout logs yet")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("No records yet")
        composeRule.onNodeWithText("ADD RECORD").performClick()
        waitForText("ADD RECORD")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .performTextReplacement("Post-delete squat")
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .performTextReplacement("100")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
        waitForText("Post-delete squat")
        assertEquals(
            "Post-delete squat",
            runBlocking { database.backupDao().getPersonalRecords().single().exerciseName },
        )
    }

    private fun waitForText(text: String, timeoutMillis: Long = 5_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun waitForAnyText(text: String, timeoutMillis: Long = 5_000) {
        composeRule.waitUntil(timeoutMillis) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(text).onFirst().assertIsDisplayed()
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
