package com.example.ironpath.data.repository

import com.example.ironpath.data.backup.BackupChangeTracker
import com.example.ironpath.data.local.IronPathDatabase
import com.example.ironpath.data.local.dao.RecordDao
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.local.withProfileWrite
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class RecordRepository
@Inject
constructor(
    private val recordDao: RecordDao,
    private val database: IronPathDatabase,
    private val backupChangeTracker: BackupChangeTracker,
) {

    fun observeAllRecords(): Flow<List<PersonalRecord>> = recordDao.observeAllRecords()

    suspend fun getAllRecordExerciseNames(): List<String> = recordDao.getAllRecordExerciseNames()

    suspend fun insertRecord(record: PersonalRecord, expectedProfileGeneration: Long? = null) =
        database.withProfileWrite(expectedProfileGeneration) {
            recordDao.insertRecord(record)
            backupChangeTracker.markIncludedDataChanged()
        }

    suspend fun updateManualRecord(
        id: String,
        draft: ValidatedRecordDraft,
        expectedProfileGeneration: Long? = null
    ) =
        database.withProfileWrite(expectedProfileGeneration) {
            val current = requireNotNull(recordDao.getRecordById(id)) { "Record no longer exists" }
            require(current.sourceType == RecordSource.Manual) { "Logged records are read-only" }
            val updated =
                current.copy(
                    exerciseName = draft.exerciseName.trim(),
                    normalizedExerciseName = draft.exerciseName.trim().lowercase(),
                    weightKg = draft.weightKg,
                    achievedOn = draft.achievedOn,
                    note = draft.note,
                )
            // Room's unique index excludes this row naturally on an in-place update.
            if (updated != current) {
                recordDao.updateRecord(updated)
                backupChangeTracker.markIncludedDataChanged()
            }
        }

    suspend fun deleteManualRecord(id: String, expectedProfileGeneration: Long? = null) =
        database.withProfileWrite(expectedProfileGeneration) {
            val current = requireNotNull(recordDao.getRecordById(id)) { "Record no longer exists" }
            require(current.sourceType == RecordSource.Manual) { "Logged records are read-only" }
            recordDao.deleteRecord(id)
            backupChangeTracker.markIncludedDataChanged()
        }

    suspend fun getLoggedRecordsForWorkoutLog(logId: String): List<PersonalRecord> =
        recordDao.getLoggedRecordsForWorkoutLog(logId)

    /** Resolve the source again under the profile fence; a UI snapshot is not write authority. */
    suspend fun saveLoggedSetAsRecord(
        logId: String,
        setId: String,
        recordId: String,
        createdAt: Long,
        zoneId: ZoneId,
        expectedProfileGeneration: Long? = null,
    ): PersonalRecord =
        database.withProfileWrite(expectedProfileGeneration) {
            val history = database.historyDao()
            val log =
                requireNotNull(history.getLogById(logId)) { "Source workout no longer exists" }
            val exercises = history.getLoggedExercisesForLog(logId)
            val sets =
                if (exercises.isEmpty()) emptyList()
                else history.getLoggedSetsForExercises(exercises.map { it.id })
            val detail =
                WorkoutLogDetail(
                    log,
                    exercises.map { exercise ->
                        LoggedExerciseDetail(
                            exercise,
                            sets.filter { it.loggedExerciseId == exercise.id }
                        )
                    }
                )
            val candidate =
                requireNotNull(detail.recordCandidates(zoneId).find { it.setId == setId }) {
                    "Set cannot become a record"
                }
            // Same source performance stays one record even after the device changes time zone.
            // Other sources/manual entries still use the global name/date/weight unique index.
            val existing =
                recordDao.getLoggedRecordsForWorkoutLog(logId).firstOrNull {
                    it.normalizedExerciseName == candidate.normalizedExerciseName &&
                        it.weightKg == candidate.weightKg
                }
            if (existing != null) return@withProfileWrite existing
            val record =
                PersonalRecord(
                    id = recordId,
                    exerciseName = candidate.exerciseName,
                    normalizedExerciseName = candidate.normalizedExerciseName,
                    weightKg = candidate.weightKg,
                    achievedOn = candidate.achievedOn,
                    note = candidate.note,
                    sourceType = RecordSource.Logged,
                    sourceWorkoutLogId = logId,
                    createdAt = createdAt,
                )
            recordDao.insertRecord(record)
            backupChangeTracker.markIncludedDataChanged()
            record
        }
}
