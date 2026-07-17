package com.example.ironpath.data.local.dao

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDaoTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    private val dao: SessionDao
        get() = databaseRule.database.sessionDao()

    @Test
    fun startNewSession_replacesExistingSession_andCascadesOldChildren() = runBlocking {
        val previousSession = TestData.session(id = "session-previous")
        val previousExercise =
            TestData.sessionExercise(
                id = "exercise-previous",
                sessionId = previousSession.id,
            )
        val previousSet =
            TestData.sessionSet(
                id = "set-previous",
                exerciseId = previousExercise.id,
                reps = 5,
                weightKg = 100.0,
            )
        dao.startNewSession(previousSession, listOf(previousExercise))
        dao.insertSet(previousSet)

        val replacementSession =
            TestData.session(
                id = "session-replacement",
                workoutId = "workout-replacement",
                title = "Strength B",
                startedAt = TestData.BASE_TIME + 1,
                lastUpdatedAt = TestData.BASE_TIME + 1,
            )
        val replacementExercise =
            TestData.sessionExercise(
                id = "exercise-replacement",
                sessionId = replacementSession.id,
                name = "Bench Press",
            )

        dao.startNewSession(replacementSession, listOf(replacementExercise))

        assertEquals(replacementSession, dao.getActiveSession())
        assertTrue(dao.getExercisesForSession(previousSession.id).isEmpty())
        assertTrue(dao.getSetsForExercises(listOf(previousExercise.id)).isEmpty())
        assertEquals(
            listOf(replacementExercise),
            dao.getExercisesForSession(replacementSession.id),
        )
    }

    @Test
    fun sessionExercisesAndSets_areReturnedInOrder() = runBlocking {
        val session = TestData.session()
        val firstExercise =
            TestData.sessionExercise(
                id = "exercise-first",
                orderIndex = 0,
            )
        val secondExercise =
            TestData.sessionExercise(
                id = "exercise-second",
                name = "Bench Press",
                orderIndex = 1,
            )
        val thirdExercise =
            TestData.sessionExercise(
                id = "exercise-third",
                name = "Deadlift",
                orderIndex = 2,
            )
        dao.startNewSession(session, listOf(thirdExercise, firstExercise, secondExercise))
        val firstSet =
            TestData.sessionSet(
                id = "set-first",
                exerciseId = firstExercise.id,
                setNumber = 1,
            )
        val secondSet =
            TestData.sessionSet(
                id = "set-first-middle",
                exerciseId = firstExercise.id,
                setNumber = 3,
            )
        val thirdSet =
            TestData.sessionSet(
                id = "set-first-last",
                exerciseId = firstExercise.id,
                setNumber = 5,
            )
        val secondExerciseSet =
            TestData.sessionSet(
                id = "set-second-exercise",
                exerciseId = secondExercise.id,
                setNumber = 2,
            )
        val thirdExerciseSet =
            TestData.sessionSet(
                id = "set-third-exercise",
                exerciseId = thirdExercise.id,
                setNumber = 4,
            )
        dao.insertSet(thirdSet)
        dao.insertSet(firstSet)
        dao.insertSet(secondSet)
        dao.insertSet(thirdExerciseSet)
        dao.insertSet(secondExerciseSet)

        assertEquals(
            listOf(firstExercise.id, secondExercise.id, thirdExercise.id),
            dao.getExercisesForSession(session.id).map { it.id },
        )
        assertEquals(
            listOf(firstExercise.id, secondExercise.id, thirdExercise.id),
            dao.observeExercisesForSession(session.id).first().map { it.id },
        )
        assertEquals(
            listOf(firstSet.id, secondSet.id, thirdSet.id),
            dao.observeSetsForExercise(firstExercise.id).first().map { it.id },
        )
        assertEquals(
            listOf(
                firstSet.id,
                secondExerciseSet.id,
                secondSet.id,
                thirdExerciseSet.id,
                thirdSet.id,
            ),
            dao.getSetsForExercises(listOf(firstExercise.id, secondExercise.id, thirdExercise.id))
                .map { it.id },
        )
        assertEquals(
            listOf(
                firstSet.id,
                secondExerciseSet.id,
                secondSet.id,
                thirdExerciseSet.id,
                thirdSet.id,
            ),
            dao.observeSetsForExercises(
                    listOf(firstExercise.id, secondExercise.id, thirdExercise.id)
                )
                .first()
                .map { it.id },
        )
    }

    @Test
    fun deletingSession_cascadesExercisesAndSets() = runBlocking {
        val session = TestData.session()
        val firstExercise = TestData.sessionExercise(id = "exercise-first")
        val secondExercise =
            TestData.sessionExercise(
                id = "exercise-second",
                name = "Bench Press",
                orderIndex = 1,
            )
        dao.startNewSession(session, listOf(firstExercise, secondExercise))
        dao.insertSet(TestData.sessionSet(id = "set-first", exerciseId = firstExercise.id))
        dao.insertSet(TestData.sessionSet(id = "set-second", exerciseId = secondExercise.id))

        dao.deleteSession(session.id)

        assertNull(dao.getActiveSession())
        assertTrue(dao.getExercisesForSession(session.id).isEmpty())
        assertTrue(dao.getSetsForExercises(listOf(firstExercise.id, secondExercise.id)).isEmpty())
    }
}
