package com.example.ironpath.ui.screens.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class WorkoutLogDetailViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val historyRepository: HistoryRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val logId: String = savedStateHandle.get<String>(Route.WORKOUT_LOG_ID_ARG).orEmpty()

    private val _uiState =
        MutableStateFlow<WorkoutLogDetailUiState>(WorkoutLogDetailUiState.Loading)
    val uiState: StateFlow<WorkoutLogDetailUiState> = _uiState.asStateFlow()

    val zoneId: ZoneId
        get() = timeProvider.zoneId

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
                    WorkoutLogDetailUiState.Ready(detail = detail)
                }
        }
    }
}

sealed interface WorkoutLogDetailUiState {
    data object Loading : WorkoutLogDetailUiState

    data object NotFound : WorkoutLogDetailUiState

    data class Ready(
        val detail: WorkoutLogDetail,
    ) : WorkoutLogDetailUiState
}
