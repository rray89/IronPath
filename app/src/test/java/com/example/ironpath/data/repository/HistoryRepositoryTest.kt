package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class HistoryRepositoryTest {

    private lateinit var historyDao: HistoryDao
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
        historyDao = mockk(relaxed = true)
        repository = HistoryRepository(historyDao)
    }

    @Test
    fun `observeAllLogs returns flow from historyDao`() {
        val expected = flowOf(listOf(log))
        every { historyDao.observeAllLogs() } returns expected

        val result = repository.observeAllLogs()

        assert(result === expected)
    }

    @Test
    fun `getLogById delegates to historyDao`() = runTest {
        repository.getLogById("log1")
        coVerify(exactly = 1) { historyDao.getLogById("log1") }
    }

    @Test
    fun `insertLog delegates to historyDao`() = runTest {
        repository.insertLog(log)
        coVerify(exactly = 1) { historyDao.insertLog(log) }
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
