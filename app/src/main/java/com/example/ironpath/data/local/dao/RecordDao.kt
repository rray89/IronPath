package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.ironpath.data.local.entity.PersonalRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {

  @Insert suspend fun insertRecord(record: PersonalRecord)

  @Update suspend fun updateRecord(record: PersonalRecord)

  @Query("DELETE FROM personal_records WHERE id = :id") suspend fun deleteRecord(id: String)

  @Query("SELECT * FROM personal_records WHERE id = :id")
  suspend fun getRecordById(id: String): PersonalRecord?

  @Query(
    "SELECT COUNT(*) FROM personal_records WHERE normalizedExerciseName = :normalizedName AND achievedOn = :date AND weightKg = :weight AND id != :excludeId"
  )
  suspend fun countDuplicatesExcluding(
    normalizedName: String,
    date: String,
    weight: Double,
    excludeId: String,
  ): Int

  @Query("SELECT * FROM personal_records ORDER BY achievedOn DESC, createdAt DESC")
  fun observeAllRecords(): Flow<List<PersonalRecord>>

  @Query("SELECT DISTINCT exerciseName FROM personal_records")
  suspend fun getAllRecordExerciseNames(): List<String>
}
