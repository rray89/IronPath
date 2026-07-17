package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.entity.PersonalRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RecordRepository @Inject constructor(private val recordDao: RecordDao) {

    fun observeAllRecords(): Flow<List<PersonalRecord>> = recordDao.observeAllRecords()

    suspend fun getAllRecordExerciseNames(): List<String> = recordDao.getAllRecordExerciseNames()

    suspend fun insertRecord(record: PersonalRecord) = recordDao.insertRecord(record)
}
