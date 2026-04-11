package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.entity.PersonalRecord
import kotlinx.coroutines.flow.Flow

class RecordRepository(private val recordDao: RecordDao) {

  fun observeAllRecords(): Flow<List<PersonalRecord>> = recordDao.observeAllRecords()

  suspend fun getAllRecordExerciseNames(): List<String> = recordDao.getAllRecordExerciseNames()

  suspend fun insertRecord(record: PersonalRecord) = recordDao.insertRecord(record)

  suspend fun updateRecord(record: PersonalRecord) = recordDao.updateRecord(record)

  suspend fun deleteRecord(id: String) = recordDao.deleteRecord(id)

  suspend fun getRecordById(id: String): PersonalRecord? = recordDao.getRecordById(id)

  suspend fun isDuplicateExcluding(
    normalizedName: String,
    date: String,
    weight: Double,
    excludeId: String,
  ): Boolean = recordDao.countDuplicatesExcluding(normalizedName, date, weight, excludeId) > 0
}
