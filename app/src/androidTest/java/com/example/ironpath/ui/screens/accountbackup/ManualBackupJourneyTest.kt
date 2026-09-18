package com.example.ironpath.ui.screens.accountbackup

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ironpath.MainActivity
import com.example.ironpath.data.backup.BackupSnapshotCodec
import com.example.ironpath.data.backup.ManualBackupCapture
import com.example.ironpath.data.backup.RemoteBackupArtifact
import com.example.ironpath.data.backup.RemoteBackupPublish
import com.example.ironpath.data.backup.RemoteBackupRead
import com.example.ironpath.data.backup.RemoteBackupStore
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.backup.validatedBundle
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.BackupCoordinator
import com.example.ironpath.domain.backup.BackupStatus
import com.example.ironpath.testutil.AccountFilesUnchangedRule
import com.example.ironpath.testutil.FakeAccountSessionAdapter
import com.example.ironpath.testutil.HiltTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import com.example.ironpath.testutil.TestDatabaseRegistry
import com.example.ironpath.ui.navigation.Route
import com.example.ironpath.ui.testing.TestTags
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ManualBackupJourneyTest {
    @get:Rule(order = 0) val accountFilesRule = AccountFilesUnchangedRule()
    @get:Rule(order = 1) val databaseRule = HiltTestDatabaseRule()
    @get:Rule(order = 2) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 3) val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var database: IronPathDatabase
    @Inject lateinit var local: RoomBackupStore
    @Inject lateinit var records: RecordRepository
    @Inject lateinit var remote: RemoteBackupStore
    @Inject lateinit var backup: BackupCoordinator
    @Inject lateinit var session: FakeAccountSessionAdapter

    @Before fun inject() = hiltRule.inject()

    @Test
    fun explicitBackupAndConflictSync_preserveCancelledPreviewAndPersistAcrossRecreation() {
        waitForText("CONTINUE ON THIS DEVICE")
        composeRule.onNodeWithText("CONTINUE ON THIS DEVICE").performScrollTo().performClick()
        waitForText("No workout plan yet")
        val original = seedLocalTrainingData()
        assertNull(original.metadata.ownerUid)
        assertNull(original.baseline)

        composeRule.onNodeWithContentDescription("Menu").performClick()
        composeRule.onNodeWithText("Back up your training data").performClick()
        waitForText("YOUR ACCOUNT")
        composeRule.onNodeWithText("SIGN IN WITH GOOGLE").performScrollTo().performClick()
        waitForAccountStatus("Data choice required")
        val accountId = requireNotNull(session.session).id
        assertUnchangedBeforeConfirmation(original, accountId)

        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().performClick()
        waitForText("MANUAL BACKUP PREVIEW")
        composeRule
            .onNodeWithText(
                "Workout logs: 1\nLogged exercises: 1\nLogged sets: 1\nPersonal records: 1"
            )
            .assertExists()
        assertUnchangedBeforeConfirmation(original, accountId)
        Espresso.pressBack()
        waitForText("YOUR ACCOUNT")
        waitForAccountStatus("Data choice required")
        assertEquals(accountId, session.session?.id)
        assertUnchangedBeforeConfirmation(original, accountId)

        composeRule.onNodeWithText("BACK UP NOW").performScrollTo().performClick()
        waitForText("MANUAL BACKUP PREVIEW")
        composeRule.onNodeWithText("CONFIRM MANUAL BACKUP").performScrollTo().performClick()
        waitForText("Manual backup complete in demo storage.")
        waitForAccountStatus("Signed in")
        val firstBackup = latest(accountId)
        val backedUp = runBlocking { local.capture() }
        assertEquals(1L, firstBackup.generation)
        assertEquals(accountId.opaqueValue, backedUp.metadata.ownerUid)
        assertEquals(original.bundle, backedUp.bundle)
        assertEquals(
            backedUp.metadata.localChangeRevision,
            backedUp.metadata.lastCompleteLocalRevision
        )
        assertEquals(firstBackup, backedUp.baseline)
        assertEquals(
            BackupStatus.UpToDate(firstBackup.summary.completedAtEpochMillis),
            backup.status.value
        )

        // HiltTestDatabaseRule uses an isolated on-disk Room database, not an in-memory fake.
        composeRule.activityRule.scenario.recreate()
        waitForText("YOUR ACCOUNT")
        waitForAccountStatus("Signed in")
        waitForText("LAST COMPLETE DEMO BACKUP")
        assertEquals(backedUp, runBlocking { local.capture() })
        assertEquals(firstBackup, latest(accountId))

        applyLocalChanges()
        waitForText("Local changes")
        val dirty = runBlocking { local.capture() }
        assertTrue(dirty.metadata.localChangeRevision > dirty.metadata.lastCompleteLocalRevision)
        assertEquals(firstBackup, latest(accountId))

        val secondInstallationBackup = publishSecondInstallationChanges(accountId, firstBackup)
        assertEquals(2L, secondInstallationBackup.generation)
        assertNotEquals(
            backedUp.metadata.installationId,
            secondInstallationBackup.summary.sourceInstallationId
        )
        assertEquals(dirty, runBlocking { local.capture() })

        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().performClick()
        waitForText("MANUAL SYNC PREVIEW")
        composeRule
            .onNode(hasText("LOCAL CHANGES") and hasText("Personal records: 2"))
            .assertExists()
        composeRule
            .onNode(hasText("CLOUD CHANGES") and hasText("Personal records: 2"))
            .assertExists()
        composeRule
            .onNode(hasText("CONFLICTING RECORDS") and hasText("Personal records: 1"))
            .assertExists()
        composeRule
            .onNodeWithText("Merge and keep local conflict versions")
            .performScrollTo()
            .assertIsNotSelected()
        composeRule
            .onNodeWithText("Overwrite this device from cloud")
            .performScrollTo()
            .assertIsNotSelected()
        composeRule.onNodeWithText("CONFIRM MANUAL SYNC").performScrollTo().assertIsNotEnabled()
        assertEquals(dirty, runBlocking { local.capture() })
        assertEquals(secondInstallationBackup, latest(accountId))

        // Both Back mechanisms cancel only the preview and retain the account and local edits.
        composeRule.onNodeWithContentDescription("Back").performClick()
        waitForText("YOUR ACCOUNT")
        assertEquals(accountId, session.session?.id)
        assertEquals(dirty, runBlocking { local.capture() })
        assertEquals(secondInstallationBackup, latest(accountId))
        composeRule.onNodeWithText("REVIEW MANUAL SYNC").performScrollTo().performClick()
        waitForText("MANUAL SYNC PREVIEW")
        composeRule
            .onNodeWithText("Merge and keep local conflict versions")
            .performScrollTo()
            .assertIsNotSelected()
        composeRule
            .onNodeWithText("Overwrite this device from cloud")
            .performScrollTo()
            .assertIsNotSelected()
            .performClick()
        composeRule.onNodeWithText("Overwrite this device from cloud").assertIsSelected()
        composeRule.onNodeWithText("Merge and keep local conflict versions").assertIsNotSelected()
        composeRule
            .onNodeWithText("CONFIRM MANUAL SYNC")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        waitForText("Manual sync complete. Your training data now reflects the confirmed choice.")
        waitForAccountStatus("Signed in")
        composeRule.onNodeWithText("Manual backup complete in demo storage.").assertDoesNotExist()
        val merged = runBlocking { local.capture() }
        val mergedRemote = latest(accountId)
        assertEquals(3L, mergedRemote.generation)
        assertEquals(accountId.opaqueValue, merged.metadata.ownerUid)
        assertEquals(dirty.metadata.localChangeRevision + 1, merged.metadata.localChangeRevision)
        assertEquals(merged.metadata.localChangeRevision, merged.metadata.lastCompleteLocalRevision)
        assertEquals(mergedRemote, merged.baseline)
        assertEquals(
            mergedRemote.snapshot.contentDigest,
            BackupSnapshotCodec().encode(merged.bundle).contentDigest
        )
        assertEquals(
            setOf(SHARED_RECORD_ID, LOCAL_RECORD_ID, CLOUD_RECORD_ID),
            merged.bundle.personalRecords.map { it.id }.toSet()
        )
        assertEquals(
            CLOUD_WEIGHT,
            merged.bundle.personalRecords.single { it.id == SHARED_RECORD_ID }.weightKg,
            0.0
        )
        assertEquals(
            localRecord(),
            merged.bundle.personalRecords.single { it.id == LOCAL_RECORD_ID }
        )
        assertEquals(
            cloudRecord(),
            merged.bundle.personalRecords.single { it.id == CLOUD_RECORD_ID }
        )
        assertEquals(original.bundle.workoutLogs, merged.bundle.workoutLogs)
        assertEquals(original.bundle.loggedExercises, merged.bundle.loggedExercises)
        assertEquals(original.bundle.loggedSets, merged.bundle.loggedSets)

        Espresso.pressBack()
        waitForText("No workout plan yet")
        composeRule.onNodeWithTag(TestTags.bottomNav(Route.HISTORY)).performClick()
        composeRule.onNodeWithTag(TestTags.historyTab("Records")).performClick()
        listOf(SHARED_RECORD_ID, LOCAL_RECORD_ID, CLOUD_RECORD_ID).forEach { id ->
            composeRule.onNodeWithTag(TestTags.record(id)).performScrollTo().assertIsDisplayed()
        }
        composeRule
            .onNode(
                hasText("120 kg") and hasAnyAncestor(hasTestTag(TestTags.record(SHARED_RECORD_ID)))
            )
            .assertExists()

        // Reopen the file and reconstruct the persistence owner after the Activity is gone.
        composeRule.activityRule.scenario.close()
        val reopened =
            TestDatabaseRegistry.reopen(InstrumentationRegistry.getInstrumentation().targetContext)
        val reconstructed = runBlocking {
            RoomBackupStore(reopened, SequenceIdProvider("unused-reconstruction")).capture()
        }
        assertEquals(merged, reconstructed)
        assertNotNull(reconstructed.baseline)
        assertEquals(mergedRemote, latest(accountId))
    }

    private fun seedLocalTrainingData(): ManualBackupCapture = runBlocking {
        database.withTransaction {
            database.historyDao().insertLog(TestData.log(id = LOG_ID, workoutId = null))
            database
                .historyDao()
                .insertLoggedExercises(
                    listOf(TestData.loggedExercise(id = LOGGED_EXERCISE_ID, logId = LOG_ID))
                )
            database
                .historyDao()
                .insertLoggedSets(
                    listOf(
                        TestData.loggedSet(
                            id = "manual-journey-set",
                            exerciseId = LOGGED_EXERCISE_ID,
                            reps = 5,
                            weightKg = 100.0,
                            completedAt = TestData.BASE_TIME
                        )
                    )
                )
            records.insertRecord(TestData.record(id = SHARED_RECORD_ID, weightKg = 100.0))
        }
        local.capture()
    }

    private fun applyLocalChanges() = runBlocking {
        database.withTransaction {
            // Seed an edit to the same stable record on this installation; the repository's
            // additional record also advances the included-data revision in this transaction.
            database.openHelper.writableDatabase.execSQL(
                "UPDATE personal_records SET weightKg = ? WHERE id = ?",
                arrayOf<Any>(110.0, SHARED_RECORD_ID)
            )
            records.insertRecord(localRecord())
        }
    }

    private fun publishSecondInstallationChanges(
        accountId: AccountId,
        first: RemoteBackupArtifact
    ): RemoteBackupArtifact = runBlocking {
        val baseline = first.validatedBundle()
        val cloud =
            baseline.copy(
                localChangeRevision = baseline.localChangeRevision + 1,
                personalRecords =
                    baseline.personalRecords.map { it.copy(weightKg = CLOUD_WEIGHT) } +
                        cloudRecord()
            )
        val result =
            remote.publish(
                accountId,
                first.generation,
                "isolated-second-installation",
                BackupSnapshotCodec().encode(cloud)
            )
        assertTrue(
            "Second installation fixture must publish through the isolated real remote adapter",
            result is RemoteBackupPublish.Completed
        )
        (result as RemoteBackupPublish.Completed).backup
    }

    private fun assertUnchangedBeforeConfirmation(
        original: ManualBackupCapture,
        accountId: AccountId
    ) {
        runBlocking {
            assertEquals(original, local.capture())
            assertEquals(RemoteBackupRead.Absent(), remote.latest(accountId))
        }
    }

    private fun latest(accountId: AccountId): RemoteBackupArtifact = runBlocking {
        val result = remote.latest(accountId)
        assertTrue("Expected a complete isolated demo backup", result is RemoteBackupRead.Complete)
        (result as RemoteBackupRead.Complete).backup
    }

    private fun waitForText(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    private fun waitForAccountStatus(status: String) {
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithTag(TestTags.ACCOUNT_STATUS)
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.config
                ?.getOrNull(SemanticsProperties.StateDescription) == status
        }
        composeRule.onNodeWithTag(TestTags.ACCOUNT_STATUS).performScrollTo().assertIsDisplayed()
    }

    private fun localRecord() =
        TestData.record(
            id = LOCAL_RECORD_ID,
            exerciseName = "Bench Press",
            normalizedExerciseName = "bench press",
            weightKg = 80.0
        )

    private fun cloudRecord() =
        TestData.record(
            id = CLOUD_RECORD_ID,
            exerciseName = "Squat",
            normalizedExerciseName = "squat",
            weightKg = 90.0
        )

    private companion object {
        const val SHARED_RECORD_ID = "manual-journey-shared-record"
        const val LOCAL_RECORD_ID = "manual-journey-local-record"
        const val CLOUD_RECORD_ID = "manual-journey-cloud-record"
        const val LOG_ID = "manual-journey-log"
        const val LOGGED_EXERCISE_ID = "manual-journey-exercise"
        const val CLOUD_WEIGHT = 120.0
    }
}
