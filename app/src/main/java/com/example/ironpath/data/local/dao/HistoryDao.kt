package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert suspend fun insertLog(log: WorkoutLog)

    @Insert suspend fun insertLoggedExercises(exercises: List<LoggedExercise>)

    @Insert suspend fun insertLoggedSets(sets: List<LoggedSet>)

    @Query("SELECT * FROM workout_logs ORDER BY completedAt DESC")
    fun observeAllLogs(): Flow<List<WorkoutLog>>

    @Query("SELECT * FROM workout_logs WHERE id = :id")
    suspend fun getLogById(id: String): WorkoutLog?

    @Query("SELECT COUNT(*) FROM workout_logs WHERE title IN (:titles)")
    suspend fun countLogsWithTitles(titles: List<String>): Int

    @Query(
        "SELECT COUNT(*) FROM workout_logs WHERE sourcePlannedWorkoutId = :sourcePlannedWorkoutId"
    )
    suspend fun countLogsWithSourcePlannedWorkoutId(sourcePlannedWorkoutId: String): Int

    @Query("SELECT * FROM logged_exercises WHERE workoutLogId = :logId ORDER BY orderIndex")
    suspend fun getLoggedExercisesForLog(logId: String): List<LoggedExercise>

    @Query("SELECT * FROM logged_sets WHERE loggedExerciseId IN (:exerciseIds) ORDER BY setNumber")
    suspend fun getLoggedSetsForExercises(exerciseIds: List<String>): List<LoggedSet>
}
