package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.entity.PersonalRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class RecordRepositoryTest {

    private lateinit var recordDao: RecordDao
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
        coEvery { recordDao.insertRecord(any()) } returns Unit
        repository = RecordRepository(recordDao)
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
}
