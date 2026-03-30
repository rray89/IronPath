package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.ironpath.data.local.entity.WorkoutLog
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {

    @Insert
    suspend fun insertLog(log: WorkoutLog)

    @Query("SELECT * FROM workout_logs ORDER BY completedAt DESC")
    fun observeAllLogs(): Flow<List<WorkoutLog>>

    @Query("SELECT * FROM workout_logs WHERE id = :id")
    suspend fun getLogById(id: String): WorkoutLog?
}
