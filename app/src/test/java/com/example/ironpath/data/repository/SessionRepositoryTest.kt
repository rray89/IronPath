package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.performance.PerformanceTracer
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
    private lateinit var performanceTracer: PerformanceTracer
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
        performanceTracer = mockk(relaxed = true)
        every { performanceTracer.beginAsyncSection(any()) } returns 1

        coEvery { sessionDao.startNewSession(any(), any()) } returns Unit
        coEvery { sessionDao.updateSet(any()) } returns Unit
        coEvery { sessionDao.insertSet(any()) } returns Unit
        coEvery { sessionDao.getExercisesForSession(any()) } returns emptyList()
        coEvery { sessionDao.getActiveSession() } returns session
        coEvery { historyDao.insertLog(any()) } returns Unit
        coEvery { historyDao.insertLoggedExercises(any()) } returns Unit
        coEvery { historyDao.insertLoggedSets(any()) } returns Unit
        coEvery { sessionDao.deleteSession(any()) } returns Unit
        coEvery { planDao.markWorkoutCompleted(any()) } returns Unit

        mockkStatic("androidx.room.RoomDatabaseKt")
        // withTransaction is compiled as a static extension:
        //   arg0 = receiver (IronPathDatabase), arg1 = suspend lambda block.
        // secondArg<>() retrieves arg1 so we can invoke it to exercise the lambda body.
        coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers
            {
                secondArg<suspend () -> Unit>().invoke()
            }

        repository = SessionRepository(sessionDao, historyDao, planDao, database, performanceTracer)
    }

    @Test
    fun `observeActiveSession returns flow from sessionDao`() {
        val expected = flowOf(session)
        every { sessionDao.observeActiveSession() } returns expected

        val result = repository.observeActiveSession()

        assertSame(expected, result)
    }

    @Test
    fun `startSession delegates to sessionDao startNewSession`() = runTest {
        repository.startSession(session, listOf(sessionExercise))
        coVerify(exactly = 1) { sessionDao.startNewSession(session, listOf(sessionExercise)) }
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
