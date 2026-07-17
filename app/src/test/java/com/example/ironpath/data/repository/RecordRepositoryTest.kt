package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.entity.PersonalRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        coEvery { recordDao.updateRecord(any()) } returns Unit
        coEvery { recordDao.deleteRecord(any()) } returns Unit
        repository = RecordRepository(recordDao)
    }

    // -- isDuplicateExcluding --

    @Test
    fun `isDuplicateExcluding returns true when DAO count is greater than 0`() = runTest {
        coEvery {
            recordDao.countDuplicatesExcluding("bench press", "2026-04-12", 100.0, "rec1")
        } returns 1

        val result =
            repository.isDuplicateExcluding(
                normalizedName = "bench press",
                date = "2026-04-12",
                weight = 100.0,
                excludeId = "rec1",
            )

        assertTrue(result)
    }

    @Test
    fun `isDuplicateExcluding returns false when DAO count is 0`() = runTest {
        coEvery { recordDao.countDuplicatesExcluding(any(), any(), any(), any()) } returns 0

        val result =
            repository.isDuplicateExcluding(
                normalizedName = "bench press",
                date = "2026-04-12",
                weight = 100.0,
                excludeId = "rec1",
            )

        assertFalse(result)
    }

    // -- CRUD delegation --

    @Test
    fun `insertRecord delegates to recordDao`() = runTest {
        repository.insertRecord(record)
        coVerify(exactly = 1) { recordDao.insertRecord(record) }
    }

    @Test
    fun `updateRecord delegates to recordDao`() = runTest {
        repository.updateRecord(record)
        coVerify(exactly = 1) { recordDao.updateRecord(record) }
    }

    @Test
    fun `deleteRecord delegates to recordDao`() = runTest {
        repository.deleteRecord("rec1")
        coVerify(exactly = 1) { recordDao.deleteRecord("rec1") }
    }

    @Test
    fun `observeAllRecords returns flow from recordDao`() {
        val expected = flowOf(listOf(record))
        every { recordDao.observeAllRecords() } returns expected

        val result = repository.observeAllRecords()

        assertSame(expected, result)
    }
}
