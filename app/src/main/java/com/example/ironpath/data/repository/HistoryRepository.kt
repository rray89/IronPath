package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.WorkoutLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class HistoryRepository
@Inject
constructor(
    private val historyDao: HistoryDao,
    private val database: IronPathDatabase,
    private val backupChangeTracker: BackupChangeTracker,
) {

    fun observeAllLogs(): Flow<List<WorkoutLog>> = historyDao.observeAllLogs()

    suspend fun getLogById(id: String): WorkoutLog? = historyDao.getLogById(id)

    suspend fun insertLog(log: WorkoutLog) =
        database.withTransaction {
            historyDao.insertLog(log)
            backupChangeTracker.markIncludedDataChanged()
        }

    suspend fun getLogDetail(id: String): WorkoutLogDetail? {
        val log = historyDao.getLogById(id) ?: return null
        val exercises = historyDao.getLoggedExercisesForLog(id)
        val exerciseIds = exercises.map { it.id }
        val sets =
            if (exerciseIds.isEmpty()) emptyList()
            else historyDao.getLoggedSetsForExercises(exerciseIds)
        val setsByExercise = sets.groupBy { it.loggedExerciseId }
        return WorkoutLogDetail(
            log = log,
            exercises =
                exercises.map { exercise ->
                    LoggedExerciseDetail(
                        exercise = exercise,
                        sets = setsByExercise[exercise.id].orEmpty().sortedBy { it.setNumber },
                    )
                },
        )
    }
}

data class WorkoutLogDetail(
    val log: WorkoutLog,
    val exercises: List<LoggedExerciseDetail>,
)

data class LoggedExerciseDetail(
    val exercise: LoggedExercise,
    val sets: List<LoggedSet>,
)
