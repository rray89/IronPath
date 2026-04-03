package com.example.ironpath.data.repository

import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.entity.WorkoutLog
import kotlinx.coroutines.flow.Flow

class HistoryRepository(private val historyDao: HistoryDao) {

  fun observeAllLogs(): Flow<List<WorkoutLog>> = historyDao.observeAllLogs()

  suspend fun getLogById(id: String): WorkoutLog? = historyDao.getLogById(id)

  suspend fun insertLog(log: WorkoutLog) = historyDao.insertLog(log)
}
