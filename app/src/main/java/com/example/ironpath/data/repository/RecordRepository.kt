package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.entity.PersonalRecord
import kotlinx.coroutines.flow.Flow

class RecordRepository(private val recordDao: RecordDao) {

  fun observeAllRecords(): Flow<List<PersonalRecord>> = recordDao.observeAllRecords()

  suspend fun getAllRecordExerciseNames(): List<String> = recordDao.getAllRecordExerciseNames()

  suspend fun insertRecord(record: PersonalRecord) = recordDao.insertRecord(record)
}
