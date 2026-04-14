package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WorkoutLog
import kotlinx.coroutines.flow.Flow

class SessionRepository(
    private val sessionDao: SessionDao,
    private val historyDao: HistoryDao,
    private val database: IronPathDatabase,
) {

    fun observeActiveSession(): Flow<ActiveSession?> = sessionDao.observeActiveSession()

    suspend fun getActiveSession(): ActiveSession? = sessionDao.getActiveSession()

    fun observeExercisesForSession(sessionId: String): Flow<List<SessionExercise>> =
        sessionDao.observeExercisesForSession(sessionId)

    suspend fun getExercisesForSession(sessionId: String): List<SessionExercise> =
        sessionDao.getExercisesForSession(sessionId)

    fun observeSetsForExercise(exerciseId: String): Flow<List<SessionSet>> =
        sessionDao.observeSetsForExercise(exerciseId)

    fun observeSetsForExercises(exerciseIds: List<String>): Flow<List<SessionSet>> =
        sessionDao.observeSetsForExercises(exerciseIds)

    suspend fun countCompletedSets(exerciseIds: List<String>): Int =
        sessionDao.countCompletedSets(exerciseIds)

    /**
     * Clears any existing active session, then starts a new one with its exercises in a single
     * transaction (handled by DAO @Transaction).
     */
    suspend fun startSession(
        session: ActiveSession,
        exercises: List<SessionExercise>,
    ) = sessionDao.startNewSession(session, exercises)

    suspend fun updateSession(session: ActiveSession) = sessionDao.updateSession(session)

    suspend fun insertSet(set: SessionSet) = sessionDao.insertSet(set)

    suspend fun updateSet(set: SessionSet) = sessionDao.updateSet(set)

    /**
     * Completes a session: deletes the active session and inserts a workout log. Uses
     * withTransaction because this spans two DAOs.
     */
    suspend fun completeSession(sessionId: String, log: WorkoutLog) {
        database.withTransaction {
            sessionDao.deleteSession(sessionId)
            historyDao.insertLog(log)
        }
    }
}
