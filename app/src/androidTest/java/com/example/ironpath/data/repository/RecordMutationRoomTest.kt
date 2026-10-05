package com.example.ironpath.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.AccountDeletionInProgressException
import com.example.ironpath.data.local.StaleProfileGenerationException
import com.example.ironpath.data.local.entity.*
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import com.example.ironpath.testutil.RoomTestDatabaseRule
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordMutationRoomTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()
    private val db
        get() = databaseRule.database

    private val dao
        get() = db.recordDao()

    private val zone = ZoneId.of("America/Vancouver")
    private val original =
        PersonalRecord("manual", "Deadlift", "deadlift", 100.0, "2026-07-16", createdAt = 123L)
    private val draft =
        ValidatedRecordDraft("  Bench Press  ", "ignored", 62.5, "2026-07-16", "corrected")
    private val repository
        get() =
            RecordRepository(
                dao,
                db,
                BackupChangeTracker { db.backupDao().incrementLocalChangeRevision() }
            )

    private suspend fun revision() = db.backupDao().getMetadata()!!.localChangeRevision

    private suspend fun source() {
        db.historyDao()
            .insertLog(
                WorkoutLog(
                    "log",
                    title = "Push",
                    startedAt = 1L,
                    completedAt = Instant.parse("2026-07-17T01:00:00Z").toEpochMilli(),
                    durationMinutes = 1,
                    exerciseCount = 1
                )
            )
        db.historyDao()
            .insertLoggedExercises(
                listOf(LoggedExercise("ex", "log", "  Bench Press  ", 3, 8, 60.0, 0))
            )
        db.historyDao()
            .insertLoggedSets(
                listOf(
                    LoggedSet("set", "ex", 1, 8, 62.5),
                    LoggedSet("same", "ex", 2, 5, 62.5),
                    LoggedSet("blank", "ex", 3)
                )
            )
    }

    private suspend fun save(setId: String = "set", generation: Long = 4L, id: String = "derived") =
        repository.saveLoggedSetAsRecord("log", setId, id, 999L, zone, generation)

    @Before
    fun seed() = runBlocking {
        db.backupDao()
            .insertMetadataIfAbsent(
                AccountBackupMetadata(installationId = "fixture", profileGeneration = 4L)
            )
        dao.insertRecord(original)
        source()
    }

    @Test
    fun editPreservesUuidAndMetadata_normalizesExcludesSelfAndDeleteLeavesLog() = runBlocking {
        repository.updateManualRecord(original.id, draft, 4L)
        val updated = dao.getRecordById(original.id)!!
        assertEquals(original.id, updated.id)
        assertEquals(original.createdAt, updated.createdAt)
        assertEquals("bench press", updated.normalizedExerciseName)
        assertEquals("Bench Press", updated.exerciseName)
        assertEquals(RecordSource.Manual, updated.sourceType)
        assertEquals(1L, revision())
        repository.updateManualRecord(original.id, draft, 4L)
        assertEquals(1L, revision())
        repository.deleteManualRecord(original.id, 4L)
        assertNull(dao.getRecordById(original.id))
        assertEquals(2L, revision())
        assertNotNull(db.historyDao().getLogById("log"))
        assertEquals(3, db.historyDao().getLoggedSetsForExercises(listOf("ex")).size)
    }

    @Test
    fun duplicateUpdatePreservesBothRowsAndRevisionAcrossSourceTypes() = runBlocking {
        val logged = save()
        val failure =
            runCatching { repository.updateManualRecord(original.id, draft, 4L) }.exceptionOrNull()
        assertTrue(failure is SQLiteConstraintException)
        assertEquals(original, dao.getRecordById(original.id))
        assertEquals(logged, dao.getRecordById(logged.id))
        assertEquals(1L, revision())
    }

    @Test
    fun sourceSaveRechecksEligibilityDateAndProvenance_andUniqueIdentity() = runBlocking {
        val record = save()
        assertEquals("2026-07-16", record.achievedOn)
        assertEquals(RecordSource.Logged, record.sourceType)
        assertEquals("log", record.sourceWorkoutLogId)
        assertEquals(62.5, record.weightKg, 0.0)
        assertEquals(listOf(record), dao.getLoggedRecordsForWorkoutLog("log"))
        assertTrue(
            runCatching { save("same", id = "duplicate") }.exceptionOrNull()
                is SQLiteConstraintException
        )
        assertTrue(runCatching { save("blank", id = "blank") }.isFailure)
        assertTrue(runCatching { save("not-a-set", id = "unknown") }.isFailure)
        assertEquals(1L, revision())
        assertEquals(2, dao.observeAllRecords().first().size)
    }

    @Test
    fun missingSourceAndLoggedMutationAreRejected() = runBlocking {
        val logged = save()
        assertTrue(runCatching { repository.updateManualRecord(logged.id, draft, 4L) }.isFailure)
        assertTrue(runCatching { repository.deleteManualRecord(logged.id, 4L) }.isFailure)
        db.openHelper.writableDatabase.execSQL("DELETE FROM workout_logs WHERE id = 'log'")
        assertTrue(runCatching { save(id = "missing") }.isFailure)
        assertEquals(1L, revision())
        assertEquals(logged, dao.getRecordById(logged.id))
    }

    @Test
    fun allMutationsHonorProfileAndDeletionBarrier() = runBlocking {
        val actions: List<suspend (Long) -> Unit> =
            listOf(
                { repository.updateManualRecord(original.id, draft, it) },
                { repository.deleteManualRecord(original.id, it) },
                {
                    save(generation = it)
                    Unit
                },
            )
        actions.forEach {
            assertTrue(runCatching { it(3L) }.exceptionOrNull() is StaleProfileGenerationException)
        }
        db.accountDeletionDao()
            .save(
                AccountDeletionJournal(
                    operationId = "deletion",
                    accountId = "account",
                    sessionEpoch = 1L,
                    profileGeneration = 4L,
                    stage = "REQUESTED",
                    createdAtEpochMillis = 1L
                )
            )
        actions.forEach {
            assertTrue(
                runCatching { it(4L) }.exceptionOrNull() is AccountDeletionInProgressException
            )
        }
        assertEquals(original, dao.getRecordById(original.id))
        assertEquals(0L, revision())
    }

    @Test
    fun revisionFailureRollsBackUpdateDeleteAndDerivedInsertion() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_revision BEFORE UPDATE ON account_backup_metadata BEGIN SELECT RAISE(ABORT, 'revision failed'); END"
        )
        assertTrue(runCatching { repository.updateManualRecord(original.id, draft, 4L) }.isFailure)
        assertEquals(original, dao.getRecordById(original.id))
        assertTrue(runCatching { repository.deleteManualRecord(original.id, 4L) }.isFailure)
        assertEquals(original, dao.getRecordById(original.id))
        assertTrue(runCatching { save() }.isFailure)
        assertEquals(listOf(original), dao.observeAllRecords().first())
        assertEquals(0L, revision())
    }
}
