package com.example.ironpath.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.data.backup.RoomBackupStore
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.SequenceIdProvider
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanReplacementIntegrationTest {
    @get:Rule val room = RoomTestDatabaseRule()
    private val nextPlan =
        TestData.plan(id = "next", startDate = "2027-01-04", endDate = "2027-01-10")
    private val nextWorkout =
        TestData.workout(id = "next-workout", planId = "next", scheduledDate = "2027-01-04")
    private val nextExercise =
        TestData.plannedExercise(id = "next-exercise", workoutId = "next-workout")

    @Test
    fun sessionStartedAfterReviewBlocksReplacement_andPreservesPlanHistoryRecordsAndRevision() =
        runBlocking {
            val db = room.database
            val store = RoomBackupStore(db, SequenceIdProvider("backup"))
            val repo = PlanRepository(db.planDao(), db, store)
            repo.createPlan(
                TestData.plan(),
                listOf(TestData.workout()),
                listOf(TestData.plannedExercise())
            )
            db.historyDao().insertLog(TestData.log())
            db.recordDao().insertRecord(TestData.record())
            val before = store.capture()
            db.sessionDao().startNewSession(TestData.session(), listOf(TestData.sessionExercise()))
            db.sessionDao().insertSet(TestData.sessionSet(reps = 5, weightKg = 100.0))
            val before = store.capture()
            val failure =
                runCatching { repo.createPlan(nextPlan, listOf(nextWorkout), listOf(nextExercise)) }
                    .exceptionOrNull()
            assertTrue(failure is ActiveSessionBlocksPlanException)
            assertEquals(before, store.capture())
            assertEquals(TestData.session(), db.sessionDao().getActiveSession())
            assertEquals(1, db.sessionDao().getSetsForExercises(listOf("session-exercise-a")).size)
        }

    @Test
    fun lateExerciseFailureRollsBackArchiveInsertAndRevision_thenRetryAndDuplicateAcceptAreStable() =
        runBlocking {
            val db = room.database
            val store = RoomBackupStore(db, SequenceIdProvider("backup"))
            val repo = PlanRepository(db.planDao(), db, store)
            repo.createPlan(
                TestData.plan(),
                listOf(TestData.workout()),
                listOf(TestData.plannedExercise())
            )
            val before = store.capture()
            db.openHelper.writableDatabase.execSQL(
                """
            CREATE TRIGGER fail_next_exercise BEFORE INSERT ON planned_exercises
            WHEN NEW.id = 'next-exercise' BEGIN SELECT RAISE(ABORT, 'forced next-week failure'); END
        """
                    .trimIndent()
            )
            assertNotNull(
                runCatching { repo.createPlan(nextPlan, listOf(nextWorkout), listOf(nextExercise)) }
                    .exceptionOrNull()
            )
            assertEquals(before, store.capture())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_next_exercise")
            val gate = CompletableDeferred<Unit>()
            val accepts =
                List(2) {
                    async(Dispatchers.Default) {
                        gate.await()
                        repo.createPlan(nextPlan, listOf(nextWorkout), listOf(nextExercise))
                    }
                }
            gate.complete(Unit)
            accepts.forEach { it.await() }
            assertEquals(nextPlan, db.planDao().getActivePlan())
            assertEquals(listOf(nextWorkout), db.planDao().getWorkoutsForPlan("next"))
            assertEquals(listOf(nextExercise), db.planDao().getExercisesForWorkout("next-workout"))
            assertEquals(2L, db.backupDao().getMetadata()?.localChangeRevision)
            db.openHelper.readableDatabase
                .query("SELECT COUNT(*) FROM weekly_plans WHERE status = 'Active'")
                .use {
                    it.moveToFirst()
                    assertEquals(1, it.getInt(0))
                }
            db.openHelper.readableDatabase
                .query("SELECT status FROM weekly_plans WHERE id = 'plan-a'")
                .use {
                    it.moveToFirst()
                    assertEquals("Archived", it.getString(0))
                }
        }
}
