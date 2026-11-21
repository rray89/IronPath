package com.example.ironpath.data.backup

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.local.StaleProfileGenerationException
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.SessionRepository
import com.example.ironpath.domain.account.AccountId
import com.example.ironpath.domain.backup.RemoteBackupSummary
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualBackupRoomTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    @Test
    fun oldProfileGenerationCannotWritePlanOrCommitBackupAfterProfileReset() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val staleCapture = store.capture()

        assertTrue(
            store.resetLocalProfile(
                expectedProfileGeneration = staleCapture.metadata.profileGeneration
            ) is LocalProfileResetResult.Committed
        )

        val repository = PlanRepository(database.planDao(), database, store)
        var staleWrite: Exception? = null
        try {
            repository.createPlan(
                TestData.plan(id = "stale-plan"),
                listOf(TestData.workout(id = "stale-workout", planId = "stale-plan")),
                listOf(
                    TestData.plannedExercise(
                        id = "stale-exercise",
                        workoutId = "stale-workout",
                    )
                ),
                expectedProfileGeneration = staleCapture.metadata.profileGeneration,
            )
        } catch (failure: Exception) {
            staleWrite = failure
        }
        assertTrue(staleWrite is StaleProfileGenerationException)
        assertNull(database.planDao().getActivePlan())
        val sessions =
            SessionRepository(
                database.sessionDao(),
                database.historyDao(),
                database.planDao(),
                database,
                PerformanceTracer(),
                store,
                com.example.ironpath.testutil.MutableTimeProvider(
                    java.time.Instant.parse("2026-07-13T19:00:00Z"),
                    java.time.ZoneId.of("UTC")
                ),
                SequenceIdProvider("start"),
            )
        assertTrue(
            failureOf {
                sessions.startPlannedWorkout(
                    "stale-workout",
                    staleCapture.metadata.profileGeneration,
                )
            }
                is StaleProfileGenerationException
        )
        assertTrue(
            failureOf {
                HistoryRepository(database.historyDao(), database, store)
                    .insertLog(
                        TestData.log(id = "stale-log"),
                        staleCapture.metadata.profileGeneration,
                    )
            }
                is StaleProfileGenerationException
        )
        assertTrue(
            failureOf {
                RecordRepository(database.recordDao(), database, store)
                    .insertRecord(
                        TestData.record(id = "stale-record"),
                        staleCapture.metadata.profileGeneration,
                    )
            }
                is StaleProfileGenerationException
        )
        assertNull(database.sessionDao().getActiveSession())
        assertTrue(database.backupDao().getWorkoutLogs().isEmpty())
        assertNull(database.recordDao().getRecordById("stale-record"))
        assertFalse(
            store.recordBackup(
                staleCapture,
                AccountId("owner"),
                artifact(staleCapture.bundle),
            )
        )

        val newGeneration = store.capture().metadata.profileGeneration
        repository.createPlan(
            TestData.plan(id = "fresh-plan"),
            listOf(TestData.workout(id = "fresh-workout", planId = "fresh-plan")),
            listOf(TestData.plannedExercise(id = "fresh-exercise", workoutId = "fresh-workout")),
            expectedProfileGeneration = newGeneration,
        )
        assertEquals("fresh-plan", database.planDao().getActivePlan()?.id)
    }

    @Test
    fun completedBackupPersistsBaselineAndLaterLocalEditsRemainDirty() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        database.recordDao().insertRecord(TestData.record(id = "record"))
        store.markIncludedDataChanged()
        val captured = store.capture()
        val backup = artifact(captured.bundle)
        database
            .recordDao()
            .insertRecord(
                TestData.record(
                    id = "new-record",
                    exerciseName = "Bench Press",
                    normalizedExerciseName = "bench press",
                    weightKg = 40.0
                )
            )
        store.markIncludedDataChanged()
        assertTrue(store.recordBackup(captured, AccountId("owner"), backup))
        val restored = RoomBackupStore(database, SequenceIdProvider("unused")).capture()
        assertEquals("owner", restored.metadata.ownerUid)
        assertEquals(2, restored.bundle.personalRecords.size)
        assertEquals(1L, restored.metadata.lastCompleteLocalRevision)
        assertEquals(backup, restored.baseline)
    }

    @Test
    fun syncAtomicallyAppliesGraphMetadataAndBaselineWhileRejectingStaleCapture() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val captured = store.capture()
        val merged =
            captured.bundle.copy(personalRecords = listOf(TestData.record(id = "cloud-record")))
        assertTrue(store.applySync(captured, AccountId("owner"), artifact(merged)))
        val completed = store.capture()
        assertEquals(1L, completed.metadata.localChangeRevision)
        assertEquals(1L, completed.metadata.lastCompleteLocalRevision)
        assertEquals("cloud-record", completed.bundle.personalRecords.single().id)
        assertEquals(artifact(merged), completed.baseline)
        assertFalse(store.applySync(captured, AccountId("owner"), artifact(captured.bundle)))
        assertEquals(completed, store.capture())
    }

    @Test
    fun activeSessionBlocksSyncAndResetClearsBaseline() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val captured = store.capture()
        assertTrue(store.recordBackup(captured, AccountId("owner"), artifact(captured.bundle)))
        database
            .sessionDao()
            .startNewSession(TestData.session(), listOf(TestData.sessionExercise()))
        val active = store.capture()
        assertFalse(store.applySync(active, AccountId("owner"), artifact(active.bundle)))
        assertNotNull(database.sessionDao().getActiveSession())
        store.resetLocalProfile()
        assertNull(store.capture().baseline)
        assertTrue(database.backupDao().getBaselineChunks().isEmpty())
    }

    @Test
    fun transferredInstallationClearsBaselineAndOwnershipTogether() = runBlocking {
        val store = RoomBackupStore(databaseRule.database, SequenceIdProvider("installation"))
        val captured = store.capture()
        val account = AccountId("owner")
        assertTrue(store.recordBackup(captured, account, artifact(captured.bundle)))
        val beforeRestore = store.capture()
        val restoreSnapshot =
            BackupSnapshotCodec()
                .encode(
                    beforeRestore.bundle.copy(
                        personalRecords = listOf(TestData.record(id = "restored-record")),
                    )
                )
        val restoreArtifact =
            BackupSnapshotCodec()
                .decodeForRestore(
                    restoreSnapshot,
                    RestoreLineage(
                        ownerUid = account.opaqueValue,
                        remoteBackupId = "restored-backup",
                        remoteGeneration = 2,
                        remoteDigest = restoreSnapshot.contentDigest,
                        sourceInstallationId = "another-installation",
                        completedAt = TestData.BASE_TIME,
                    ),
                )
        assertTrue(store.restore(beforeRestore, account, restoreArtifact, null))
        assertNotNull(databaseRule.database.backupDao().getRestoreUndoMetadata())
        assertTrue(databaseRule.database.backupDao().getRestoreUndoChunks().isNotEmpty())

        assertEquals(InstallationValidationResult.Transferred, store.validateInstallation())
        val transferred = store.capture()
        assertNull(transferred.metadata.ownerUid)
        assertNull(transferred.baseline)
        assertNull(databaseRule.database.backupDao().getRestoreUndoMetadata())
        assertTrue(databaseRule.database.backupDao().getRestoreUndoChunks().isEmpty())
    }

    @Test
    fun baselineInsertFailureRollsBackTheWholeSyncTransaction() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        database.recordDao().insertRecord(TestData.record(id = "original"))
        store.markIncludedDataChanged()
        val first = store.capture()
        assertTrue(store.recordBackup(first, AccountId("owner"), artifact(first.bundle)))
        val original = store.capture()
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_sync_baseline BEFORE INSERT ON backup_baseline_chunks BEGIN SELECT RAISE(ABORT, 'injected failure'); END"
        )
        val merged =
            original.bundle.copy(personalRecords = listOf(TestData.record(id = "replacement")))
        val failure =
            runCatching { store.applySync(original, AccountId("owner"), artifact(merged)) }
                .exceptionOrNull()
        assertNotNull(failure)
        assertEquals(original, store.capture())
    }

    @Test
    fun revisionOverflowRollsBackItsIncludedProductMutation() = runBlocking {
        val database = databaseRule.database
        val store = RoomBackupStore(database, SequenceIdProvider("installation"))
        val captured = store.capture()
        database
            .backupDao()
            .updateMetadata(captured.metadata.copy(localChangeRevision = Long.MAX_VALUE))
        val failure =
            runCatching {
                    database.withTransaction {
                        database.recordDao().insertRecord(TestData.record(id = "never-committed"))
                        store.markIncludedDataChanged()
                    }
                }
                .exceptionOrNull()
        assertTrue(failure is ArithmeticException)
        assertTrue(store.export().personalRecords.isEmpty())
        assertEquals(Long.MAX_VALUE, store.capture().metadata.localChangeRevision)
    }

    private fun artifact(bundle: BackupBundle): RemoteBackupArtifact {
        val snapshot = BackupSnapshotCodec().encode(bundle)
        return RemoteBackupArtifact(
            RemoteBackupSummary("backup", 100, "installation", snapshot.entityCounts),
            1,
            snapshot
        )
    }

    private suspend fun failureOf(block: suspend () -> Unit): Exception? =
        try {
            block()
            null
        } catch (failure: Exception) {
            failure
        }
}
