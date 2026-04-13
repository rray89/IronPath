package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.entity.WorkoutLog
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
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
}
