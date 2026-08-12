package com.example.ironpath.data.repository

import androidx.room.withTransaction
import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.HistoryDao
import com.example.ironpath.data.local.dao.PlanDao
import com.example.ironpath.data.local.dao.SessionDao
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.LoggedExercise
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import com.example.ironpath.data.local.entity.WorkoutLog
import com.example.ironpath.data.performance.PerformanceTracer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SessionRepository
@Inject
constructor(
    private val sessionDao: SessionDao,
    private val historyDao: HistoryDao,
    private val planDao: PlanDao,
    private val database: IronPathDatabase,
    private val performanceTracer: PerformanceTracer,
    private val backupChangeTracker: BackupChangeTracker,
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
     * Completes a session atomically: conditionally marks its planned workout complete, writes an
     * immutable history snapshot, and deletes the active graph. Rejects a repeated completion after
     * the source session has already been removed.
     */
    suspend fun completeSession(sessionId: String, log: WorkoutLog) {
        val traceCookie = performanceTracer.beginAsyncSection(COMPLETE_SESSION_TRACE)
        try {
            database.withTransaction {
                val activeSession = sessionDao.getActiveSession()
                check(activeSession?.id == sessionId) {
                    "Active session $sessionId no longer exists"
                }

                val sessionExercises = sessionDao.getExercisesForSession(sessionId)
                val exerciseIds = sessionExercises.map { it.id }
                val sessionSets =
                    if (exerciseIds.isEmpty()) emptyList()
                    else sessionDao.getSetsForExercises(exerciseIds)

                if (sessionSets.any { it.reps != null && it.weightKg != null }) {
                    planDao.markWorkoutCompleted(activeSession.sourcePlannedWorkoutId)
                }

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
                backupChangeTracker.markIncludedDataChanged()
            }
        } finally {
            performanceTracer.endAsyncSection(COMPLETE_SESSION_TRACE, traceCookie)
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

private const val COMPLETE_SESSION_TRACE = "IronPath#completeSession"
