package com.example.ironpath.data.local.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryDaoTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    private val dao
        get() = databaseRule.database.historyDao()

    @Test
    fun logs_areSortedMostRecentFirst() = runBlocking {
        val oldest = TestData.log(id = "log-oldest", completedAt = TestData.BASE_TIME + 1_000)
        val newest = TestData.log(id = "log-newest", completedAt = TestData.BASE_TIME + 3_000)
        val middle = TestData.log(id = "log-middle", completedAt = TestData.BASE_TIME + 2_000)

        dao.insertLog(oldest)
        dao.insertLog(newest)
        dao.insertLog(middle)

        assertEquals(listOf(newest, middle, oldest), dao.observeAllLogs().first())
    }

    @Test
    fun logLookupsAndSeedGuards_matchExactRows() = runBlocking {
        val first =
            TestData.log(
                id = "log-first",
                title = "Strength A",
                workoutId = "workout-a",
            )
        val second =
            TestData.log(
                id = "log-second",
                title = "Strength B",
                workoutId = null,
            )
        val third =
            TestData.log(
                id = "log-third",
                title = "Strength A",
                workoutId = "workout-c",
            )

        dao.insertLog(first)
        dao.insertLog(second)
        dao.insertLog(third)

        assertEquals(second, dao.getLogById(second.id))
        assertNull(dao.getLogById("missing-log"))
        assertEquals(2, dao.countLogsWithTitles(listOf("Strength A")))
        assertEquals(3, dao.countLogsWithTitles(listOf("Strength A", "Strength B")))
        assertEquals(0, dao.countLogsWithTitles(emptyList()))
        assertEquals(1, dao.countLogsWithSourcePlannedWorkoutId("workout-a"))
        assertEquals(0, dao.countLogsWithSourcePlannedWorkoutId("missing-workout"))
    }

    @Test
    fun loggedSnapshotQueries_returnChildrenInProductOrder() = runBlocking {
        val log = TestData.log()
        val laterExercise =
            TestData.loggedExercise(
                id = "logged-exercise-later",
                orderIndex = 7,
                name = "Bench Press",
            )
        val earlierExercise =
            TestData.loggedExercise(
                id = "logged-exercise-earlier",
                orderIndex = 2,
                name = "Squat",
            )
        val thirdSet =
            TestData.loggedSet(
                id = "logged-set-third",
                exerciseId = earlierExercise.id,
                setNumber = 3,
            )
        val firstSet =
            TestData.loggedSet(
                id = "logged-set-first",
                exerciseId = earlierExercise.id,
                setNumber = 1,
            )
        val secondSet =
            TestData.loggedSet(
                id = "logged-set-second",
                exerciseId = earlierExercise.id,
                setNumber = 2,
            )

        dao.insertLog(log)
        dao.insertLoggedExercises(listOf(laterExercise, earlierExercise))
        dao.insertLoggedSets(listOf(thirdSet, firstSet, secondSet))

        assertEquals(
            listOf(earlierExercise, laterExercise),
            dao.getLoggedExercisesForLog(log.id),
        )
        assertEquals(
            listOf(firstSet, secondSet, thirdSet),
            dao.getLoggedSetsForExercises(listOf(earlierExercise.id, laterExercise.id)),
        )
    }

    @Test
    fun loggedSnapshot_cascadesFromLogToExercisesToSets() = runBlocking {
        val log = TestData.log()
        val exercise = TestData.loggedExercise()
        val set = TestData.loggedSet()
        val retainedLog = TestData.log(id = "log-retained", title = "Retained")
        val retainedExercise =
            TestData.loggedExercise(
                id = "logged-exercise-retained",
                logId = retainedLog.id,
            )
        val retainedSet =
            TestData.loggedSet(
                id = "logged-set-retained",
                exerciseId = retainedExercise.id,
            )
        dao.insertLog(log)
        dao.insertLog(retainedLog)
        dao.insertLoggedExercises(listOf(exercise))
        dao.insertLoggedExercises(listOf(retainedExercise))
        dao.insertLoggedSets(listOf(set))
        dao.insertLoggedSets(listOf(retainedSet))

        databaseRule.database.openHelper.writableDatabase.execSQL(
            "DELETE FROM workout_logs WHERE id = ?",
            arrayOf(log.id),
        )

        assertNull(dao.getLogById(log.id))
        assertEquals(emptyList<Any>(), dao.getLoggedExercisesForLog(log.id))
        assertEquals(emptyList<Any>(), dao.getLoggedSetsForExercises(listOf(exercise.id)))
        assertEquals(retainedLog, dao.getLogById(retainedLog.id))
        assertEquals(listOf(retainedExercise), dao.getLoggedExercisesForLog(retainedLog.id))
        assertEquals(
            listOf(retainedSet),
            dao.getLoggedSetsForExercises(listOf(retainedExercise.id)),
        )
    }
}
