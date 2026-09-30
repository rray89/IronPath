package com.example.ironpath.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.ironpath.data.local.dao.BackupDao
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.BackupBaselineChunk
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.RestoreUndoChunk
import com.example.ironpath.data.local.entity.RestoreUndoMetadata
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
            AccountBackupMetadata::class,
            BackupBaselineChunk::class,
            RestoreUndoMetadata::class,
            RestoreUndoChunk::class,
        ],
    version = 6,
    exportSchema = true,
)
abstract class IronPathDatabase : RoomDatabase() {
    abstract fun planDao(): PlanDao

    abstract fun sessionDao(): SessionDao

    abstract fun historyDao(): HistoryDao

    abstract fun recordDao(): RecordDao

    abstract fun backupDao(): BackupDao

    companion object {
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `backup_baseline_chunks` (
                            `chunkIndex` INTEGER NOT NULL,
                            `ownerUid` TEXT NOT NULL,
                            `installationId` TEXT NOT NULL,
                            `backupId` TEXT NOT NULL,
                            `remoteGeneration` INTEGER NOT NULL,
                            `completedAt` INTEGER NOT NULL,
                            `sourceInstallationId` TEXT NOT NULL,
                            `formatVersion` INTEGER NOT NULL,
                            `capturedRevision` INTEGER NOT NULL,
                            `entityCountsJson` TEXT NOT NULL,
                            `snapshotByteCount` INTEGER NOT NULL,
                            `snapshotDigest` TEXT NOT NULL,
                            `payload` TEXT NOT NULL,
                            `chunkByteCount` INTEGER NOT NULL,
                            `chunkDigest` TEXT NOT NULL,
                            PRIMARY KEY(`chunkIndex`)
                        )
                        """
                            .trimIndent()
                    )
                }
            }

        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `account_backup_metadata` ADD COLUMN `requiresLineageReviewAfterUndo` INTEGER NOT NULL DEFAULT 0"
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `restore_undo_metadata` (
                            `id` INTEGER NOT NULL,
                            `slotIdentity` TEXT NOT NULL,
                            `restoringOwnerUid` TEXT NOT NULL,
                            `restoringInstallationId` TEXT NOT NULL,
                            `previousOwnerUid` TEXT,
                            `previousInstallationId` TEXT NOT NULL,
                            `previousLocalChangeRevision` INTEGER NOT NULL,
                            `previousLastCompleteLocalRevision` INTEGER NOT NULL,
                            `previousLastObservedRemoteBackupId` TEXT,
                            `previousLastObservedRemoteGeneration` INTEGER NOT NULL,
                            `previousLastObservedRemoteDigest` TEXT,
                            `previousLastObservedSourceInstallationId` TEXT,
                            `previousLastObservedRemoteCompletedAt` INTEGER,
                            `snapshotFormatVersion` INTEGER NOT NULL,
                            `snapshotRevision` INTEGER NOT NULL,
                            `snapshotEntityCountsJson` TEXT NOT NULL,
                            `snapshotByteCount` INTEGER NOT NULL,
                            `snapshotDigest` TEXT NOT NULL,
                            `baselineBackupId` TEXT,
                            `baselineGeneration` INTEGER,
                            `baselineCompletedAt` INTEGER,
                            `baselineSourceInstallationId` TEXT,
                            `baselineFormatVersion` INTEGER,
                            `baselineRevision` INTEGER,
                            `baselineEntityCountsJson` TEXT,
                            `baselineByteCount` INTEGER,
                            `baselineDigest` TEXT,
                            PRIMARY KEY(`id`)
                        )
                        """
                            .trimIndent()
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `restore_undo_chunks` (
                            `kind` TEXT NOT NULL,
                            `chunkIndex` INTEGER NOT NULL,
                            `payload` TEXT NOT NULL,
                            `payloadByteCount` INTEGER NOT NULL,
                            `payloadDigest` TEXT NOT NULL,
                            PRIMARY KEY(`kind`, `chunkIndex`)
                        )
                        """
                            .trimIndent()
                    )
                }
            }

        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE `account_backup_metadata` ADD COLUMN `pendingSignOutUid` TEXT"
                    )
                }
            }

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

        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `account_backup_metadata` (
                            `id` INTEGER NOT NULL,
                            `ownerUid` TEXT,
                            `installationId` TEXT NOT NULL,
                            `localChangeRevision` INTEGER NOT NULL,
                            `lastCompleteLocalRevision` INTEGER NOT NULL,
                            `lastObservedRemoteBackupId` TEXT,
                            `lastObservedRemoteGeneration` INTEGER NOT NULL,
                            `lastObservedRemoteDigest` TEXT,
                            `lastObservedSourceInstallationId` TEXT,
                            `lastObservedRemoteCompletedAt` INTEGER,
                            PRIMARY KEY(`id`)
                        )
                        """
                            .trimIndent(),
                    )
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `account_backup_metadata` (
                            `id`,
                            `ownerUid`,
                            `installationId`,
                            `localChangeRevision`,
                            `lastCompleteLocalRevision`,
                            `lastObservedRemoteBackupId`,
                            `lastObservedRemoteGeneration`,
                            `lastObservedRemoteDigest`,
                            `lastObservedSourceInstallationId`,
                            `lastObservedRemoteCompletedAt`
                        ) VALUES (
                            1,
                            NULL,
                            lower(hex(randomblob(16))),
                            CASE WHEN
                                EXISTS(SELECT 1 FROM `weekly_plans`) OR
                                EXISTS(SELECT 1 FROM `workout_logs`) OR
                                EXISTS(SELECT 1 FROM `personal_records`)
                            THEN 1 ELSE 0 END,
                            0,
                            NULL,
                            0,
                            NULL,
                            NULL,
                            NULL
                        )
                        """
                            .trimIndent(),
                    )
                }
            }
    }
}
