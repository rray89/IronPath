package com.example.ironpath.data.local

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ironpath.testutil.TestData
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IronPathDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            IronPathDatabase::class.java,
        )

    @Test
    @Throws(IOException::class)
    fun migrate1To2_preservesExistingPlansSessionsLogsAndRecords_andCreatesSnapshotTables() {
        helper.createDatabase(PRESERVATION_DATABASE, 1).apply {
            seedVersionOneData()
            close()
        }

        helper
            .runMigrationsAndValidate(
                PRESERVATION_DATABASE,
                2,
                true,
                IronPathDatabase.MIGRATION_1_2,
            )
            .use { database ->
                database.assertSingleRow("SELECT * FROM weekly_plans WHERE id = '$PLAN_ID'") {
                    assertEquals(PLAN_ID, string("id"))
                    assertEquals("Active", string("status"))
                    assertEquals("2026-07-13", string("startDate"))
                    assertEquals("2026-07-19", string("endDate"))
                    assertEquals(1_700_000_000_000L, long("createdAt"))
                }
                database.assertSingleRow(
                    "SELECT * FROM planned_workouts WHERE id = '$WORKOUT_ID'"
                ) {
                    assertEquals(WORKOUT_ID, string("id"))
                    assertEquals(PLAN_ID, string("weeklyPlanId"))
                    assertEquals(1, int("dayOfWeek"))
                    assertEquals("2026-07-13", string("scheduledDate"))
                    assertEquals("Strength A", string("title"))
                    assertEquals("Upcoming", string("status"))
                }
                database.assertSingleRow(
                    "SELECT * FROM planned_exercises WHERE id = '$PLANNED_EXERCISE_ID'"
                ) {
                    assertEquals(PLANNED_EXERCISE_ID, string("id"))
                    assertEquals(WORKOUT_ID, string("plannedWorkoutId"))
                    assertEquals("Back Squat", string("name"))
                    assertEquals(3, int("sets"))
                    assertEquals(5, int("reps"))
                    assertEquals(102.5, double("weightKg"), 0.0)
                    assertEquals(0, int("orderIndex"))
                }
                database.assertSingleRow("SELECT * FROM active_sessions WHERE id = '$SESSION_ID'") {
                    assertEquals(SESSION_ID, string("id"))
                    assertEquals(WORKOUT_ID, string("sourcePlannedWorkoutId"))
                    assertEquals("Strength A", string("workoutTitle"))
                    assertEquals(1_700_000_010_000L, long("startedAt"))
                    assertEquals(1_700_000_020_000L, long("lastUpdatedAt"))
                }
                database.assertSingleRow(
                    "SELECT * FROM session_exercises WHERE id = '$SESSION_EXERCISE_ID'"
                ) {
                    assertEquals(SESSION_EXERCISE_ID, string("id"))
                    assertEquals(SESSION_ID, string("activeSessionId"))
                    assertEquals("Back Squat", string("name"))
                    assertEquals(3, int("plannedSets"))
                    assertEquals(5, int("plannedReps"))
                    assertEquals(102.5, double("plannedWeightKg"), 0.0)
                    assertEquals(0, int("orderIndex"))
                }
                database.assertSingleRow(
                    "SELECT * FROM session_sets WHERE id = '$SESSION_SET_ID'"
                ) {
                    assertEquals(SESSION_SET_ID, string("id"))
                    assertEquals(SESSION_EXERCISE_ID, string("sessionExerciseId"))
                    assertEquals(4, int("setNumber"))
                    assertEquals(5, int("reps"))
                    assertEquals(105.0, double("weightKg"), 0.0)
                    assertEquals(1, int("isExtra"))
                    assertEquals(1_700_000_030_000L, long("completedAt"))
                }
                database.assertSingleRow("SELECT * FROM workout_logs WHERE id = '$LOG_ID'") {
                    assertEquals(LOG_ID, string("id"))
                    assertEquals("Strength A", string("title"))
                    assertEquals(WORKOUT_ID, string("sourcePlannedWorkoutId"))
                    assertEquals(1_699_999_000_000L, long("startedAt"))
                    assertEquals(1_699_999_360_000L, long("completedAt"))
                    assertEquals(60, int("durationMinutes"))
                    assertEquals(1, int("exerciseCount"))
                }
                database.assertSingleRow("SELECT * FROM personal_records WHERE id = '$RECORD_ID'") {
                    assertEquals(RECORD_ID, string("id"))
                    assertEquals("Deadlift", string("exerciseName"))
                    assertEquals("deadlift", string("normalizedExerciseName"))
                    assertEquals(180.5, double("weightKg"), 0.0)
                    assertEquals("2026-07-12", string("achievedOn"))
                    assertEquals("Clean pull", string("note"))
                    assertEquals("Manual", string("sourceType"))
                    assertNull(nullableString("sourceWorkoutLogId"))
                    assertEquals(1_699_999_500_000L, long("createdAt"))
                }
                database.assertSingleRow(
                    "SELECT * FROM personal_records WHERE id = '$LOGGED_RECORD_ID'"
                ) {
                    assertEquals("Bench Press", string("exerciseName"))
                    assertEquals("bench press", string("normalizedExerciseName"))
                    assertEquals("Logged", string("sourceType"))
                    assertEquals(LOG_ID, string("sourceWorkoutLogId"))
                }

                assertEquals(0, database.rowCount("logged_exercises"))
                assertEquals(0, database.rowCount("logged_sets"))
                assertEquals(
                    setOf("logged_exercises", "logged_sets"),
                    database.tableNames("logged_exercises", "logged_sets"),
                )
            }
    }

    @Test
    @Throws(IOException::class)
    fun migrate1To2_enforcesNewForeignKeysAndIndexes() {
        helper.createDatabase(CONSTRAINT_DATABASE, 1).close()

        helper
            .runMigrationsAndValidate(
                CONSTRAINT_DATABASE,
                2,
                true,
                IronPathDatabase.MIGRATION_1_2,
            )
            .use { database ->
                database.execSQL("PRAGMA foreign_keys=ON")
                database.assertSingleRow("PRAGMA foreign_keys") { assertEquals(1, getInt(0)) }
                database.assertForeignKey(
                    table = "logged_exercises",
                    childColumn = "workoutLogId",
                    parentTable = "workout_logs",
                )
                database.assertForeignKey(
                    table = "logged_sets",
                    childColumn = "loggedExerciseId",
                    parentTable = "logged_exercises",
                )
                database.assertIndex(
                    table = "logged_exercises",
                    index = "index_logged_exercises_workoutLogId",
                    unique = false,
                    columns = listOf("workoutLogId"),
                )
                database.assertIndex(
                    table = "logged_sets",
                    index = "index_logged_sets_loggedExerciseId",
                    unique = false,
                    columns = listOf("loggedExerciseId"),
                )
                database.assertIndex(
                    table = "personal_records",
                    index = "index_personal_records_normalizedExerciseName_achievedOn_weightKg",
                    unique = true,
                    columns = listOf("normalizedExerciseName", "achievedOn", "weightKg"),
                )

                assertThrows(SQLiteConstraintException::class.java) {
                    database.execSQL(
                        """
                        INSERT INTO logged_exercises
                            (id, workoutLogId, name, plannedSets, plannedReps, plannedWeightKg, orderIndex)
                        VALUES ('orphan-exercise', 'missing-log', 'Squat', 3, 5, 100.0, 0)
                        """
                            .trimIndent()
                    )
                }
                assertThrows(SQLiteConstraintException::class.java) {
                    database.execSQL(
                        """
                        INSERT INTO logged_sets
                            (id, loggedExerciseId, setNumber, reps, weightKg, isExtra, completedAt)
                        VALUES ('orphan-set', 'missing-exercise', 1, 5, 100.0, 0, 1700000000000)
                        """
                            .trimIndent()
                    )
                }

                database.execSQL(
                    """
                    INSERT INTO workout_logs
                        (id, title, sourcePlannedWorkoutId, startedAt, completedAt, durationMinutes, exerciseCount)
                    VALUES ('cascade-log', 'Cascade', NULL, 1700000000000, 1700003600000, 60, 1)
                    """
                        .trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO logged_exercises
                        (id, workoutLogId, name, plannedSets, plannedReps, plannedWeightKg, orderIndex)
                    VALUES ('cascade-exercise', 'cascade-log', 'Squat', 3, 5, 100.0, 0)
                    """
                        .trimIndent()
                )
                database.execSQL(
                    """
                    INSERT INTO logged_sets
                        (id, loggedExerciseId, setNumber, reps, weightKg, isExtra, completedAt)
                    VALUES ('cascade-set', 'cascade-exercise', 1, 5, 100.0, 0, 1700000100000)
                    """
                        .trimIndent()
                )
                database.execSQL("DELETE FROM workout_logs WHERE id = 'cascade-log'")
                assertEquals(0, database.rowCount("logged_exercises"))
                assertEquals(0, database.rowCount("logged_sets"))

                database.execSQL(
                    """
                    INSERT INTO personal_records
                        (id, exerciseName, normalizedExerciseName, weightKg, achievedOn, note,
                         sourceType, sourceWorkoutLogId, createdAt)
                    VALUES ('record-one', 'Deadlift', 'deadlift', 180.5, '2026-07-16', NULL,
                            'Manual', NULL, 1700000000000)
                    """
                        .trimIndent()
                )
                assertThrows(SQLiteConstraintException::class.java) {
                    database.execSQL(
                        """
                        INSERT INTO personal_records
                            (id, exerciseName, normalizedExerciseName, weightKg, achievedOn, note,
                             sourceType, sourceWorkoutLogId, createdAt)
                        VALUES ('record-two', 'DEADLIFT', 'deadlift', 180.5, '2026-07-16', NULL,
                                'Manual', NULL, 1700000001000)
                        """
                            .trimIndent()
                    )
                }
            }
    }

    @Test
    @Throws(IOException::class)
    fun allMigrations_openLatestSchemaAndAllDaosRemainUsable() {
        helper.createDatabase(ALL_MIGRATIONS_DATABASE, 1).apply {
            seedVersionOneData()
            close()
        }
        helper
            .runMigrationsAndValidate(
                ALL_MIGRATIONS_DATABASE,
                2,
                true,
                IronPathDatabase.MIGRATION_1_2,
            )
            .close()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val database =
            Room.databaseBuilder(context, IronPathDatabase::class.java, ALL_MIGRATIONS_DATABASE)
                .addMigrations(IronPathDatabase.MIGRATION_1_2)
                .build()
        try {
            database.openHelper.writableDatabase
            runBlocking {
                assertEquals(PLAN_ID, database.planDao().getActivePlan()?.id)
                assertEquals(
                    listOf(WORKOUT_ID),
                    database.planDao().getWorkoutsForPlan(PLAN_ID).map { it.id },
                )
                assertEquals(
                    listOf(PLANNED_EXERCISE_ID),
                    database.planDao().getExercisesForWorkout(WORKOUT_ID).map { it.id },
                )

                assertEquals(SESSION_ID, database.sessionDao().getActiveSession()?.id)
                assertEquals(
                    listOf(SESSION_EXERCISE_ID),
                    database.sessionDao().getExercisesForSession(SESSION_ID).map { it.id },
                )
                assertEquals(
                    listOf(SESSION_SET_ID),
                    database.sessionDao().getSetsForExercises(listOf(SESSION_EXERCISE_ID)).map {
                        it.id
                    },
                )

                assertEquals(LOG_ID, database.historyDao().getLogById(LOG_ID)?.id)
                val snapshotExercise =
                    TestData.loggedExercise(
                        id = "migration-logged-exercise",
                        logId = LOG_ID,
                    )
                val snapshotSet =
                    TestData.loggedSet(
                        id = "migration-logged-set",
                        exerciseId = snapshotExercise.id,
                        reps = 5,
                        weightKg = 100.0,
                    )
                database.historyDao().insertLoggedExercises(listOf(snapshotExercise))
                database.historyDao().insertLoggedSets(listOf(snapshotSet))
                assertEquals(
                    listOf(snapshotExercise),
                    database.historyDao().getLoggedExercisesForLog(LOG_ID),
                )
                assertEquals(
                    listOf(snapshotSet),
                    database.historyDao().getLoggedSetsForExercises(listOf(snapshotExercise.id)),
                )
                assertEquals(
                    listOf(LOGGED_RECORD_ID, RECORD_ID),
                    database.recordDao().observeAllRecords().first().map { it.id },
                )
            }
        } finally {
            database.close()
        }
    }

    private fun SupportSQLiteDatabase.seedVersionOneData() {
        execSQL(
            """
            INSERT INTO weekly_plans (id, status, startDate, endDate, createdAt)
            VALUES ('$PLAN_ID', 'Active', '2026-07-13', '2026-07-19', 1700000000000)
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO personal_records
                (id, exerciseName, normalizedExerciseName, weightKg, achievedOn, note, sourceType,
                 sourceWorkoutLogId, createdAt)
            VALUES ('$LOGGED_RECORD_ID', 'Bench Press', 'bench press', 100.0, '2026-07-13', NULL,
                    'Logged', '$LOG_ID', 1699999600000)
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO planned_workouts
                (id, weeklyPlanId, dayOfWeek, scheduledDate, title, status)
            VALUES ('$WORKOUT_ID', '$PLAN_ID', 1, '2026-07-13', 'Strength A', 'Upcoming')
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO planned_exercises
                (id, plannedWorkoutId, name, sets, reps, weightKg, orderIndex)
            VALUES ('$PLANNED_EXERCISE_ID', '$WORKOUT_ID', 'Back Squat', 3, 5, 102.5, 0)
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO active_sessions
                (id, sourcePlannedWorkoutId, workoutTitle, startedAt, lastUpdatedAt)
            VALUES ('$SESSION_ID', '$WORKOUT_ID', 'Strength A', 1700000010000, 1700000020000)
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO session_exercises
                (id, activeSessionId, name, plannedSets, plannedReps, plannedWeightKg, orderIndex)
            VALUES ('$SESSION_EXERCISE_ID', '$SESSION_ID', 'Back Squat', 3, 5, 102.5, 0)
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO session_sets
                (id, sessionExerciseId, setNumber, reps, weightKg, isExtra, completedAt)
            VALUES ('$SESSION_SET_ID', '$SESSION_EXERCISE_ID', 4, 5, 105.0, 1, 1700000030000)
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO workout_logs
                (id, title, sourcePlannedWorkoutId, startedAt, completedAt, durationMinutes,
                 exerciseCount)
            VALUES ('$LOG_ID', 'Strength A', '$WORKOUT_ID', 1699999000000, 1699999360000, 60, 1)
            """
                .trimIndent()
        )
        execSQL(
            """
            INSERT INTO personal_records
                (id, exerciseName, normalizedExerciseName, weightKg, achievedOn, note, sourceType,
                 sourceWorkoutLogId, createdAt)
            VALUES ('$RECORD_ID', 'Deadlift', 'deadlift', 180.5, '2026-07-12', 'Clean pull',
                    'Manual', NULL, 1699999500000)
            """
                .trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.assertForeignKey(
        table: String,
        childColumn: String,
        parentTable: String,
    ) {
        assertSingleRow("PRAGMA foreign_key_list(`$table`)") {
            assertEquals(parentTable, string("table"))
            assertEquals(childColumn, string("from"))
            assertEquals("id", string("to"))
            assertEquals("NO ACTION", string("on_update"))
            assertEquals("CASCADE", string("on_delete"))
        }
    }

    private fun SupportSQLiteDatabase.assertIndex(
        table: String,
        index: String,
        unique: Boolean,
        columns: List<String>,
    ) {
        query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == index) {
                    found = true
                    assertEquals(if (unique) 1 else 0, cursor.getInt(uniqueColumn))
                }
            }
            assertTrue("Missing index $index on $table", found)
        }

        val actualColumns = mutableListOf<String>()
        query("PRAGMA index_info(`$index`)").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) actualColumns += cursor.getString(nameColumn)
        }
        assertEquals(columns, actualColumns)
    }

    private fun SupportSQLiteDatabase.assertSingleRow(
        sql: String,
        assertions: Cursor.() -> Unit,
    ) {
        query(sql).use { cursor ->
            assertTrue("Expected one row for: $sql", cursor.moveToFirst())
            cursor.assertions()
            assertFalse("Expected exactly one row for: $sql", cursor.moveToNext())
        }
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.tableNames(vararg expected: String): Set<String> {
        val expectedSql = expected.joinToString(",") { "'$it'" }
        return query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ($expectedSql)"
            )
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))

    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { index -> if (isNull(index)) null else getString(index) }

    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))

    private fun Cursor.double(column: String): Double = getDouble(getColumnIndexOrThrow(column))

    private companion object {
        const val PRESERVATION_DATABASE = "migration-preservation.db"
        const val CONSTRAINT_DATABASE = "migration-constraints.db"
        const val ALL_MIGRATIONS_DATABASE = "migration-all.db"

        const val PLAN_ID = "plan-v1"
        const val WORKOUT_ID = "workout-v1"
        const val PLANNED_EXERCISE_ID = "planned-exercise-v1"
        const val SESSION_ID = "session-v1"
        const val SESSION_EXERCISE_ID = "session-exercise-v1"
        const val SESSION_SET_ID = "session-set-v1"
        const val LOG_ID = "log-v1"
        const val RECORD_ID = "record-v1"
        const val LOGGED_RECORD_ID = "record-logged-v1"
    }
}
