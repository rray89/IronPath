package com.example.ironpath.ui.screens.history

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WorkoutLogDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val historyRepository: HistoryRepository,
    private val recordRepository: RecordRepository,
) : ViewModel() {

    private val logId: String = savedStateHandle.get<String>(Route.WORKOUT_LOG_ID_ARG).orEmpty()

    private val _uiState =
        MutableStateFlow<WorkoutLogDetailUiState>(WorkoutLogDetailUiState.Loading)
    val uiState: StateFlow<WorkoutLogDetailUiState> = _uiState.asStateFlow()

    init {
        loadDetail()
    }

    private fun loadDetail() {
        viewModelScope.launch {
            val detail = historyRepository.getLogDetail(logId)
            _uiState.value =
                if (detail == null) {
                    WorkoutLogDetailUiState.NotFound
                } else {
                    WorkoutLogDetailUiState.Ready(
                        detail = detail,
                        savedSetIds =
                            detail.savedSetIdsFor(
                                recordRepository.getLoggedRecordsForWorkoutLog(detail.log.id),
                            ),
                    )
                }
        }
    }

    fun saveSetAsRecord(
        exerciseDetail: LoggedExerciseDetail,
        set: LoggedSet,
    ) {
        val ready = _uiState.value as? WorkoutLogDetailUiState.Ready ?: return
        val reps = set.reps
        val weight = set.weightKg
        if (reps == null || weight == null || weight <= 0.0) {
            updateReady(
                ready.copy(recordMessage = "Only completed weighted sets can become records.")
            )
            return
        }

        viewModelScope.launch {
            val exerciseName = exerciseDetail.exercise.name.trim()
            val achievedOn = ready.detail.log.completedAt.toRecordDate()
            val normalizedName = exerciseName.lowercase().trim()
            val isDuplicate =
                recordRepository.isDuplicateExcluding(
                    normalizedName = normalizedName,
                    date = achievedOn,
                    weight = weight,
                    excludeId = "",
                )
            if (isDuplicate) {
                updateDuplicateRecordState(ready, set)
                return@launch
            }

            val record =
                PersonalRecord(
                    exerciseName = exerciseName,
                    normalizedExerciseName = normalizedName,
                    weightKg = weight,
                    achievedOn = achievedOn,
                    note = "${ready.detail.log.title} · set ${set.setNumber}, $reps reps",
                    sourceType = RecordSource.Logged,
                    sourceWorkoutLogId = ready.detail.log.id,
                )
            try {
                recordRepository.insertRecord(record)
                updateReady(
                    ready.copy(
                        savedSetIds =
                            ready.savedSetIds + ready.detail.savedSetIdsFor(listOf(record)),
                        recordMessage = "Record saved from this workout.",
                    ),
                )
            } catch (_: SQLiteConstraintException) {
                updateDuplicateRecordState(ready, set)
            }
        }
    }

    private fun updateReady(state: WorkoutLogDetailUiState.Ready) {
        _uiState.value = state
    }

    private suspend fun updateDuplicateRecordState(
        ready: WorkoutLogDetailUiState.Ready,
        set: LoggedSet,
    ) {
        val persistedSavedSetIds =
            ready.detail.savedSetIdsFor(
                recordRepository.getLoggedRecordsForWorkoutLog(ready.detail.log.id),
            )
        val savedSetIds = ready.savedSetIds + persistedSavedSetIds
        updateReady(
            ready.copy(
                savedSetIds = savedSetIds,
                recordMessage =
                    if (savedSetIds.contains(set.id)) {
                        "Record already saved from this workout."
                    } else {
                        "A record with this exercise, date, and weight already exists."
                    },
            ),
        )
    }

    private fun Long.toRecordDate(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun WorkoutLogDetail.savedSetIdsFor(records: List<PersonalRecord>): Set<String> {
        val achievedOn = log.completedAt.toRecordDate()
        val savedKeys =
            records
                .filter {
                    it.sourceType == RecordSource.Logged &&
                        it.sourceWorkoutLogId == log.id &&
                        it.achievedOn == achievedOn
                }
                .map { SavedRecordKey(it.normalizedExerciseName, it.achievedOn, it.weightKg) }
                .toSet()

        return exercises
            .flatMap { exerciseDetail ->
                val normalizedName = exerciseDetail.exercise.name.normalizedRecordName()
                exerciseDetail.sets.filter { set ->
                    val weight = set.weightKg
                    set.reps != null &&
                        weight != null &&
                        weight > 0.0 &&
                        SavedRecordKey(normalizedName, achievedOn, weight) in savedKeys
                }
            }
            .map { it.id }
            .toSet()
    }

    private fun String.normalizedRecordName(): String = lowercase().trim()
}

private data class SavedRecordKey(
    val normalizedExerciseName: String,
    val achievedOn: String,
    val weightKg: Double,
)

sealed interface WorkoutLogDetailUiState {
    data object Loading : WorkoutLogDetailUiState

    data object NotFound : WorkoutLogDetailUiState

    data class Ready(
        val detail: WorkoutLogDetail,
        val savedSetIds: Set<String> = emptySet(),
        val recordMessage: String? = null,
    ) : WorkoutLogDetailUiState
}
