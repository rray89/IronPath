package com.example.ironpath.data.local.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ironpath.testutil.RoomTestDatabaseRule
import com.example.ironpath.testutil.TestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordDaoTest {
    @get:Rule val databaseRule = RoomTestDatabaseRule()

    private val dao
        get() = databaseRule.database.recordDao()

    @Test
    fun records_sortByAchievedDateThenCreatedAtDescending() = runBlocking {
        val earlierDate =
            TestData.record(
                id = "record-earlier-date",
                achievedOn = "2026-07-15",
                createdAt = TestData.BASE_TIME + 3_000,
            )
        val sameDateOlder =
            TestData.record(
                id = "record-same-date-older",
                achievedOn = "2026-07-16",
                weightKg = 181.0,
                createdAt = TestData.BASE_TIME + 1_000,
            )
        val sameDateNewer =
            TestData.record(
                id = "record-same-date-newer",
                achievedOn = "2026-07-16",
                weightKg = 182.0,
                createdAt = TestData.BASE_TIME + 2_000,
            )

        dao.insertRecord(earlierDate)
        dao.insertRecord(sameDateOlder)
        dao.insertRecord(sameDateNewer)

        assertEquals(
            listOf(sameDateNewer, sameDateOlder, earlierDate),
            dao.observeAllRecords().first(),
        )
    }

    @Test
    fun exactNormalizedDateWeightDuplicate_isRejectedByUniqueIndex() {
        val original = TestData.record(id = "record-original", exerciseName = "Deadlift")
        val duplicate =
            TestData.record(
                id = "record-duplicate",
                exerciseName = "  DEADLIFT  ",
                normalizedExerciseName = original.normalizedExerciseName,
                achievedOn = original.achievedOn,
                weightKg = original.weightKg,
            )
        runBlocking { dao.insertRecord(original) }

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { dao.insertRecord(duplicate) }
        }

        assertEquals(listOf(original), runBlocking { dao.observeAllRecords().first() })
    }

    @Test
    fun duplicateAcrossDifferentDateOrWeight_isAllowed() = runBlocking {
        val original = TestData.record(id = "record-original")
        val differentDate =
            TestData.record(
                id = "record-different-date",
                achievedOn = "2026-07-17",
                createdAt = TestData.BASE_TIME + 1_000,
            )
        val differentWeight =
            TestData.record(
                id = "record-different-weight",
                weightKg = 181.0,
                createdAt = TestData.BASE_TIME + 2_000,
            )

        dao.insertRecord(original)
        dao.insertRecord(differentDate)
        dao.insertRecord(differentWeight)

        assertEquals(
            listOf(differentDate, differentWeight, original),
            dao.observeAllRecords().first(),
        )
    }

    @Test
    fun exerciseNames_areDistinctForManualRecordSuggestions() = runBlocking {
        dao.insertRecord(TestData.record(id = "record-deadlift-one"))
        dao.insertRecord(
            TestData.record(
                id = "record-deadlift-two",
                achievedOn = "2026-07-17",
            ),
        )
        dao.insertRecord(
            TestData.record(
                id = "record-squat",
                exerciseName = "Squat",
                normalizedExerciseName = "squat",
            ),
        )

        val names = dao.getAllRecordExerciseNames()

        assertEquals(2, names.size)
        assertEquals(setOf("Deadlift", "Squat"), names.toSet())
    }
}
