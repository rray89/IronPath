package com.example.ironpath.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog

@Database(
    entities =
        [
            WeeklyPlan::class,
            PlannedWorkout::class,
            PlannedExercise::class,
            ActiveSession::class,
            SessionExercise::class,
            SessionSet::class,
            WorkoutLog::class,
            LoggedExercise::class,
            LoggedSet::class,
            PersonalRecord::class,
        ],
    version = 2,
    exportSchema = true,
)
abstract class IronPathDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao

    abstract fun sessionDao(): SessionDao

    abstract fun historyDao(): HistoryDao

    abstract fun recordDao(): RecordDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `logged_exercises` (
                            `id` TEXT NOT NULL,
                            `workoutLogId` TEXT NOT NULL,
                            `name` TEXT NOT NULL,
                            `plannedSets` INTEGER NOT NULL,
                            `plannedReps` INTEGER NOT NULL,
                            `plannedWeightKg` REAL NOT NULL,
                            `orderIndex` INTEGER NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`workoutLogId`) REFERENCES `workout_logs`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """
                            .trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS `index_logged_exercises_workoutLogId`
                        ON `logged_exercises` (`workoutLogId`)
                        """
                            .trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `logged_sets` (
                            `id` TEXT NOT NULL,
                            `loggedExerciseId` TEXT NOT NULL,
                            `setNumber` INTEGER NOT NULL,
                            `reps` INTEGER,
                            `weightKg` REAL,
                            `isExtra` INTEGER NOT NULL,
                            `completedAt` INTEGER,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`loggedExerciseId`) REFERENCES `logged_exercises`(`id`)
                                ON UPDATE NO ACTION ON DELETE CASCADE
                        )
                        """
                            .trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE INDEX IF NOT EXISTS `index_logged_sets_loggedExerciseId`
                        ON `logged_sets` (`loggedExerciseId`)
                        """
                            .trimIndent(),
                    )
                }
            }
    }
}
