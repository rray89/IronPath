package com.example.ironpath.data.repository

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.data.local.AccountDeletionInProgressException
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.StaleProfileGenerationException
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.AccountDeletionJournal
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.performance.PerformanceTracer
import com.example.ironpath.domain.session.StartWorkoutResult
import com.example.ironpath.testutil.FileBackedRoomTestDatabaseRule
import com.example.ironpath.testutil.MutableTimeProvider
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStartIntegrationTest {
    @get:Rule val room = RoomTestDatabaseRule()
    @get:Rule val fileRoom = FileBackedRoomTestDatabaseRule()
    private val clock =
        MutableTimeProvider(Instant.parse("2026-07-13T19:00:00Z"), ZoneId.of("America/Vancouver"))

    private fun repository(db: IronPathDatabase, dao: SessionDao = db.sessionDao()) =
        SessionRepository(
            dao,
            db.historyDao(),
            db.planDao(),
            db,
            PerformanceTracer(),
            RoomBackupStore(db, SequenceIdProvider("metadata")),
            clock,
            SequenceIdProvider("start"),
        )

    private suspend fun seed(db: IronPathDatabase) {
        db.planDao()
            .createPlanWithWorkouts(
                TestData.plan(),
                listOf(TestData.workout()),
                listOf(
                    TestData.plannedExercise(
                        id = "second",
                        name = "Bench",
                        sets = 1,
                        orderIndex = 1
                    ),
                    TestData.plannedExercise(id = "first", sets = 2, orderIndex = 0),
                )
            )
        RoomBackupStore(db, SequenceIdProvider("metadata")).capture()
    }

    @Test
    fun startCommitsOrderedCompleteGraph_andReopenRecoversIt_withoutChangingBackupRevision() =
        runBlocking {
            val db = fileRoom.open()
            seed(db)
            val before = db.backupDao().getMetadata()
            val result =
                repository(db).startPlannedWorkout("workout-a", 0L) as StartWorkoutResult.Started
            val session = checkNotNull(db.sessionDao().getActiveSession())
            val exercises = db.sessionDao().getExercisesForSession(result.sessionId)
            assertEquals(listOf("Squat", "Bench"), exercises.map { it.name })
            assertEquals(listOf(0, 1), exercises.map { it.orderIndex })
            val sets = db.sessionDao().getSetsForExercises(exercises.map { it.id })
            assertEquals(
                listOf(1, 2),
                sets.filter { it.sessionExerciseId == exercises[0].id }.map { it.setNumber }
            )
            assertEquals(1, sets.count { it.sessionExerciseId == exercises[1].id })
            assertTrue(
                sets.all {
                    it.weightKg == 100.0 && it.reps == null && it.completedAt == null && !it.isExtra
                }
            )
            assertEquals(clock.epochMillis(), session.startedAt)
            assertEquals(session.startedAt, session.lastUpdatedAt)
            assertEquals(before, db.backupDao().getMetadata())
            db.close()
            val reopened = fileRoom.open()
            assertEquals(session, reopened.sessionDao().getActiveSession())
            assertEquals(exercises, reopened.sessionDao().getExercisesForSession(session.id))
            assertEquals(sets, reopened.sessionDao().getSetsForExercises(exercises.map { it.id }))
        }

    @Test
    fun simultaneousAndRepeatedStarts_preserveExactlyOneCompleteSessionAndLoggedProgress() =
        runBlocking {
            val db = room.database
            seed(db)
            val repo = repository(db)
            val gate = CompletableDeferred<Unit>()
            val starts =
                List(2) {
                    async(Dispatchers.Default) {
                        gate.await()
                        repo.startPlannedWorkout("workout-a")
                    }
                }
            gate.complete(Unit)
            val results = starts.map { it.await() }
            assertEquals(1, results.count { it is StartWorkoutResult.Started })
            val session = checkNotNull(db.sessionDao().getActiveSession())
            assertEquals(
                StartWorkoutResult.ExistingSession(session.id),
                results.single { it is StartWorkoutResult.ExistingSession }
            )
            val exercises = db.sessionDao().getExercisesForSession(session.id)
            val sets = db.sessionDao().getSetsForExercises(exercises.map { it.id })
            assertEquals(3, sets.size)
            val logged = sets.first().copy(reps = 8, weightKg = 125.0)
            repo.updateSet(logged)
            assertEquals(
                StartWorkoutResult.ExistingSession(session.id),
                repo.startPlannedWorkout("deleted-source")
            )
            assertEquals(
                logged,
                db.sessionDao().getSetsForExercises(exercises.map { it.id }).first {
                    it.id == logged.id
                }
            )
            assertEquals(0L, db.backupDao().getMetadata()?.localChangeRevision)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM active_sessions").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        }

    @Test
    fun deletedSourceCannotStart_andMidSetInsertFailureRollsBackEntireGraph() = runBlocking {
        val db = room.database
        seed(db)
        val repo = repository(db)
        db.planDao().deleteWorkout("workout-a")
        assertEquals(StartWorkoutResult.NotStartable, repo.startPlannedWorkout("workout-a"))
        db.planDao().insertWorkouts(listOf(TestData.workout()))
        db.planDao().insertExercises(listOf(TestData.plannedExercise()))
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_default_set BEFORE INSERT ON session_sets
            WHEN NEW.setNumber = 2 BEGIN SELECT RAISE(ABORT, 'forced second set failure'); END
        """
                .trimIndent()
        )
        val before = db.backupDao().getMetadata()
        assertNotNull(runCatching { repo.startPlannedWorkout("workout-a") }.exceptionOrNull())
        assertEmptyGraph(db)
        assertEquals(before, db.backupDao().getMetadata())
        assertNotNull(db.planDao().getWorkoutById("workout-a"))
    }

    @Test
    fun cancellationAfterFirstDefaultSet_rollsBack_thenRetryCreatesFullGraph() = runBlocking {
        val db = room.database
        seed(db)
        val inserted = CompletableDeferred<Unit>()
        val dao =
            object : SessionDao by db.sessionDao() {
                override suspend fun insertSets(sets: List<SessionSet>) {
                    db.sessionDao().insertSet(sets.first())
                    inserted.complete(Unit)
                    awaitCancellation()
                }
            }
        val job = launch { repository(db, dao).startPlannedWorkout("workout-a") }
        inserted.await()
        job.cancelAndJoin()
        assertEmptyGraph(db)
        assertTrue(repository(db).startPlannedWorkout("workout-a") is StartWorkoutResult.Started)
        val exercises =
            db.sessionDao()
                .getExercisesForSession(checkNotNull(db.sessionDao().getActiveSession()).id)
        assertEquals(3, db.sessionDao().getSetsForExercises(exercises.map { it.id }).size)
    }

    @Test
    fun queuedStartRechecksProfileGenerationAndDeletionBarrierInsideTransaction() = runBlocking {
        val db = room.database
        seed(db)
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val reset = launch {
            db.withTransaction {
                held.complete(Unit)
                release.await()
                db.backupDao()
                    .updateMetadata(
                        checkNotNull(db.backupDao().getMetadata()).copy(profileGeneration = 1)
                    )
            }
        }
        held.await()
        val queued = async { runCatching { repository(db).startPlannedWorkout("workout-a", 0L) } }
        release.complete(Unit)
        reset.join()
        assertTrue(queued.await().exceptionOrNull() is StaleProfileGenerationException)
        assertEmptyGraph(db)
        db.accountDeletionDao()
            .save(
                AccountDeletionJournal(
                    operationId = "test",
                    accountId = "synthetic",
                    sessionEpoch = 1,
                    profileGeneration = 1,
                    stage = "PREPARED",
                    createdAtEpochMillis = 1
                )
            )
        assertTrue(
            runCatching { repository(db).startPlannedWorkout("workout-a", 1L) }.exceptionOrNull()
                is AccountDeletionInProgressException
        )
        assertEmptyGraph(db)
    }

    private fun assertEmptyGraph(db: IronPathDatabase) {
        for (table in listOf("active_sessions", "session_exercises", "session_sets")) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                it.moveToFirst()
                assertEquals(table, 0, it.getInt(0))
            }
        }
    }
}
