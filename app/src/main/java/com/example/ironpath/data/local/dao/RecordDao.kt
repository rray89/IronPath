package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.ironpath.data.local.entity.PersonalRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

    @Insert
    suspend fun insertRecord(record: PersonalRecord)

    @Query("SELECT * FROM personal_records ORDER BY achievedOn DESC, createdAt DESC")
    fun observeAllRecords(): Flow<List<PersonalRecord>>

    @Query("SELECT DISTINCT exerciseName FROM personal_records")
    suspend fun getAllRecordExerciseNames(): List<String>
}
