package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WorkoutLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SessionRepositoryTest {

  private lateinit var sessionDao: SessionDao
  private lateinit var historyDao: HistoryDao
  private lateinit var database: IronPathDatabase
  private lateinit var repository: SessionRepository

  private val session =
    ActiveSession(
      id = "session1",
      sourcePlannedWorkoutId = "workout1",
      workoutTitle = "Push A",
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
    )

  private val log =
    WorkoutLog(
      title = "Push A",
      sourcePlannedWorkoutId = "workout1",
      startedAt = 1000L,
      completedAt = 4600L,
      durationMinutes = 1,
      exerciseCount = 1,
    )

  @Before
  fun setUp() {
    sessionDao = mockk(relaxed = true)
    historyDao = mockk(relaxed = true)
    database = mockk(relaxed = true)

    mockkStatic("androidx.room.RoomDatabaseKt")
    // withTransaction is compiled as a static extension:
    //   arg0 = receiver (IronPathDatabase), arg1 = suspend lambda block.
    // secondArg<>() retrieves arg1 so we can invoke it to exercise the lambda body.
    coEvery { database.withTransaction(any<suspend () -> Unit>()) } coAnswers
      {
        secondArg<suspend () -> Unit>().invoke()
      }

    repository = SessionRepository(sessionDao, historyDao, database)
  }

  @Test
  fun `observeActiveSession returns flow from sessionDao`() {
    val expected = flowOf(session)
    every { sessionDao.observeActiveSession() } returns expected

    val result = repository.observeActiveSession()

    assert(result === expected)
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
  fun `completeSession deletes session and inserts log within a transaction`() = runTest {
    repository.completeSession("session1", log)

    coVerify(exactly = 1) { sessionDao.deleteSession("session1") }
    coVerify(exactly = 1) { historyDao.insertLog(log) }
  }
}
