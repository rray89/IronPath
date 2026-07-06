package com.example.ironpath.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.LoggedSet
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.LoggedExerciseDetail
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.WorkoutLogDetail
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkoutLogDetailViewModel(
    private val logId: String,
    private val historyRepository: HistoryRepository,
    private val recordRepository: RecordRepository,
) : ViewModel() {

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
                    WorkoutLogDetailUiState.Ready(detail)
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
                updateReady(
                    ready.copy(
                        recordMessage =
                            "A record with this exercise, date, and weight already exists.",
                    ),
                )
                return@launch
            }

            recordRepository.insertRecord(
                PersonalRecord(
                    exerciseName = exerciseName,
                    normalizedExerciseName = normalizedName,
                    weightKg = weight,
                    achievedOn = achievedOn,
                    note = "${ready.detail.log.title} · set ${set.setNumber}, $reps reps",
                    sourceType = RecordSource.Logged,
                    sourceWorkoutLogId = ready.detail.log.id,
                ),
            )
            updateReady(
                ready.copy(
                    savedSetIds = ready.savedSetIds + set.id,
                    recordMessage = "Record saved from this workout.",
                ),
            )
        }
    }

    private fun updateReady(state: WorkoutLogDetailUiState.Ready) {
        _uiState.value = state
    }

    private fun Long.toRecordDate(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

sealed interface WorkoutLogDetailUiState {
    data object Loading : WorkoutLogDetailUiState

    data object NotFound : WorkoutLogDetailUiState

    data class Ready(
        val detail: WorkoutLogDetail,
        val savedSetIds: Set<String> = emptySet(),
        val recordMessage: String? = null,
    ) : WorkoutLogDetailUiState
}
