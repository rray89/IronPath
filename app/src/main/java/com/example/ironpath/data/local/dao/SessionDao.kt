package com.example.ironpath.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.ironpath.data.local.entity.ActiveSession
import com.example.ironpath.data.local.entity.SessionExercise
import com.example.ironpath.data.local.entity.SessionSet
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    // ActiveSession
    @Insert suspend fun insertSession(session: ActiveSession)

    @Update suspend fun updateSession(session: ActiveSession)

    @Query("SELECT * FROM active_sessions LIMIT 1") fun observeActiveSession(): Flow<ActiveSession?>

    @Query("SELECT * FROM active_sessions LIMIT 1") suspend fun getActiveSession(): ActiveSession?

    @Query("DELETE FROM active_sessions WHERE id = :id") suspend fun deleteSession(id: String)

    // SessionExercise
    @Insert suspend fun insertSessionExercises(exercises: List<SessionExercise>)

    @Query("SELECT * FROM session_exercises WHERE activeSessionId = :sessionId ORDER BY orderIndex")
    fun observeExercisesForSession(sessionId: String): Flow<List<SessionExercise>>

    @Query("SELECT * FROM session_exercises WHERE activeSessionId = :sessionId ORDER BY orderIndex")
    suspend fun getExercisesForSession(sessionId: String): List<SessionExercise>

    // SessionSet
    @Insert suspend fun insertSet(set: SessionSet)

    @Update suspend fun updateSet(set: SessionSet)

    @Query("SELECT * FROM session_sets WHERE sessionExerciseId = :exerciseId ORDER BY setNumber")
    fun observeSetsForExercise(exerciseId: String): Flow<List<SessionSet>>

    @Query(
        "SELECT * FROM session_sets WHERE sessionExerciseId IN (:exerciseIds) ORDER BY setNumber"
    )
    fun observeSetsForExercises(exerciseIds: List<String>): Flow<List<SessionSet>>

    @Query(
        "SELECT * FROM session_sets WHERE sessionExerciseId IN (:exerciseIds) ORDER BY setNumber"
    )
    suspend fun getSetsForExercises(exerciseIds: List<String>): List<SessionSet>

    @Transaction
    suspend fun startNewSession(session: ActiveSession, exercises: List<SessionExercise>) {
        val existing = getActiveSession()
        if (existing != null) {
            deleteSession(existing.id)
        }
        insertSession(session)
        insertSessionExercises(exercises)
    }
}
