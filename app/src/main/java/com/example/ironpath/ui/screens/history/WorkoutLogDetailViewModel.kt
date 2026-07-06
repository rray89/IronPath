package com.example.ironpath.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.WorkoutLogDetail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WorkoutLogDetailViewModel(
    private val logId: String,
    private val historyRepository: HistoryRepository,
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
}

sealed interface WorkoutLogDetailUiState {
    data object Loading : WorkoutLogDetailUiState

    data object NotFound : WorkoutLogDetailUiState

    data class Ready(val detail: WorkoutLogDetail) : WorkoutLogDetailUiState
}
