package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class HistoryRepositoryTest {

    private lateinit var historyDao: HistoryDao
    private lateinit var database: IronPathDatabase
    private lateinit var backupChangeTracker: BackupChangeTracker
    private lateinit var repository: HistoryRepository

    private val log =
        WorkoutLog(
            id = "log1",
            title = "Push A",
            startedAt = 1000L,
            completedAt = 4600L,
            durationMinutes = 1,
            exerciseCount = 3,
        )
    private val loggedExercise =
        LoggedExercise(
            id = "lex1",
            workoutLogId = "log1",
            name = "Bench Press",
            plannedSets = 3,
            plannedReps = 10,
            plannedWeightKg = 60.0,
            orderIndex = 0,
        )
    private val loggedSet =
        LoggedSet(
            id = "lset1",
            loggedExerciseId = "lex1",
            setNumber = 1,
            reps = 10,
            weightKg = 62.5,
            completedAt = 4_000L,
        )

    @Before
    fun setUp() {
        historyDao = mockk()
        database = mockk()
        backupChangeTracker = mockk()
        coEvery { historyDao.getLogById(any()) } returns null
        coEvery { historyDao.insertLog(any()) } returns Unit
        coEvery { backupChangeTracker.markIncludedDataChanged() } returns Unit
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers
            {
                secondArg<suspend () -> Unit>().invoke()
            }
        repository = HistoryRepository(historyDao, database, backupChangeTracker)
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `observeAllLogs returns flow from historyDao`() {
        val expected = flowOf(listOf(log))
        every { historyDao.observeAllLogs() } returns expected

        val result = repository.observeAllLogs()

        assertSame(expected, result)
    }

    @Test
    fun `getLogById delegates to historyDao`() = runTest {
        repository.getLogById("log1")
        coVerify(exactly = 1) { historyDao.getLogById("log1") }
    }

    @Test
    fun `insertLog delegates to historyDao and increments the backup revision`() = runTest {
        repository.insertLog(log)
        coVerify(exactly = 1) { historyDao.insertLog(log) }
        coVerify(exactly = 1) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `getLogDetail returns null when log does not exist`() = runTest {
        coEvery { historyDao.getLogById("missing") } returns null

        val result = repository.getLogDetail("missing")

        assertNull(result)
    }

    @Test
    fun `getLogDetail returns log with exercises and sets`() = runTest {
        coEvery { historyDao.getLogById("log1") } returns log
        coEvery { historyDao.getLoggedExercisesForLog("log1") } returns listOf(loggedExercise)
        coEvery { historyDao.getLoggedSetsForExercises(listOf("lex1")) } returns listOf(loggedSet)

        val result = repository.getLogDetail("log1")

        assertEquals(log, result?.log)
        assertEquals(
            listOf(LoggedExerciseDetail(loggedExercise, listOf(loggedSet))),
            result?.exercises,
        )
    }
}
