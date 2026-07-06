package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
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
     * Completes a session: snapshots the active session for history detail, deletes the active
     * session, and inserts a workout log. Uses withTransaction because this spans two DAOs.
     */
    suspend fun completeSession(sessionId: String, log: WorkoutLog) {
        database.withTransaction {
            val sessionExercises = sessionDao.getExercisesForSession(sessionId)
            val exerciseIds = sessionExercises.map { it.id }
            val sessionSets =
                if (exerciseIds.isEmpty()) emptyList()
                else sessionDao.getSetsForExercises(exerciseIds)

            historyDao.insertLog(log)
            val loggedExercises = sessionExercises.map { it.toLoggedExercise(log.id) }
            if (loggedExercises.isNotEmpty()) {
                historyDao.insertLoggedExercises(loggedExercises)
            }
            val loggedSets = sessionSets.map { it.toLoggedSet() }
            if (loggedSets.isNotEmpty()) {
                historyDao.insertLoggedSets(loggedSets)
            }
            sessionDao.deleteSession(sessionId)
        }
    }

    private fun SessionExercise.toLoggedExercise(logId: String): LoggedExercise =
        LoggedExercise(
            id = id,
            workoutLogId = logId,
            name = name,
            plannedSets = plannedSets,
            plannedReps = plannedReps,
            plannedWeightKg = plannedWeightKg,
            orderIndex = orderIndex,
        )

    private fun SessionSet.toLoggedSet(): LoggedSet =
        LoggedSet(
            id = id,
            loggedExerciseId = sessionExerciseId,
            setNumber = setNumber,
            reps = reps,
            weightKg = weightKg,
            isExtra = isExtra,
            completedAt = completedAt,
        )
}
