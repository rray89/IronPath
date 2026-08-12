package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.ironpath.data.local.entity.AccountBackupMetadata
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.PlannedExercise
import com.example.ironpath.data.local.entity.PlannedWorkout
import com.example.ironpath.data.local.entity.WeeklyPlan
import com.example.ironpath.data.local.entity.WorkoutLog

@Dao
interface BackupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMetadataIfAbsent(metadata: AccountBackupMetadata)

    @Query("SELECT * FROM account_backup_metadata WHERE id = 1")
    suspend fun getMetadata(): AccountBackupMetadata?

    @Update suspend fun updateMetadata(metadata: AccountBackupMetadata)

    @Query(
        """
        UPDATE account_backup_metadata
        SET localChangeRevision = localChangeRevision + 1
        WHERE id = 1
        """
    )
    suspend fun incrementLocalChangeRevision()

    @Query("SELECT * FROM weekly_plans ORDER BY createdAt, id")
    suspend fun getWeeklyPlans(): List<WeeklyPlan>

    @Query("SELECT * FROM planned_workouts ORDER BY weeklyPlanId, dayOfWeek, id")
    suspend fun getPlannedWorkouts(): List<PlannedWorkout>

    @Query("SELECT * FROM planned_exercises ORDER BY plannedWorkoutId, orderIndex, id")
    suspend fun getPlannedExercises(): List<PlannedExercise>

    @Query("SELECT * FROM workout_logs ORDER BY completedAt, id")
    suspend fun getWorkoutLogs(): List<WorkoutLog>

    @Query("SELECT * FROM logged_exercises ORDER BY workoutLogId, orderIndex, id")
    suspend fun getLoggedExercises(): List<LoggedExercise>

    @Query("SELECT * FROM logged_sets ORDER BY loggedExerciseId, setNumber, id")
    suspend fun getLoggedSets(): List<LoggedSet>

    @Query("SELECT * FROM personal_records ORDER BY achievedOn, createdAt, id")
    suspend fun getPersonalRecords(): List<PersonalRecord>

    @Query("DELETE FROM personal_records") suspend fun deletePersonalRecords()

    @Query("DELETE FROM active_sessions") suspend fun deleteActiveSessions()

    @Query("DELETE FROM workout_logs") suspend fun deleteWorkoutLogs()

    @Query("DELETE FROM weekly_plans") suspend fun deleteWeeklyPlans()

    @Insert suspend fun insertWeeklyPlans(plans: List<WeeklyPlan>)

    @Insert suspend fun insertPlannedWorkouts(workouts: List<PlannedWorkout>)

    @Insert suspend fun insertPlannedExercises(exercises: List<PlannedExercise>)

    @Insert suspend fun insertWorkoutLogs(logs: List<WorkoutLog>)

    @Insert suspend fun insertLoggedExercises(exercises: List<LoggedExercise>)

    @Insert suspend fun insertLoggedSets(sets: List<LoggedSet>)

    @Insert suspend fun insertPersonalRecords(records: List<PersonalRecord>)
}
