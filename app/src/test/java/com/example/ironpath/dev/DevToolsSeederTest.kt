package com.example.ironpath.dev

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DevToolsSeederTest {

    private lateinit var database: IronPathDatabase
    private lateinit var historyDao: HistoryDao
    private lateinit var seeder: DevToolsSeeder

    @Before
    fun setUp() {
        database = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)
        every { database.historyDao() } returns historyDao

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers
            {
                secondArg<suspend () -> Unit>().invoke()
            }

        seeder =
            DevToolsSeeder(
                database = database,
                planRepository = mockk<PlanRepository>(relaxed = true),
                recordRepository = mockk<RecordRepository>(relaxed = true),
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `seedHistoryLogs does not append duplicate seed logs`() = runTest {
        coEvery { historyDao.countLogsWithSourcePlannedWorkoutId("__dev_seed_history__") } returns 1

        var thrown: IllegalStateException? = null
        try {
            seeder.seedHistoryLogs()
        } catch (error: IllegalStateException) {
            thrown = error
        }

        assertEquals("History logs already seeded", thrown?.message)
        coVerify(exactly = 1) { database.withTransaction(any<suspend () -> Unit>()) }
        coVerify(exactly = 0) { historyDao.insertLog(any()) }
    }

    @Test
    fun `seedHistoryLogs ignores real logs that share seed titles`() = runTest {
        coEvery { historyDao.countLogsWithTitles(any()) } returns 1
        coEvery { historyDao.countLogsWithSourcePlannedWorkoutId("__dev_seed_history__") } returns 0

        seeder.seedHistoryLogs()

        coVerify(exactly = 1) { database.withTransaction(any<suspend () -> Unit>()) }
    }
}
