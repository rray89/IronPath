package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.AccountDeletionDao
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordRepositoryTest {

    private lateinit var recordDao: RecordDao
    private lateinit var database: IronPathDatabase
    private lateinit var accountDeletionDao: AccountDeletionDao
    private lateinit var backupChangeTracker: BackupChangeTracker
    private lateinit var repository: RecordRepository

    private val record =
        PersonalRecord(
            id = "rec1",
            exerciseName = "Bench Press",
            normalizedExerciseName = "bench press",
            weightKg = 100.0,
            achievedOn = "2026-04-12",
            createdAt = 1_000L,
        )

    @Before
    fun setUp() {
        recordDao = mockk()
        database = mockk()
        accountDeletionDao = mockk()
        backupChangeTracker = mockk()
        coEvery { recordDao.insertRecord(any()) } returns Unit
        coEvery { recordDao.getLoggedRecordsForWorkoutLog(any()) } returns emptyList()
        coEvery { backupChangeTracker.markIncludedDataChanged() } returns Unit
        every { database.accountDeletionDao() } returns accountDeletionDao
        coEvery { accountDeletionDao.getJournal() } returns null
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any?>()) } coAnswers
            {
                secondArg<suspend () -> Any?>().invoke()
            }
        repository = RecordRepository(recordDao, database, backupChangeTracker)
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `insertRecord delegates to recordDao`() = runTest {
        repository.insertRecord(record)
        coVerify(exactly = 1) { recordDao.insertRecord(record) }
    }

    @Test
    fun `observeAllRecords returns flow from recordDao`() {
        val expected = flowOf(listOf(record))
        every { recordDao.observeAllRecords() } returns expected

        val result = repository.observeAllRecords()

        assertSame(expected, result)
    }

    @Test
    fun `getAllRecordExerciseNames returns DAO suggestions`() = runTest {
        val expected = listOf("Bench Press", "Squat")
        coEvery { recordDao.getAllRecordExerciseNames() } returns expected

        assertEquals(expected, repository.getAllRecordExerciseNames())
    }

    private val editedDraft =
        ValidatedRecordDraft("  SQUAT  ", "untrusted", 125.5, "2026-04-12", "Corrected")

    @Test
    fun `edit normalizes current manual row preserving identity and metadata`() = runTest {
        coEvery { recordDao.getRecordById(record.id) } returns record
        coEvery { recordDao.updateRecord(any()) } returns Unit
        repository.updateManualRecord(record.id, editedDraft)
        coVerify {
            recordDao.updateRecord(
                record.copy(
                    exerciseName = "SQUAT",
                    normalizedExerciseName = "squat",
                    weightKg = 125.5,
                    note = "Corrected"
                )
            )
        }
        coVerify(exactly = 1) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `unchanged edit excludes own identity and needs no revision`() = runTest {
        coEvery { recordDao.getRecordById(record.id) } returns record
        repository.updateManualRecord(
            record.id,
            ValidatedRecordDraft(
                record.exerciseName,
                record.normalizedExerciseName,
                record.weightKg,
                record.achievedOn,
                record.note
            )
        )
        coVerify(exactly = 0) { recordDao.updateRecord(any()) }
        coVerify(exactly = 0) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `missing and logged target cannot be mutated`() = runTest {
        for (target in listOf(null, record.copy(sourceType = RecordSource.Logged))) {
            coEvery { recordDao.getRecordById(record.id) } returns target
            assertTrue(
                runCatching { repository.updateManualRecord(record.id, editedDraft) }.isFailure
            )
            assertTrue(runCatching { repository.deleteManualRecord(record.id) }.isFailure)
        }
        coVerify(exactly = 0) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `confirmed delete targets one manual id and marks included data`() = runTest {
        coEvery { recordDao.getRecordById(record.id) } returns record
        coEvery { recordDao.deleteRecord(record.id) } returns Unit
        repository.deleteManualRecord(record.id)
        coVerify(exactly = 1) { recordDao.deleteRecord(record.id) }
        coVerify(exactly = 1) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `derived command reads persisted source and maps source values`() = runTest {
        val history = mockk<HistoryDao>()
        every { database.historyDao() } returns history
        val log =
            WorkoutLog(
                "log",
                title = "Workout",
                startedAt = 1L,
                completedAt = 2000L,
                durationMinutes = 1,
                exerciseCount = 1
            )
        coEvery { history.getLogById("log") } returns log
        coEvery { history.getLoggedExercisesForLog("log") } returns
            listOf(LoggedExercise("ex", "log", " Bench Press ", 1, 8, 50.0, 0))
        coEvery { history.getLoggedSetsForExercises(listOf("ex")) } returns
            listOf(LoggedSet("set", "ex", 1, 8, 62.5))
        val result = repository.saveLoggedSetAsRecord("log", "set", "new", 123L, ZoneOffset.UTC)
        assertEquals("new", result.id)
        assertEquals("Bench Press", result.exerciseName)
        assertEquals("bench press", result.normalizedExerciseName)
        assertEquals("1970-01-01", result.achievedOn)
        assertEquals("log", result.sourceWorkoutLogId)
        assertEquals(RecordSource.Logged, result.sourceType)
        assertEquals(62.5, result.weightKg, 0.0)
        coEvery { recordDao.getLoggedRecordsForWorkoutLog("log") } returns listOf(result)
        val repeated =
            repository.saveLoggedSetAsRecord(
                "log",
                "set",
                "unused-new-id",
                456L,
                ZoneOffset.ofHours(-12)
            )
        assertEquals(result, repeated)
        coVerify(exactly = 1) { recordDao.insertRecord(result) }
        coVerify(exactly = 1) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `deleted source and missing set cannot insert derived record`() = runTest {
        val history = mockk<HistoryDao>()
        every { database.historyDao() } returns history
        coEvery { history.getLogById("log") } returns null
        assertTrue(
            runCatching {
                    repository.saveLoggedSetAsRecord("log", "set", "new", 123L, ZoneOffset.UTC)
                }
                .isFailure
        )
        coEvery { history.getLogById("log") } returns
            WorkoutLog(
                "log",
                title = "Workout",
                startedAt = 1L,
                completedAt = 2L,
                durationMinutes = 1,
                exerciseCount = 0
            )
        coEvery { history.getLoggedExercisesForLog("log") } returns emptyList()
        assertTrue(
            runCatching {
                    repository.saveLoggedSetAsRecord("log", "set", "new", 123L, ZoneOffset.UTC)
                }
                .isFailure
        )
        coVerify(exactly = 0) { recordDao.insertRecord(any()) }
        coVerify(exactly = 0) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `logged source query delegates without writes`() = runTest {
        coEvery { recordDao.getLoggedRecordsForWorkoutLog("log") } returns listOf(record)
        assertEquals(listOf(record), repository.getLoggedRecordsForWorkoutLog("log"))
    }
}
