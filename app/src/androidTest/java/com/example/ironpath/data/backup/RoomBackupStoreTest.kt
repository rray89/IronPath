package com.example.ironpath.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.RemoteBackupSummary
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomBackupStoreTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()
    @get:Rule val fileDatabaseRule = FileBackedRoomTestDatabaseRule()

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
            val oldRecord =
                TestData.record(id = "old-record", sourceWorkoutLogId = "missing-old-log")
            database.planDao().insertPlan(oldPlan)
            database.recordDao().insertRecord(oldRecord)
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
            val encoded = BackupSnapshotCodec(preserveDanglingProvenance = true).encode(bundle)
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
            val artifact = BackupSnapshotCodec().decodeForRestore(encoded, lineage)
            val captured = store.capture()

            val blocked = store.restore(captured, AccountId("account-uid"), artifact, null)

            assertFalse(blocked)
            assertEquals(oldPlan, database.planDao().getActivePlan())
            assertEquals(oldSession, database.sessionDao().getActiveSession())

            database.sessionDao().deleteSession(oldSession.id)
            val replacementSession =
                TestData.session(id = "replacement-session", workoutId = "replacement-workout")
            database.sessionDao().startNewSession(replacementSession, emptyList())
            val staleConfirmation =
                store.restore(
                    captured,
                    AccountId("account-uid"),
                    artifact,
                    oldSession.id,
                )
            assertFalse(staleConfirmation)
            assertEquals(replacementSession, database.sessionDao().getActiveSession())

            val restored =
                store.restore(
                    store.capture(),
                    AccountId("account-uid"),
                    artifact,
                    replacementSession.id,
                )

            assertTrue(restored)
            assertEquals(
                setOf("sourcePlannedWorkoutId", "sourceWorkoutLogId"),
                artifact.nulledProvenanceFields,
            )
            assertEquals(restoredPlan, database.planDao().getActivePlan())
            assertNull(database.sessionDao().getActiveSession())
            assertNull(database.historyDao().getLogById(restoredLog.id)?.sourcePlannedWorkoutId)
            assertNull(database.recordDao().getRecordById(restoredRecord.id)?.sourceWorkoutLogId)
            val metadata = checkNotNull(database.backupDao().getMetadata())
            assertEquals("account-uid", metadata.ownerUid)
            assertEquals(4L, metadata.lastObservedRemoteGeneration)
            assertEquals("backup-7", metadata.lastObservedRemoteBackupId)
            assertEquals(encoded.contentDigest, metadata.lastObservedRemoteDigest)
            assertEquals(1L, metadata.localChangeRevision)
            assertEquals(1L, metadata.lastCompleteLocalRevision)

            val undoRows =
                database.openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM restore_undo_metadata")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getInt(0)
                    }
            assertEquals(1, undoRows)

            val persistedUndo =
                checkNotNull(store.captureUndo(AccountId("account-uid"), "installation-1"))
            assertEquals(
                "missing-old-log",
                persistedUndo.bundle.personalRecords.single().sourceWorkoutLogId
            )
            assertTrue(store.undo(store.capture(), AccountId("account-uid"), persistedUndo))
            assertEquals(oldPlan, database.planDao().getActivePlan())
            assertEquals(
                "missing-old-log",
                database.recordDao().getRecordById(oldRecord.id)?.sourceWorkoutLogId
            )
            assertNull(database.sessionDao().getActiveSession())
            val undoMetadata = checkNotNull(database.backupDao().getMetadata())
            assertNull(undoMetadata.ownerUid)
            assertEquals(2L, undoMetadata.localChangeRevision)
            assertEquals(0L, undoMetadata.lastCompleteLocalRevision)
            assertTrue(undoMetadata.requiresLineageReviewAfterUndo)
            val consumedUndoRows =
                database.openHelper.readableDatabase
                    .query("SELECT COUNT(*) FROM restore_undo_metadata")
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        cursor.getInt(0)
                    }
            assertEquals(0, consumedUndoRows)
        }

    @Test
    fun oneUndoSurvivesColdRoomReopenAndIsConsumedExactlyOnce() = runBlocking {
        val firstDatabase = fileDatabaseRule.open()
        val firstStore = RoomBackupStore(firstDatabase, SequenceIdProvider("install"))
        val original = TestData.record(id = "cold-old", weightKg = 50.0)
        firstDatabase.backupDao().insertPersonalRecords(listOf(original))
        firstStore.markIncludedDataChanged()
        val account = AccountId("owner")
        val sharedBase = firstStore.capture()
        val baseArtifact =
            remoteArtifact(
                bundleWithRecord(sharedBase.metadata.localChangeRevision, original),
                backupId = "cold-base",
                generation = 1,
                sourceInstallationId = "other-device",
            )
        assertTrue(firstStore.recordBackup(sharedBase, account, baseArtifact))

        val localChange = original.copy(weightKg = 60.0)
        firstDatabase.backupDao().deletePersonalRecords()
        firstDatabase.backupDao().insertPersonalRecords(listOf(localChange))
        firstStore.markIncludedDataChanged()
        val targetArtifact =
            remoteArtifact(
                bundleWithRecord(10, original.copy(weightKg = 70.0)),
                backupId = "cold-target",
                generation = 2,
                sourceInstallationId = "another-device",
            )
        val validatedTarget = targetArtifact.toValidatedRestore("owner")
        assertTrue(firstStore.restore(firstStore.capture(), account, validatedTarget, null))
        val installationId = checkNotNull(firstDatabase.backupDao().getMetadata()).installationId
        firstDatabase.close()

        val reopened = fileDatabaseRule.open()
        val reopenedStore = RoomBackupStore(reopened, SequenceIdProvider("install"))
        val undo = checkNotNull(reopenedStore.captureUndo(account, installationId))
        assertEquals(60.0, undo.bundle.personalRecords.single().weightKg, 0.0)
        assertEquals(1L, undo.baseline?.generation)
        assertTrue(reopenedStore.undo(reopenedStore.capture(), account, undo))

        val restored = reopenedStore.capture()
        assertEquals(60.0, restored.bundle.personalRecords.single().weightKg, 0.0)
        assertEquals(1L, restored.baseline?.generation)
        assertEquals("cold-base", restored.baseline?.summary?.backupId)
        assertEquals(1L, restored.metadata.lastObservedRemoteGeneration)
        assertEquals(1L, restored.metadata.lastCompleteLocalRevision)
        assertTrue(restored.metadata.requiresLineageReviewAfterUndo)
        assertNull(reopenedStore.captureUndo(account, installationId))
    }

    @Test
    fun failedReplacementRestoreRollsBackThePreviousUndoSlot() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val account = AccountId("owner")
        val original = TestData.plan(id = "undo-original")
        database.planDao().insertPlan(original)
        store.markIncludedDataChanged()

        fun planArtifact(id: String, generation: Long) =
            remoteArtifact(
                    BackupBundle(
                        localChangeRevision = generation,
                        weeklyPlans = listOf(TestData.plan(id = id)),
                        plannedWorkouts = emptyList(),
                        plannedExercises = emptyList(),
                        workoutLogs = emptyList(),
                        loggedExercises = emptyList(),
                        loggedSets = emptyList(),
                        personalRecords = emptyList(),
                    ),
                    backupId = "backup-$id",
                    generation = generation,
                    sourceInstallationId = "source-$id",
                )
                .toValidatedRestore(account.opaqueValue)

        assertTrue(store.restore(store.capture(), account, planArtifact("undo-first", 1), null))
        assertTrue(store.restore(store.capture(), account, planArtifact("undo-second", 2), null))
        val beforeFailure =
            checkNotNull(
                store.captureUndo(
                    account,
                    checkNotNull(database.backupDao().getMetadata()).installationId
                )
            )
        assertEquals("undo-first", beforeFailure.bundle.weeklyPlans.single().id)
        val metadataBeforeFailure = checkNotNull(database.backupDao().getMetadata())

        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_third_restore
            BEFORE INSERT ON weekly_plans
            WHEN NEW.id = 'undo-third'
            BEGIN
                SELECT RAISE(ABORT, 'forced replacement restore failure');
            END
            """
                .trimIndent()
        )
        var failure: Exception? = null
        try {
            store.restore(store.capture(), account, planArtifact("undo-third", 3), null)
        } catch (error: Exception) {
            failure = error
        }

        assertNotNull(failure)
        assertEquals("undo-second", database.planDao().getActivePlan()?.id)
        assertEquals(metadataBeforeFailure, database.backupDao().getMetadata())
        val afterFailure =
            checkNotNull(store.captureUndo(account, metadataBeforeFailure.installationId))
        assertEquals(beforeFailure.slotIdentity, afterFailure.slotIdentity)
        assertEquals(beforeFailure, afterFailure)
        assertTrue(store.undo(store.capture(), account, afterFailure))
        assertEquals("undo-first", database.planDao().getActivePlan()?.id)
        assertNull(store.captureUndo(account, metadataBeforeFailure.installationId))
    }

    @Test
    fun failedUndoInsertRollsBackCurrentDataMetadataAndSlotThenRetrySucceeds() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val account = AccountId("owner")
        val original = TestData.plan(id = "undo-original")
        database.planDao().insertPlan(original)
        store.markIncludedDataChanged()
        val target =
            remoteArtifact(
                    bundleWithPlan(2, TestData.plan(id = "undo-restored")),
                    backupId = "undo-target",
                    generation = 1,
                    sourceInstallationId = "source",
                )
                .toValidatedRestore(account.opaqueValue)
        assertTrue(store.restore(store.capture(), account, target, null))

        val metadataBefore = checkNotNull(database.backupDao().getMetadata())
        val captureBefore = store.capture()
        val slotBefore = checkNotNull(store.captureUndo(account, metadataBefore.installationId))
        assertEquals("undo-original", slotBefore.bundle.weeklyPlans.single().id)
        assertNotNull(database.backupDao().getRestoreUndoMetadata())
        assertTrue(database.backupDao().getRestoreUndoChunks().isNotEmpty())

        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_undo_plan
            BEFORE INSERT ON weekly_plans
            WHEN NEW.id = 'undo-original'
            BEGIN
                SELECT RAISE(ABORT, 'forced undo failure');
            END
            """
                .trimIndent()
        )
        val failure =
            runCatching { store.undo(captureBefore, account, slotBefore) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(captureBefore, store.capture())
        assertEquals(metadataBefore, database.backupDao().getMetadata())
        assertEquals(slotBefore, store.captureUndo(account, metadataBefore.installationId))

        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_undo_plan")
        assertTrue(store.undo(store.capture(), account, slotBefore))
        assertEquals(original, database.planDao().getActivePlan())
        assertNull(store.captureUndo(account, metadataBefore.installationId))
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

            val captured = store.capture()
            val restoreTarget =
                remoteArtifact(
                        bundleWithRecord(2, TestData.record(id = "reset-target")),
                        backupId = "reset-target",
                        generation = 4,
                        sourceInstallationId = "source-device",
                    )
                    .toValidatedRestore("owner-a")
            assertTrue(
                store.restore(
                    captured,
                    AccountId("owner-a"),
                    restoreTarget,
                    captured.activeSessionId,
                )
            )
            assertNotNull(database.backupDao().getRestoreUndoMetadata())
            assertTrue(database.backupDao().getRestoreUndoChunks().isNotEmpty())

            assertEquals(
                LocalProfileResetResult.Committed(installationMarkerUpdated = true),
                store.resetLocalProfile(pendingSignOutUid = "owner-a"),
            )

            assertNull(database.planDao().getActivePlan())
            assertNull(database.sessionDao().getActiveSession())
            val reset = checkNotNull(database.backupDao().getMetadata())
            assertNotEquals("installation-1", reset.installationId)
            assertNull(reset.ownerUid)
            assertEquals(0L, reset.localChangeRevision)
            assertEquals(0L, reset.lastCompleteLocalRevision)
            assertNull(reset.lastObservedRemoteBackupId)
            assertEquals(0L, reset.lastObservedRemoteGeneration)
            assertNull(reset.lastObservedRemoteDigest)
            assertNull(reset.lastObservedSourceInstallationId)
            assertNull(reset.lastObservedRemoteCompletedAt)
            assertEquals("owner-a", reset.pendingSignOutUid)
            assertNull(database.backupDao().getRestoreUndoMetadata())
            assertTrue(database.backupDao().getRestoreUndoChunks().isEmpty())
            val clearedBundle = store.capture().bundle
            assertTrue(clearedBundle.weeklyPlans.isEmpty())
            assertTrue(clearedBundle.plannedWorkouts.isEmpty())
            assertTrue(clearedBundle.plannedExercises.isEmpty())
            assertTrue(clearedBundle.workoutLogs.isEmpty())
            assertTrue(clearedBundle.loggedExercises.isEmpty())
            assertTrue(clearedBundle.loggedSets.isEmpty())
            assertTrue(clearedBundle.personalRecords.isEmpty())
            assertEquals(reset.installationId, sentinel.installationId)
            assertTrue(store.clearPendingSignOut("owner-a"))
            assertNull(database.backupDao().getMetadata()?.pendingSignOutUid)
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
                store.capture(),
                AccountId("owner-a"),
                artifact,
                originalSession.id,
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

    private fun bundleWithRecord(
        revision: Long,
        record: com.example.ironpath.data.local.entity.PersonalRecord
    ) =
        BackupBundle(
            localChangeRevision = revision,
            weeklyPlans = emptyList(),
            plannedWorkouts = emptyList(),
            plannedExercises = emptyList(),
            workoutLogs = emptyList(),
            loggedExercises = emptyList(),
            loggedSets = emptyList(),
            personalRecords = listOf(record),
        )

    private fun bundleWithPlan(
        revision: Long,
        plan: com.example.ironpath.data.local.entity.WeeklyPlan
    ) =
        BackupBundle(
            localChangeRevision = revision,
            weeklyPlans = listOf(plan),
            plannedWorkouts = emptyList(),
            plannedExercises = emptyList(),
            workoutLogs = emptyList(),
            loggedExercises = emptyList(),
            loggedSets = emptyList(),
            personalRecords = emptyList(),
        )

    private fun remoteArtifact(
        bundle: BackupBundle,
        backupId: String,
        generation: Long,
        sourceInstallationId: String,
    ): RemoteBackupArtifact {
        val snapshot = BackupSnapshotCodec().encode(bundle)
        return RemoteBackupArtifact(
            RemoteBackupSummary(
                backupId = backupId,
                completedAtEpochMillis = TestData.BASE_TIME + generation,
                sourceInstallationId = sourceInstallationId,
                entityCounts = snapshot.entityCounts,
            ),
            generation,
            snapshot,
        )
    }

    private fun RemoteBackupArtifact.toValidatedRestore(ownerUid: String) =
        BackupSnapshotCodec()
            .decodeForRestore(
                snapshot,
                RestoreLineage(
                    ownerUid = ownerUid,
                    remoteBackupId = summary.backupId,
                    remoteGeneration = generation,
                    remoteDigest = snapshot.contentDigest,
                    sourceInstallationId = summary.sourceInstallationId,
                    completedAt = summary.completedAtEpochMillis,
                ),
            )

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
