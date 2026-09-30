package com.example.ironpath.e2e

import androidx.compose.ui.test.assertDoesNotExist
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
import com.example.ironpath.testutil.FakeOnboardingRepository
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.TestAccountContextReader
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
    @Inject lateinit var onboarding: FakeOnboardingRepository
    @Inject lateinit var accountContextReader: TestAccountContextReader
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

        composeRule.activityRule.scenario.recreate()
        waitForText("Post-recovery squat", timeoutMillis = 10_000)
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).assertIsSelected()
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("Post-recovery squat")
        composeRule.onNodeWithText("ADD RECORD").performScrollTo().performClick()
        waitForText("ADD RECORD")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .performTextReplacement("Post-second-recovery squat")
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .performTextReplacement("110")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
        waitForText("Post-second-recovery squat")
        assertEquals(
            setOf("Post-recovery squat", "Post-second-recovery squat"),
            runBlocking {
                database.backupDao().getPersonalRecords().map { it.exerciseName }.toSet()
            },
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

        composeRule.activityRule.scenario.recreate()
        waitForText("Post-delete squat", timeoutMillis = 10_000)
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).assertIsSelected()
        assertEquals(1L, runBlocking { database.backupDao().getMetadata()?.profileGeneration })
        composeRule.onNodeWithText("ADD RECORD").performScrollTo().performClick()
        waitForText("ADD RECORD")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .performTextReplacement("Post-recreate squat")
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .performTextReplacement("105")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
        waitForText("Post-recreate squat")
        assertEquals(
            setOf("Post-delete squat", "Post-recreate squat"),
            runBlocking {
                database.backupDao().getPersonalRecords().map { it.exerciseName }.toSet()
            },
        )
        assertEquals(1L, runBlocking { database.backupDao().getMetadata()?.profileGeneration })
    }

    @Test
    fun committedRemoveDataSignOutRetryAfterRecreation_keepsTheCommittedGeneration() {
        waitForText("CONTINUE ON THIS DEVICE")
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")

        val accountId = AccountId("test-athlete")
        val profile = AccountProfile(accountId, "Test Athlete", "test@example.invalid")
        runBlocking {
            val metadata = requireNotNull(database.backupDao().getMetadata())
            database.backupDao().updateMetadata(metadata.copy(ownerUid = accountId.opaqueValue))
            database
                .backupDao()
                .insertWorkoutLogs(listOf(TestData.log(id = "sign-out-removal-log")))
        }

        openAccountBackup()
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").performScrollTo().performClick()
        waitForText("SIGN OUT")
        assertEquals(profile, session.session)

        session.failClearSession = true
        composeRule.onNodeWithText("SIGN OUT").performScrollTo().performClick()
        composeRule.onNodeWithText("Remove data from this device").performClick()
        composeRule.onNodeWithText("CONTINUE").performClick()
        composeRule.onNodeWithText("REMOVE DATA AND SIGN OUT").performClick()
        waitForText("No workout plan yet", timeoutMillis = 10_000)
        assertEquals(profile, session.session)
        runBlocking {
            assertEquals(1L, database.backupDao().getMetadata()?.profileGeneration)
            assertEquals(
                accountId.opaqueValue,
                database.backupDao().getMetadata()?.pendingSignOutUid,
            )
            assertTrue(database.backupDao().getWorkoutLogs().isEmpty())
        }

        composeRule.activityRule.scenario.recreate()
        waitForText("No workout plan yet", timeoutMillis = 10_000)
        assertEquals(1L, runBlocking { database.backupDao().getMetadata()?.profileGeneration })
        openAccountBackup()
        waitForText("FINISH SIGN OUT")

        session.failClearSession = false
        composeRule.onNodeWithText("FINISH SIGN OUT").performClick()
        waitForText("SIGN IN WITH GOOGLE")
        assertNull(session.session)
        runBlocking {
            assertEquals(1L, database.backupDao().getMetadata()?.profileGeneration)
            assertNull(database.backupDao().getMetadata()?.pendingSignOutUid)
            assertNull(database.backupDao().getMetadata()?.ownerUid)
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("No workout plan yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("No workout logs yet")
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("No records yet")
        composeRule.onNodeWithText("ADD RECORD").performScrollTo().performClick()
        waitForText("ADD RECORD")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .performTextReplacement("Post-sign-out squat")
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .performTextReplacement("90")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
        waitForText("Post-sign-out squat")
        assertEquals(1L, runBlocking { database.backupDao().getMetadata()?.profileGeneration })

        composeRule.activityRule.scenario.recreate()
        waitForText("Post-sign-out squat", timeoutMillis = 10_000)
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).assertIsSelected()
        composeRule.onNodeWithText("RECORDS").performClick()
        waitForText("Post-sign-out squat")
        composeRule.onNodeWithText("ADD RECORD").performScrollTo().performClick()
        waitForText("ADD RECORD")
        composeRule
            .onNodeWithTag(TestTags.RECORD_NAME)
            .performScrollTo()
            .performTextReplacement("Post-sign-out-recreate squat")
        composeRule
            .onNodeWithTag(TestTags.RECORD_WEIGHT)
            .performScrollTo()
            .performTextReplacement("95")
        composeRule.onNodeWithText("SAVE").performScrollTo().performClick()
        waitForText("Post-sign-out-recreate squat")
        assertEquals(
            setOf("Post-sign-out squat", "Post-sign-out-recreate squat"),
            runBlocking {
                database.backupDao().getPersonalRecords().map { it.exerciseName }.toSet()
            },
        )
        assertEquals(1L, runBlocking { database.backupDao().getMetadata()?.profileGeneration })
    }

    @Test
    fun onboardingReadFailureKeepsAppClosedUntilRetrySucceeds() {
        waitForText("CONTINUE ON THIS DEVICE")
        onboarding.failReads = true
        composeRule.activityRule.scenario.recreate()

        waitForText("Local profile unavailable", timeoutMillis = 10_000)
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
        composeRule.onNodeWithText("No workout plan yet").assertDoesNotExist()

        onboarding.failReads = false
        composeRule.onNodeWithText("RETRY").performClick()
        waitForText("CONTINUE ON THIS DEVICE")
    }

    @Test
    fun accountContextChangesFailure_keepsAppClosedUntilRetryRestartsObservation() {
        waitForText("CONTINUE ON THIS DEVICE")
        accountContextReader.failChanges = true
        composeRule.activityRule.scenario.recreate()

        waitForText("Local profile unavailable", timeoutMillis = 10_000)
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").assertDoesNotExist()
        composeRule.onNodeWithText("No workout plan yet").assertDoesNotExist()

        accountContextReader.failChanges = false
        composeRule.onNodeWithText("RETRY").performClick()
        waitForText("CONTINUE ON THIS DEVICE", timeoutMillis = 10_000)
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        waitForText("No workout logs yet")

        runBlocking {
            val metadata = requireNotNull(database.backupDao().getMetadata())
            database.backupDao().updateMetadata(metadata.copy(profileGeneration = 1))
        }

        waitForText("No workout plan yet", timeoutMillis = 10_000)
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HOME)).assertIsSelected()
    }

    @Test
    fun devToolsClearAllData_returnsToEntryAfterActivityRecreation() {
        waitForText("CONTINUE ON THIS DEVICE")
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")

        repeat(5) { composeRule.onNodeWithText("IRONPATH").performClick() }
        waitForText("DEV TOOLS")
        composeRule.onNodeWithText("Clear All Data").performScrollTo().performClick()
        waitForText("Clear all data?")
        composeRule.onNodeWithText("Clear").performClick()
        waitForText("CONTINUE ON THIS DEVICE", timeoutMillis = 10_000)
        runBlocking { assertEquals(1L, database.backupDao().getMetadata()?.profileGeneration) }

        composeRule.activityRule.scenario.recreate()
        waitForText("CONTINUE ON THIS DEVICE", timeoutMillis = 10_000)
        composeRule.onNodeWithText("No workout plan yet").assertDoesNotExist()
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

    private fun openAccountBackup() {
        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Back up your training data").performClick()
        waitForText("YOUR ACCOUNT")
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
