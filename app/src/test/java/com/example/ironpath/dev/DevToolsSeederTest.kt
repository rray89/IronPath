package com.example.ironpath.dev

import androidx.room.withTransaction
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.onboarding.OnboardingRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DevToolsSeederTest {

    private lateinit var database: IronPathDatabase
    private lateinit var historyDao: HistoryDao
    private lateinit var onboardingRepository: OnboardingRepository
    private lateinit var backupStore: RoomBackupStore
    private lateinit var planRepository: PlanRepository
    private lateinit var recordRepository: RecordRepository

    @Before
    fun setUp() {
        database = mockk(relaxed = true)
        historyDao = mockk(relaxed = true)
        onboardingRepository = mockk()
        backupStore = mockk(relaxed = true)
        planRepository = mockk(relaxed = true)
        recordRepository = mockk(relaxed = true)

        every { database.historyDao() } returns historyDao
        coEvery { historyDao.countLogsWithSourcePlannedWorkoutId(any()) } returns 0
        coEvery { onboardingRepository.reset() } returns true
        coEvery { planRepository.getActivePlan() } returns null

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers
            {
                secondArg<suspend () -> Unit>().invoke()
            }
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun `seedPlanForToday uses fixed date timestamp and ids for the complete plan graph`() =
        runTest {
            val timeProvider = FakeTimeProvider()
            val plan = slot<WeeklyPlan>()
            val workouts = slot<List<PlannedWorkout>>()
            val exercises = slot<List<PlannedExercise>>()
            coEvery {
                planRepository.createPlan(
                    capture(plan),
                    capture(workouts),
                    capture(exercises),
                )
            } returns Unit

            seeder(timeProvider = timeProvider).seedPlanForToday()

            assertEquals("test-id-1", plan.captured.id)
            assertEquals("2026-07-13", plan.captured.startDate)
            assertEquals("2026-07-19", plan.captured.endDate)
            assertEquals(timeProvider.epochMillis(), plan.captured.createdAt)
            assertEquals(listOf(1, 4, 6), workouts.captured.map { it.dayOfWeek })
            assertEquals(
                listOf("test-id-2", "test-id-6", "test-id-10"),
                workouts.captured.map { it.id },
            )
            assertEquals(
                listOf(
                    "test-id-3",
                    "test-id-4",
                    "test-id-5",
                    "test-id-7",
                    "test-id-8",
                    "test-id-9",
                    "test-id-11",
                    "test-id-12",
                    "test-id-13",
                ),
                exercises.captured.map { it.id },
            )
            assertTrue(
                exercises.captured.all { exercise ->
                    workouts.captured.any { it.id == exercise.plannedWorkoutId }
                }
            )
        }

    @Test
    fun `seedPlanForTomorrow anchors the plan to the provider tomorrow`() = runTest {
        val plan = slot<WeeklyPlan>()
        val workouts = slot<List<PlannedWorkout>>()
        val exercises = slot<List<PlannedExercise>>()
        coEvery {
            planRepository.createPlan(
                capture(plan),
                capture(workouts),
                capture(exercises),
            )
        } returns Unit

        seeder().seedPlanForTomorrow()

        assertEquals("2026-07-13", plan.captured.startDate)
        assertTrue(workouts.captured.any { it.dayOfWeek == 5 && it.scheduledDate == "2026-07-17" })
    }

    @Test
    fun `seedHistoryLogs uses fixed time and unique provider ids for every snapshot row`() =
        runTest {
            val timeProvider = FakeTimeProvider()
            val logs = mutableListOf<WorkoutLog>()
            val exerciseBatches = mutableListOf<List<LoggedExercise>>()
            val setBatches = mutableListOf<List<LoggedSet>>()
            coEvery { historyDao.insertLog(capture(logs)) } returns Unit
            coEvery { historyDao.insertLoggedExercises(capture(exerciseBatches)) } returns Unit
            coEvery { historyDao.insertLoggedSets(capture(setBatches)) } returns Unit

            seeder(timeProvider = timeProvider).seedHistoryLogs()

            val exercises = exerciseBatches.flatten()
            val sets = setBatches.flatten()
            val expectedCompletedAt = timeProvider.epochMillis() - DAY_MILLIS
            assertEquals(5, logs.size)
            assertEquals(15, exercises.size)
            assertEquals(45, sets.size)
            assertEquals(expectedCompletedAt, logs.first().completedAt)
            assertEquals(expectedCompletedAt - 45 * 60_000L, logs.first().startedAt)
            assertEquals(expectedCompletedAt - 2 * 60_000L, sets.first().completedAt)

            val ids = logs.map { it.id } + exercises.map { it.id } + sets.map { it.id }
            assertEquals(65, ids.size)
            assertEquals(65, ids.toSet().size)
            assertEquals((1..65).map { "test-id-$it" }.toSet(), ids.toSet())
        }

    @Test
    fun `seedRecords uses fixed dates ids and creation timestamp`() = runTest {
        val timeProvider = FakeTimeProvider()
        val records = mutableListOf<PersonalRecord>()
        coEvery { recordRepository.insertRecord(capture(records)) } returns Unit

        seeder(timeProvider = timeProvider).seedRecords()

        assertEquals(
            listOf(
                "2026-07-15",
                "2026-07-13",
                "2026-07-09",
                "2026-07-06",
                "2026-07-02",
            ),
            records.map { it.achievedOn },
        )
        assertEquals((1..5).map { "test-id-$it" }, records.map { it.id })
        assertTrue(records.all { it.createdAt == timeProvider.epochMillis() })
    }

    @Test
    fun `seedHistoryLogs does not append duplicate seed logs`() = runTest {
        coEvery { historyDao.countLogsWithSourcePlannedWorkoutId("__dev_seed_history__") } returns 1

        var thrown: IllegalStateException? = null
        try {
            seeder().seedHistoryLogs()
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

        seeder().seedHistoryLogs()

        coVerify(exactly = 1) { database.withTransaction(any<suspend () -> Unit>()) }
    }

    @Test
    fun `clearAllData resets onboarding before clearing Room`() = runTest {
        val operations = mutableListOf<String>()
        coEvery { onboardingRepository.reset() } coAnswers
            {
                operations += "onboarding"
                true
            }
        coEvery { backupStore.resetLocalProfile() } coAnswers
            {
                operations += "room"
                Unit
            }

        seeder().clearAllData()

        assertEquals(listOf("onboarding", "room"), operations)
    }

    @Test
    fun `clearAllData preserves Room when onboarding reset fails`() = runTest {
        coEvery { onboardingRepository.reset() } returns false

        var thrown: IllegalStateException? = null
        try {
            seeder().clearAllData()
        } catch (error: IllegalStateException) {
            thrown = error
        }

        assertEquals("Failed to reset onboarding", thrown?.message)
        coVerify(exactly = 0) { backupStore.resetLocalProfile() }
    }

    @Test
    fun `clearAllData surfaces Room failure after onboarding reset succeeds`() = runTest {
        coEvery { backupStore.resetLocalProfile() } throws
            IllegalStateException("Room clear failed")

        var thrown: IllegalStateException? = null
        try {
            seeder().clearAllData()
        } catch (error: IllegalStateException) {
            thrown = error
        }

        assertEquals("Room clear failed", thrown?.message)
        coVerify(exactly = 1) { onboardingRepository.reset() }
        coVerify(exactly = 1) { backupStore.resetLocalProfile() }
    }

    private fun seeder(
        timeProvider: FakeTimeProvider = FakeTimeProvider(),
        idProvider: FakeIdProvider = FakeIdProvider(),
    ) =
        DevToolsSeeder(
            database = database,
            onboardingRepository = onboardingRepository,
            backupStore = backupStore,
            planRepository = planRepository,
            recordRepository = recordRepository,
            timeProvider = timeProvider,
            idProvider = idProvider,
        )

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
