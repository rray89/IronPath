package com.example.ironpath.ui.screens.history

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.data.repository.WorkoutLogDetail
import com.example.ironpath.data.repository.recordCandidates
import com.example.ironpath.data.repository.savedRecordSetIds
import com.example.ironpath.domain.account.ProfileGenerationToken
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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
    private val recordRepository: RecordRepository,
    private val idProvider: IdProvider,
    private val profileGenerationToken: ProfileGenerationToken? = null,
) : ViewModel() {
    private val logId: String = savedStateHandle.get<String>(Route.WORKOUT_LOG_ID_ARG).orEmpty()
    private val readOnly = savedStateHandle.get<Boolean>(Route.RECORD_SOURCE_ARG) ?: false
    private val _uiState =
        MutableStateFlow<WorkoutLogDetailUiState>(WorkoutLogDetailUiState.Loading)
    val uiState: StateFlow<WorkoutLogDetailUiState> = _uiState.asStateFlow()
    val zoneId: ZoneId
        get() = timeProvider.zoneId

    private var loading = false

    init {
        loadDetail()
    }

    fun loadDetail() {
        if (loading || _uiState.value is WorkoutLogDetailUiState.Ready) return
        loading = true
        _uiState.value = WorkoutLogDetailUiState.Loading
        viewModelScope.launch {
            try {
                profileGenerationToken?.initialize()
                val detail = historyRepository.getLogDetail(logId)
                _uiState.value =
                    if (detail == null) WorkoutLogDetailUiState.NotFound
                    else
                        WorkoutLogDetailUiState.Ready(
                            detail = detail,
                            savedSetIds =
                                detail.savedRecordSetIds(
                                    recordRepository.getLoggedRecordsForWorkoutLog(logId),
                                    zoneId
                                ),
                            readOnly = readOnly,
                        )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _uiState.value = WorkoutLogDetailUiState.Failed
            } finally {
                loading = false
            }
        }
    }

    fun saveSetAsRecord(setId: String) {
        val ready = _uiState.value as? WorkoutLogDetailUiState.Ready ?: return
        if (ready.readOnly || ready.saving || setId in ready.savedSetIds) return
        if (ready.detail.recordCandidates(zoneId).none { it.setId == setId }) return
        val generation = profileGenerationToken?.current()
        if (profileGenerationToken != null && generation == null) return
        _uiState.value = ready.copy(saving = true, recordMessage = null)
        viewModelScope.launch {
            try {
                val record =
                    recordRepository.saveLoggedSetAsRecord(
                        logId,
                        setId,
                        idProvider.newId(),
                        timeProvider.epochMillis(),
                        zoneId,
                        generation
                    )
                updateReady {
                    it.copy(
                        savedSetIds =
                            it.savedSetIds + it.detail.savedRecordSetIds(listOf(record), zoneId),
                        recordMessage = "Record saved from this workout."
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SQLiteConstraintException) {
                try {
                    val records = recordRepository.getLoggedRecordsForWorkoutLog(logId)
                    updateReady {
                        val saved = it.detail.savedRecordSetIds(records, zoneId)
                        it.copy(
                            savedSetIds = saved,
                            recordMessage =
                                if (setId in saved) "Record already saved from this workout."
                                else "A record with this exercise, date, and weight already exists."
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    updateReady {
                        it.copy(recordMessage = "Unable to check saved records. Please try again.")
                    }
                }
            } catch (_: Exception) {
                updateReady { it.copy(recordMessage = "Unable to save record. Please try again.") }
            } finally {
                updateReady { it.copy(saving = false) }
            }
        }
    }

    private fun updateReady(
        transform: (WorkoutLogDetailUiState.Ready) -> WorkoutLogDetailUiState.Ready
    ) {
        val ready = _uiState.value as? WorkoutLogDetailUiState.Ready ?: return
        _uiState.value = transform(ready)
    }
}

sealed interface WorkoutLogDetailUiState {
    data object Loading : WorkoutLogDetailUiState

    data object NotFound : WorkoutLogDetailUiState

    data object Failed : WorkoutLogDetailUiState

    data class Ready(
        val detail: WorkoutLogDetail,
        val savedSetIds: Set<String> = emptySet(),
        val saving: Boolean = false,
        val recordMessage: String? = null,
        val readOnly: Boolean = false,
    ) : WorkoutLogDetailUiState
}
