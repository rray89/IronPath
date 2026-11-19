package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.AccountDeletionDao
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.domain.session.StartWorkoutResult
import com.example.ironpath.testutil.FakeIdProvider
import com.example.ironpath.testutil.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {

    private lateinit var sessionDao: SessionDao
    private lateinit var historyDao: HistoryDao
    private lateinit var planDao: PlanDao
    private lateinit var database: IronPathDatabase
    private lateinit var accountDeletionDao: AccountDeletionDao
    private lateinit var performanceTracer: PerformanceTracer
    private lateinit var backupChangeTracker: BackupChangeTracker
    private lateinit var repository: SessionRepository

    private val session =
        ActiveSession(
            id = "session1",
            sourcePlannedWorkoutId = "workout1",
            workoutTitle = "Push A",
            startedAt = 1_000L,
            lastUpdatedAt = 1_000L,
        )

    private val sessionExercise =
        SessionExercise(
            id = "sex1",
            activeSessionId = "session1",
            name = "Bench Press",
            plannedSets = 3,
            plannedReps = 10,
            plannedWeightKg = 60.0,
            orderIndex = 0,
        )

    private val sessionSet =
        SessionSet(
            id = "set1",
            sessionExerciseId = "sex1",
            setNumber = 1,
            reps = 10,
            weightKg = 60.0,
            completedAt = 4_000L,
        )

    private val log =
        WorkoutLog(
            id = "log1",
            title = "Push A",
            sourcePlannedWorkoutId = "workout1",
            startedAt = 1000L,
            completedAt = 4600L,
            durationMinutes = 1,
            exerciseCount = 1,
        )

    @Before
    fun setUp() {
        sessionDao = mockk()
        historyDao = mockk()
        planDao = mockk()
        database = mockk()
        accountDeletionDao = mockk()
        every { database.accountDeletionDao() } returns accountDeletionDao
        coEvery { accountDeletionDao.getJournal() } returns null
        performanceTracer = mockk(relaxed = true)
        backupChangeTracker = mockk()
        every { performanceTracer.beginAsyncSection(any()) } returns 1

        coEvery { sessionDao.insertSession(any()) } returns Unit
        coEvery { sessionDao.insertSessionExercises(any()) } returns Unit
        coEvery { sessionDao.insertSets(any()) } returns Unit
        coEvery { sessionDao.updateSet(any()) } returns Unit
        coEvery { sessionDao.insertSet(any()) } returns Unit
        coEvery { sessionDao.getExercisesForSession(any()) } returns emptyList()
        coEvery { sessionDao.getActiveSession() } returns session
        coEvery { historyDao.insertLog(any()) } returns Unit
        coEvery { historyDao.insertLoggedExercises(any()) } returns Unit
        coEvery { historyDao.insertLoggedSets(any()) } returns Unit
        coEvery { sessionDao.deleteSession(any()) } returns Unit
        coEvery { planDao.markWorkoutCompleted(any()) } returns Unit
        coEvery { backupChangeTracker.markIncludedDataChanged() } returns Unit

        mockkStatic("androidx.room.RoomDatabaseKt")
        // withTransaction is compiled as a static extension:
        //   arg0 = receiver (IronPathDatabase), arg1 = suspend lambda block.
        // secondArg<>() retrieves arg1 so we can invoke it to exercise the lambda body.
        coEvery { database.withTransaction(any<suspend () -> Any?>()) } coAnswers
            {
                secondArg<suspend () -> Any?>().invoke()
            }

        repository =
            SessionRepository(
                sessionDao,
                historyDao,
                planDao,
                database,
                performanceTracer,
                backupChangeTracker,
                FakeTimeProvider(),
                FakeIdProvider(),
            )
    }

    @Test
    fun `observeActiveSession returns flow from sessionDao`() {
        val expected = flowOf(session)
        every { sessionDao.observeActiveSession() } returns expected

        val result = repository.observeActiveSession()

        assertSame(expected, result)
    }

    @Test
    fun `starting while a session exists preserves that session`() = runTest {
        assertEquals(
            StartWorkoutResult.ExistingSession(session.id),
            repository.startPlannedWorkout("other")
        )
        coVerify(exactly = 0) { sessionDao.insertSession(any()) }
        coVerify(exactly = 0) { sessionDao.deleteSession(any()) }
        coVerify(exactly = 0) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `start rechecks source and builds all default sets from stored prescription`() = runTest {
        stubStartable()
        assertEquals(
            StartWorkoutResult.Started("test-id-1"),
            repository.startPlannedWorkout("workout1")
        )
        coVerify {
            sessionDao.insertSession(
                match {
                    it.workoutTitle == "Stored title" &&
                        it.startedAt == it.lastUpdatedAt &&
                        it.startedAt == FakeTimeProvider().epochMillis()
                }
            )
        }
        coVerify {
            sessionDao.insertSessionExercises(
                match {
                    it.single().plannedSets == 2 &&
                        it.single().orderIndex == 3 &&
                        it.single().name == "Squat"
                }
            )
        }
        coVerify {
            sessionDao.insertSets(
                match {
                    it.map { set -> set.setNumber } == listOf(1, 2) &&
                        it.all { set ->
                            set.sessionExerciseId == "test-id-2" &&
                                set.weightKg == 42.5 &&
                                set.reps == null &&
                                !set.isExtra &&
                                set.completedAt == null
                        }
                }
            )
        }
        coVerify(exactly = 0) { backupChangeTracker.markIncludedDataChanged() }
    }

    @Test
    fun `missing completed future malformed or archived sources cannot start`() = runTest {
        stubStartable()
        val source = planDao.getWorkoutById("workout1")!!
        for (workout in
            listOf(
                null,
                source.copy(status = WorkoutStatus.Completed),
                source.copy(status = WorkoutStatus.Skipped),
                source.copy(scheduledDate = "2026-07-17"),
                source.copy(scheduledDate = "2026-07-15"),
                source.copy(scheduledDate = "bad"),
                source.copy(weeklyPlanId = "archived")
            )) {
            coEvery { planDao.getWorkoutById("workout1") } returns workout
            assertEquals(
                StartWorkoutResult.NotStartable,
                repository.startPlannedWorkout("workout1")
            )
        }
        coVerify(exactly = 0) { sessionDao.insertSession(any()) }
    }

    private fun stubStartable() {
        coEvery { sessionDao.getActiveSession() } returns null
        coEvery { planDao.getWorkoutById("workout1") } returns
            PlannedWorkout("workout1", "plan1", 4, "2026-07-16", "Stored title")
        coEvery { planDao.getActivePlan() } returns
            WeeklyPlan(
                id = "plan1",
                startDate = "2026-07-13",
                endDate = "2026-07-19",
                createdAt = 1L
            )
        coEvery { planDao.getExercisesForWorkout("workout1") } returns
            listOf(PlannedExercise("planned", "workout1", "Squat", 2, 5, 42.5, 3))
    }

    @Test
    fun `updateSet delegates to sessionDao`() = runTest {
        repository.updateSet(sessionSet)
        coVerify(exactly = 1) { sessionDao.updateSet(sessionSet) }
    }

    @Test
    fun `insertSet delegates to sessionDao`() = runTest {
        repository.insertSet(sessionSet)
        coVerify(exactly = 1) { sessionDao.insertSet(sessionSet) }
    }

    @Test
    fun `completeSession deletes session and inserts log`() = runTest {
        repository.completeSession("session1", log)

        coVerify(exactly = 1) { sessionDao.deleteSession("session1") }
        coVerify(exactly = 1) { historyDao.insertLog(log) }
        verify(exactly = 1) { performanceTracer.beginAsyncSection("IronPath#completeSession") }
        verify(exactly = 1) { performanceTracer.endAsyncSection("IronPath#completeSession", 1) }
    }

    @Test
    fun `completeSession trace spans transaction acquisition and commit`() = runTest {
        val events = mutableListOf<String>()
        every { performanceTracer.beginAsyncSection("IronPath#completeSession") } answers
            {
                events += "trace-begin"
                7
            }
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers
            {
                events += "transaction-start"
                secondArg<suspend () -> Unit>().invoke()
                events += "transaction-end"
            }
        every { performanceTracer.endAsyncSection("IronPath#completeSession", 7) } answers
            {
                events += "trace-end"
            }

        repository.completeSession("session1", log)

        assertEquals(
            listOf("trace-begin", "transaction-start", "transaction-end", "trace-end"),
            events,
        )
    }

    @Test
    fun `completeSession snapshots exercises and sets for workout log detail`() = runTest {
        coEvery { sessionDao.getExercisesForSession("session1") } returns listOf(sessionExercise)
        coEvery { sessionDao.getSetsForExercises(listOf("sex1")) } returns listOf(sessionSet)

        repository.completeSession("session1", log)

        coVerify(exactly = 1) {
            historyDao.insertLoggedExercises(
                listOf(
                    LoggedExercise(
                        id = "sex1",
                        workoutLogId = log.id,
                        name = "Bench Press",
                        plannedSets = 3,
                        plannedReps = 10,
                        plannedWeightKg = 60.0,
                        orderIndex = 0,
                    ),
                ),
            )
        }
        coVerify(exactly = 1) {
            historyDao.insertLoggedSets(
                listOf(
                    LoggedSet(
                        id = "set1",
                        loggedExerciseId = "sex1",
                        setNumber = 1,
                        reps = 10,
                        weightKg = 60.0,
                        isExtra = false,
                        completedAt = 4_000L,
                    ),
                ),
            )
        }
    }

    @Test
    fun `completeSession marks source workout completed when a stored set has both values`() =
        runTest {
            coEvery { sessionDao.getExercisesForSession("session1") } returns
                listOf(sessionExercise)
            coEvery { sessionDao.getSetsForExercises(listOf("sex1")) } returns listOf(sessionSet)

            repository.completeSession("session1", log)

            coVerify(exactly = 1) { planDao.markWorkoutCompleted("workout1") }
        }

    @Test
    fun `completeSession leaves source workout unchanged when no stored set is complete`() =
        runTest {
            coEvery { sessionDao.getExercisesForSession("session1") } returns
                listOf(sessionExercise)
            coEvery { sessionDao.getSetsForExercises(listOf("sex1")) } returns
                listOf(sessionSet.copy(weightKg = null, completedAt = null))

            repository.completeSession("session1", log)

            coVerify(exactly = 0) { planDao.markWorkoutCompleted(any()) }
        }

    @Test
    fun `completeSession rejects a missing session before writing history`() = runTest {
        coEvery { sessionDao.getActiveSession() } returns null

        val result = runCatching { repository.completeSession("session1", log) }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        coVerify(exactly = 0) { historyDao.insertLog(any()) }
        coVerify(exactly = 0) { sessionDao.deleteSession(any()) }
        verify(exactly = 1) { performanceTracer.endAsyncSection("IronPath#completeSession", 1) }
    }
}
