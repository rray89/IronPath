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
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `seedHistoryLogs does not append duplicate seed logs`() = runTest {
        coEvery { historyDao.countLogsWithTitles(any()) } returns 1

        try {
            seeder.seedHistoryLogs()
        } catch (_: IllegalStateException) {
            // Expected: the seed is already present.
        }

        coVerify(exactly = 0) { database.withTransaction(any<suspend () -> Unit>()) }
    }
}
