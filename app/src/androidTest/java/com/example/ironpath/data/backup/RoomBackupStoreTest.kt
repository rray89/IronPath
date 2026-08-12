package com.example.ironpath.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackupStoreTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    @Test
    fun export_readsOneConsistentIncludedGraphAndExcludesTheActiveSession() = runBlocking {
        val database = databaseRule.database
        val plan = TestData.plan()
        val workout = TestData.workout()
        val plannedExercise = TestData.plannedExercise()
        val session = TestData.session()
        val sessionExercise = TestData.sessionExercise()
        val log = TestData.log()
        val loggedExercise = TestData.loggedExercise()
        val loggedSet = TestData.loggedSet(reps = 5, weightKg = 102.5)
        val record = TestData.record(sourceWorkoutLogId = log.id)

        database.planDao().insertPlan(plan)
        database.planDao().insertWorkouts(listOf(workout))
        database.planDao().insertExercises(listOf(plannedExercise))
        database.sessionDao().startNewSession(session, listOf(sessionExercise))
        database.historyDao().insertLog(log)
        database.historyDao().insertLoggedExercises(listOf(loggedExercise))
        database.historyDao().insertLoggedSets(listOf(loggedSet))
        database.recordDao().insertRecord(record)

        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        store.markIncludedDataChanged()

        val bundle = store.export()

        assertEquals(1L, bundle.localChangeRevision)
        assertEquals(listOf(plan), bundle.weeklyPlans)
        assertEquals(listOf(workout), bundle.plannedWorkouts)
        assertEquals(listOf(plannedExercise), bundle.plannedExercises)
        assertEquals(listOf(log), bundle.workoutLogs)
        assertEquals(listOf(loggedExercise), bundle.loggedExercises)
        assertEquals(listOf(loggedSet), bundle.loggedSets)
        assertEquals(listOf(record), bundle.personalRecords)
        assertFalse(bundle.allStableIds().contains(session.id))
        assertFalse(bundle.allStableIds().contains(sessionExercise.id))
    }

    @Test
    fun restore_requiresExplicitActiveSessionDiscardThenAtomicallyReplacesIncludedData() =
        runBlocking {
            val database = databaseRule.database
            val oldPlan = TestData.plan(id = "old-plan")
            val oldSession = TestData.session(id = "old-session", workoutId = "old-workout")
            database.planDao().insertPlan(oldPlan)
            database.sessionDao().startNewSession(oldSession, emptyList())

            val restoredPlan =
                TestData.plan(
                    id = "restored-plan",
                    startDate = "2026-08-10",
                    endDate = "2026-08-16",
                )
            val restoredLog =
                TestData.log(id = "restored-log", workoutId = "missing-provenance-workout")
            val restoredRecord =
                TestData.record(
                    id = "restored-record",
                    sourceWorkoutLogId = "missing-provenance-log",
                )
            val bundle =
                BackupBundle(
                    localChangeRevision = Long.MAX_VALUE,
                    weeklyPlans = listOf(restoredPlan),
                    plannedWorkouts = emptyList(),
                    plannedExercises = emptyList(),
                    workoutLogs = listOf(restoredLog),
                    loggedExercises = emptyList(),
                    loggedSets = emptyList(),
                    personalRecords = listOf(restoredRecord),
                )
            val encoded = BackupSnapshotCodec().encode(bundle)
            val lineage =
                RestoreLineage(
                    ownerUid = "account-uid",
                    remoteBackupId = "backup-7",
                    remoteGeneration = 4,
                    remoteDigest = encoded.contentDigest,
                    sourceInstallationId = "source-installation",
                    completedAt = TestData.BASE_TIME,
                )
            val store = RoomBackupStore(database, SequenceIdProvider("installation"))
            val validated = BackupBundleValidator.validate(bundle)
            val artifact =
                ValidatedRestoreArtifact(
                    bundle = validated.bundle,
                    lineage = lineage,
                    contentDigest = encoded.contentDigest,
                    nulledProvenanceFields = validated.nulledProvenanceFields,
                )

            val blocked = store.restore(artifact, ActiveSessionRestoreDisposition.Preserve)

            assertEquals(
                RestoreResult.ActiveSessionRequiresConfirmation(oldSession.id),
                blocked,
            )
            assertEquals(oldPlan, database.planDao().getActivePlan())
            assertEquals(oldSession, database.sessionDao().getActiveSession())

            database.sessionDao().deleteSession(oldSession.id)
            val replacementSession =
                TestData.session(id = "replacement-session", workoutId = "replacement-workout")
            database.sessionDao().startNewSession(replacementSession, emptyList())
            val staleConfirmation =
                store.restore(
                    artifact,
                    ActiveSessionRestoreDisposition.Discard(oldSession.id),
                )
            assertEquals(
                RestoreResult.ActiveSessionRequiresConfirmation(replacementSession.id),
                staleConfirmation,
            )
            assertEquals(replacementSession, database.sessionDao().getActiveSession())

            val restored =
                store.restore(
                    artifact,
                    ActiveSessionRestoreDisposition.Discard(replacementSession.id),
                )

            assertTrue(restored is RestoreResult.Success)
            assertEquals(
                setOf("sourcePlannedWorkoutId", "sourceWorkoutLogId"),
                (restored as RestoreResult.Success).nulledProvenanceFields,
            )
            assertEquals(restoredPlan, database.planDao().getActivePlan())
            assertNull(database.sessionDao().getActiveSession())
            assertNull(database.historyDao().getLogById(restoredLog.id)?.sourcePlannedWorkoutId)
            assertNull(database.recordDao().getRecordById(restoredRecord.id)?.sourceWorkoutLogId)
            val metadata = checkNotNull(database.backupDao().getMetadata())
            assertEquals("account-uid", metadata.ownerUid)
            assertEquals(4L, metadata.lastObservedRemoteGeneration)
            assertEquals("backup-7", metadata.lastObservedRemoteBackupId)
            assertEquals(1L, metadata.localChangeRevision)
            assertEquals(0L, metadata.lastCompleteLocalRevision)
        }

    @Test
    fun includedProductTransactionsIncrementTheDurableRevisionExactlyOnce() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val planRepository = PlanRepository(database.planDao(), database, store)
        val recordRepository = RecordRepository(database.recordDao(), database, store)
        val sessionRepository =
            SessionRepository(
                database.sessionDao(),
                database.historyDao(),
                database.planDao(),
                database,
                PerformanceTracer(),
                store,
            )
        val plan = TestData.plan()
        val workout = TestData.workout()
        val plannedExercise = TestData.plannedExercise()

        planRepository.createPlan(plan, listOf(workout), listOf(plannedExercise))
        assertEquals(1L, checkNotNull(database.backupDao().getMetadata()).localChangeRevision)

        recordRepository.insertRecord(TestData.record())
        assertEquals(2L, checkNotNull(database.backupDao().getMetadata()).localChangeRevision)

        val session = TestData.session()
        val sessionExercise = TestData.sessionExercise()
        sessionRepository.startSession(session, listOf(sessionExercise))
        sessionRepository.completeSession(session.id, TestData.log())

        assertEquals(3L, checkNotNull(database.backupDao().getMetadata()).localChangeRevision)
    }

    @Test
    fun resetLocalProfile_clearsAllLocalGraphsOwnershipLineageAndRotatesInstallationId() =
        runBlocking {
            val database = databaseRule.database
            val ids = SequenceIdProvider("installation")
            val sentinel = FakeInstallationSentinel()
            val store = RoomBackupStore(database, ids, sentinel)
            val plan = TestData.plan()
            database.planDao().insertPlan(plan)
            database.sessionDao().startNewSession(TestData.session(), emptyList())
            database.sessionDao().insertSession(TestData.session(id = "session-b"))
            store.markIncludedDataChanged()
            val original = checkNotNull(database.backupDao().getMetadata())
            database
                .backupDao()
                .updateMetadata(
                    original.copy(
                        ownerUid = "owner-a",
                        lastCompleteLocalRevision = 1,
                        lastObservedRemoteBackupId = "backup-a",
                        lastObservedRemoteGeneration = 3,
                        lastObservedRemoteDigest = "digest-a",
                        lastObservedSourceInstallationId = "other-installation",
                        lastObservedRemoteCompletedAt = TestData.BASE_TIME,
                    )
                )

            store.resetLocalProfile()

            assertNull(database.planDao().getActivePlan())
            assertNull(database.sessionDao().getActiveSession())
            val reset = checkNotNull(database.backupDao().getMetadata())
            assertEquals("installation-2", reset.installationId)
            assertNull(reset.ownerUid)
            assertEquals(0L, reset.localChangeRevision)
            assertEquals(0L, reset.lastCompleteLocalRevision)
            assertNull(reset.lastObservedRemoteBackupId)
            assertEquals(0L, reset.lastObservedRemoteGeneration)
            assertNull(reset.lastObservedRemoteDigest)
            assertNull(reset.lastObservedSourceInstallationId)
            assertNull(reset.lastObservedRemoteCompletedAt)
            assertEquals(reset.installationId, sentinel.installationId)
            assertEquals(InstallationValidationResult.Validated, store.validateInstallation())
        }

    @Test
    fun restore_rollsBackDeletesActiveSessionAndMetadataWhenAnInsertFails() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val originalPlan = TestData.plan(id = "original-plan")
        val originalSession = TestData.session(id = "original-session")
        database.planDao().insertPlan(originalPlan)
        database.sessionDao().startNewSession(originalSession, emptyList())
        store.markIncludedDataChanged()
        val originalMetadata = checkNotNull(database.backupDao().getMetadata())
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_restored_plan
            BEFORE INSERT ON weekly_plans
            WHEN NEW.id = 'restored-plan'
            BEGIN
                SELECT RAISE(ABORT, 'forced restore failure');
            END
            """
                .trimIndent()
        )
        val bundle =
            BackupBundle(
                localChangeRevision = 7,
                weeklyPlans = listOf(TestData.plan(id = "restored-plan")),
                plannedWorkouts = emptyList(),
                plannedExercises = emptyList(),
                workoutLogs = emptyList(),
                loggedExercises = emptyList(),
                loggedSets = emptyList(),
                personalRecords = emptyList(),
            )

        var failure: Exception? = null
        try {
            val encoded = BackupSnapshotCodec().encode(bundle)
            val artifact =
                BackupSnapshotCodec()
                    .decodeForRestore(
                        encoded,
                        RestoreLineage(
                            "owner-a",
                            "backup-a",
                            2,
                            encoded.contentDigest,
                            "source-a",
                            10,
                        ),
                    )
            store.restore(
                artifact,
                ActiveSessionRestoreDisposition.Discard(originalSession.id),
            )
        } catch (error: Exception) {
            failure = error
        }

        assertNotNull(failure)
        assertEquals(originalPlan, database.planDao().getActivePlan())
        assertEquals(originalSession, database.sessionDao().getActiveSession())
        assertEquals(originalMetadata, database.backupDao().getMetadata())
    }

    @Test
    fun validateInstallation_initializesFreshMetadataAndAcceptsTheMatchingSentinel() = runBlocking {
        val sentinel = FakeInstallationSentinel()
        val store =
            RoomBackupStore(
                databaseRule.database,
                SequenceIdProvider("installation"),
                sentinel,
            )

        val initialized = store.validateInstallation()
        val validated = store.validateInstallation()

        assertEquals(InstallationValidationResult.Initialized, initialized)
        assertEquals(InstallationValidationResult.Validated, validated)
        assertEquals(
            "installation-1",
            checkNotNull(databaseRule.database.backupDao().getMetadata()).installationId,
        )
        assertEquals("installation-1", sentinel.installationId)
    }

    @Test
    fun validateInstallation_rotatesTransferredOwnershipAndLineageBeforeBecomingValid() =
        runBlocking {
            val database = databaseRule.database
            val ids = SequenceIdProvider("installation")
            val sentinel = FakeInstallationSentinel(installationId = "copied-device")
            val store = RoomBackupStore(database, ids, sentinel)
            store.markIncludedDataChanged()
            val original = checkNotNull(database.backupDao().getMetadata())
            database
                .backupDao()
                .updateMetadata(
                    original.copy(
                        ownerUid = "owner-a",
                        lastCompleteLocalRevision = 1,
                        lastObservedRemoteBackupId = "backup-a",
                        lastObservedRemoteGeneration = 2,
                        lastObservedRemoteDigest = "digest-a",
                        lastObservedSourceInstallationId = "other-device",
                        lastObservedRemoteCompletedAt = TestData.BASE_TIME,
                    )
                )

            val transferred = store.validateInstallation()
            val validated = store.validateInstallation()

            assertEquals(InstallationValidationResult.Transferred, transferred)
            assertEquals(InstallationValidationResult.Validated, validated)
            val reset = checkNotNull(database.backupDao().getMetadata())
            assertEquals("installation-2", reset.installationId)
            assertEquals("installation-2", sentinel.installationId)
            assertNull(reset.ownerUid)
            assertEquals(0L, reset.lastCompleteLocalRevision)
            assertNull(reset.lastObservedRemoteBackupId)
            assertEquals(0L, reset.lastObservedRemoteGeneration)
            assertNull(reset.lastObservedRemoteDigest)
            assertNull(reset.lastObservedSourceInstallationId)
            assertNull(reset.lastObservedRemoteCompletedAt)
        }

    @Test
    fun validateInstallation_failsClosedWhenTheSentinelCannotBeWritten() = runBlocking {
        val database = databaseRule.database
        val sentinel = FakeInstallationSentinel(writeSucceeds = false)
        val store = RoomBackupStore(database, SequenceIdProvider("installation"), sentinel)
        store.markIncludedDataChanged()
        val original = checkNotNull(database.backupDao().getMetadata())
        database.backupDao().updateMetadata(original.copy(ownerUid = "owner-a"))

        val result = store.validateInstallation()

        assertEquals(InstallationValidationResult.Failed, result)
        assertNull(checkNotNull(database.backupDao().getMetadata()).ownerUid)
    }

    @Test
    fun validateInstallation_readFailureLeavesTrustedMetadataUnchanged() = runBlocking {
        val database = databaseRule.database
        val sentinel = FakeInstallationSentinel(readFails = true)
        val store = RoomBackupStore(database, SequenceIdProvider("installation"), sentinel)
        store.markIncludedDataChanged()
        val original = checkNotNull(database.backupDao().getMetadata())
        database.backupDao().updateMetadata(original.copy(ownerUid = "owner-a"))
        val trusted = checkNotNull(database.backupDao().getMetadata())

        assertEquals(InstallationValidationResult.Failed, store.validateInstallation())
        assertEquals(trusted, database.backupDao().getMetadata())
    }

    private class FakeInstallationSentinel(
        var installationId: String? = null,
        private val writeSucceeds: Boolean = true,
        private val readFails: Boolean = false,
    ) : InstallationSentinel {
        override suspend fun readInstallationId(): String? {
            if (readFails) error("forced sentinel read failure")
            return installationId
        }

        override suspend fun writeInstallationId(installationId: String): Boolean {
            if (!writeSucceeds) return false
            this.installationId = installationId
            return true
        }
    }
}
