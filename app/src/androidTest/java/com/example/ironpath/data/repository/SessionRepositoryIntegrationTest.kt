package com.example.ironpath.data.repository

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.entity.PlanStatus
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WorkoutStatus
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionRepositoryIntegrationTest {
    @get:Rule val room = RoomTestDatabaseRule()
    @get:Rule val fileRoom = FileBackedRoomTestDatabaseRule()

    @Test
    fun completeSession_snapshotsGraph_marksWorkoutCompleted_andDeletesActiveGraph() = runBlocking {
        val set =
            TestData.sessionSet(
                reps = 5,
                weightKg = 100.0,
                completedAt = TestData.BASE_TIME + 10_000,
            )
        seedActiveGraph(room.database, listOf(set))
        val repository = repository(room.database)
        val log = TestData.log()

        repository.completeSession("session-a", log)

        assertEquals(
            WorkoutStatus.Completed,
            room.database.planDao().getWorkoutById("workout-a")?.status
        )
        assertEquals(log, room.database.historyDao().getLogById("log-a"))
        assertEquals(
            listOf(TestData.loggedExercise(id = "session-exercise-a")),
            room.database.historyDao().getLoggedExercisesForLog("log-a"),
        )
        assertEquals(
            listOf(
                TestData.loggedSet(
                    id = "session-set-a",
                    exerciseId = "session-exercise-a",
                    reps = 5,
                    weightKg = 100.0,
                    completedAt = TestData.BASE_TIME + 10_000,
                )
            ),
            room.database.historyDao().getLoggedSetsForExercises(listOf("session-exercise-a")),
        )
        assertNull(room.database.sessionDao().getActiveSession())
        assertTrue(room.database.sessionDao().getExercisesForSession("session-a").isEmpty())
        assertTrue(
            room.database.sessionDao().getSetsForExercises(listOf("session-exercise-a")).isEmpty()
        )
    }

    @Test
    fun completeSession_withNoExercises_writesLog_deletesSession_andLeavesWorkoutUpcoming() =
        runBlocking {
            seedPlan(room.database)
            room.database.sessionDao().startNewSession(TestData.session(), emptyList())

            repository(room.database).completeSession("session-a", TestData.log(exerciseCount = 0))

            assertNotNull(room.database.historyDao().getLogById("log-a"))
            assertNull(room.database.sessionDao().getActiveSession())
            assertEquals(
                WorkoutStatus.Upcoming,
                room.database.planDao().getWorkoutById("workout-a")?.status
            )
            assertTrue(room.database.historyDao().getLoggedExercisesForLog("log-a").isEmpty())
        }

    @Test
    fun completeSession_withOnlyIncompleteSets_leavesWorkoutUpcoming() = runBlocking {
        val sets =
            listOf(
                TestData.sessionSet(id = "set-reps", setNumber = 1, reps = 5),
                TestData.sessionSet(id = "set-weight", setNumber = 2, weightKg = 100.0),
                TestData.sessionSet(id = "set-blank", setNumber = 3),
            )
        seedActiveGraph(room.database, sets)

        repository(room.database).completeSession("session-a", TestData.log())

        assertEquals(
            WorkoutStatus.Upcoming,
            room.database.planDao().getWorkoutById("workout-a")?.status,
        )
        assertEquals(
            sets.map { it.toExpectedLoggedSet() },
            room.database.historyDao().getLoggedSetsForExercises(listOf("session-exercise-a")),
        )
        assertNull(room.database.sessionDao().getActiveSession())
    }

    @Test
    fun completeSession_preservesUnfinishedAndExtraSetsExactly() = runBlocking {
        val sets =
            listOf(
                TestData.sessionSet(id = "set-reps", setNumber = 1, reps = 5),
                TestData.sessionSet(id = "set-weight", setNumber = 2, weightKg = 100.0),
                TestData.sessionSet(id = "set-blank", setNumber = 3),
                TestData.sessionSet(
                    id = "set-extra",
                    setNumber = 4,
                    reps = 3,
                    weightKg = 110.0,
                    isExtra = true,
                    completedAt = TestData.BASE_TIME + 20_000,
                ),
            )
        seedActiveGraph(room.database, sets)

        repository(room.database).completeSession("session-a", TestData.log())

        assertEquals(
            WorkoutStatus.Completed,
            room.database.planDao().getWorkoutById("workout-a")?.status
        )
        assertEquals(
            sets.map { it.toExpectedLoggedSet() },
            room.database.historyDao().getLoggedSetsForExercises(listOf("session-exercise-a")),
        )
    }

    @Test
    fun completeSession_duplicateLogId_rollsBackPlanAndKeepsActiveGraph() = runBlocking {
        val set = TestData.sessionSet(reps = 5, weightKg = 100.0)
        seedActiveGraph(room.database, listOf(set))
        val existingLog = TestData.log(title = "Existing")
        room.database.historyDao().insertLog(existingLog)

        val error =
            runCatching {
                    repository(room.database)
                        .completeSession(
                            "session-a",
                            TestData.log(title = "Replacement"),
                        )
                }
                .exceptionOrNull()

        assertTrue(error is SQLiteException)
        assertEquals(
            WorkoutStatus.Upcoming,
            room.database.planDao().getWorkoutById("workout-a")?.status
        )
        assertEquals(TestData.session(), room.database.sessionDao().getActiveSession())
        assertEquals(
            listOf(TestData.sessionExercise()),
            room.database.sessionDao().getExercisesForSession("session-a")
        )
        assertEquals(
            listOf(set),
            room.database.sessionDao().getSetsForExercises(listOf("session-exercise-a"))
        )
        assertEquals(existingLog, room.database.historyDao().getLogById("log-a"))
        assertTrue(room.database.historyDao().getLoggedExercisesForLog("log-a").isEmpty())
    }

    @Test
    fun completionStatusFailure_rollsBackHistoryAndActiveGraph() = runBlocking {
        val set = TestData.sessionSet(reps = 5, weightKg = 100.0)
        seedActiveGraph(room.database, listOf(set))
        room.database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_completion
            BEFORE UPDATE OF status ON planned_workouts
            WHEN NEW.id = 'workout-a' AND NEW.status = 'Completed'
            BEGIN
                SELECT RAISE(ABORT, 'forced completion failure');
            END
            """
                .trimIndent()
        )

        val error =
            runCatching { repository(room.database).completeSession("session-a", TestData.log()) }
                .exceptionOrNull()

        assertTrue(error is SQLiteException)
        assertEquals(
            WorkoutStatus.Upcoming,
            room.database.planDao().getWorkoutById("workout-a")?.status
        )
        assertEquals(TestData.session(), room.database.sessionDao().getActiveSession())
        assertEquals(
            listOf(set),
            room.database.sessionDao().getSetsForExercises(listOf("session-exercise-a"))
        )
        assertNull(room.database.historyDao().getLogById("log-a"))
        assertTrue(room.database.historyDao().getLoggedExercisesForLog("log-a").isEmpty())
    }

    @Test
    fun lateSnapshotCollision_rollsBackPlanLogAndActiveGraph() = runBlocking {
        val collidingSet = TestData.sessionSet(id = "shared-set", reps = 5, weightKg = 100.0)
        seedActiveGraph(room.database, listOf(collidingSet))
        room.database.historyDao().insertLog(TestData.log(id = "old-log", title = "Old"))
        room.database
            .historyDao()
            .insertLoggedExercises(
                listOf(TestData.loggedExercise(id = "old-exercise", logId = "old-log"))
            )
        val oldSet =
            TestData.loggedSet(
                id = "shared-set",
                exerciseId = "old-exercise",
                reps = 1,
                weightKg = 1.0
            )
        room.database.historyDao().insertLoggedSets(listOf(oldSet))

        val error =
            runCatching { repository(room.database).completeSession("session-a", TestData.log()) }
                .exceptionOrNull()

        assertTrue(error is SQLiteException)
        assertEquals(
            WorkoutStatus.Upcoming,
            room.database.planDao().getWorkoutById("workout-a")?.status
        )
        assertEquals(TestData.session(), room.database.sessionDao().getActiveSession())
        assertEquals(
            listOf(collidingSet),
            room.database.sessionDao().getSetsForExercises(listOf("session-exercise-a"))
        )
        assertNull(room.database.historyDao().getLogById("log-a"))
        assertTrue(room.database.historyDao().getLoggedExercisesForLog("log-a").isEmpty())
        assertEquals(
            listOf(oldSet),
            room.database.historyDao().getLoggedSetsForExercises(listOf("old-exercise"))
        )
    }

    @Test
    fun secondRepositoryCompletion_isRejectedWithoutCreatingAnotherLog() = runBlocking {
        seedActiveGraph(room.database, listOf(TestData.sessionSet(reps = 5, weightKg = 100.0)))
        val repository = repository(room.database)
        repository.completeSession("session-a", TestData.log())

        val error =
            runCatching {
                    repository.completeSession(
                        "session-a",
                        TestData.log(id = "log-b", title = "Duplicate"),
                    )
                }
                .exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertNotNull(room.database.historyDao().getLogById("log-a"))
        assertNull(room.database.historyDao().getLogById("log-b"))
    }

    @Test
    fun reopeningFileBackedDatabase_preservesCompletionAndNoActiveSession() = runBlocking {
        val first = fileRoom.open()
        seedActiveGraph(first, listOf(TestData.sessionSet(reps = 5, weightKg = 100.0)))
        repository(first).completeSession("session-a", TestData.log())
        first.close()

        val reopened = fileRoom.open()

        assertEquals(
            WorkoutStatus.Completed,
            reopened.planDao().getWorkoutById("workout-a")?.status
        )
        assertNotNull(reopened.historyDao().getLogById("log-a"))
        assertEquals(1, reopened.historyDao().getLoggedExercisesForLog("log-a").size)
        assertEquals(
            1,
            reopened.historyDao().getLoggedSetsForExercises(listOf("session-exercise-a")).size
        )
        assertNull(reopened.sessionDao().getActiveSession())
    }

    private fun repository(database: IronPathDatabase) =
        SessionRepository(
            database.sessionDao(),
            database.historyDao(),
            database.planDao(),
            database,
            PerformanceTracer(),
            RoomBackupStore(database, SequenceIdProvider("session-repository")),
        )

    private suspend fun seedPlan(database: IronPathDatabase) {
        database
            .planDao()
            .createPlanWithWorkouts(
                TestData.plan(status = PlanStatus.Active),
                listOf(TestData.workout()),
                listOf(TestData.plannedExercise()),
            )
    }

    private suspend fun seedActiveGraph(database: IronPathDatabase, sets: List<SessionSet>) {
        seedPlan(database)
        database
            .sessionDao()
            .startNewSession(
                TestData.session(),
                listOf(TestData.sessionExercise()),
            )
        sets.forEach { database.sessionDao().insertSet(it) }
    }

    private fun SessionSet.toExpectedLoggedSet() =
        TestData.loggedSet(
            id = id,
            exerciseId = sessionExerciseId,
            setNumber = setNumber,
            reps = reps,
            weightKg = weightKg,
            isExtra = isExtra,
            completedAt = completedAt,
        )
}
