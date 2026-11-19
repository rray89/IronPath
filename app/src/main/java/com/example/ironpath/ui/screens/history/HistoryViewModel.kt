package com.example.ironpath.ui.screens.history

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.local.entity.RecordSource
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.domain.account.ProfileGenerationToken
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
import com.example.ironpath.domain.validation.RecordDraftResult
import com.example.ironpath.domain.validation.RecordDraftValidator
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel
@Inject
constructor(
    private val historyRepository: HistoryRepository,
    private val recordRepository: RecordRepository,
    private val planRepository: PlanRepository,
    private val timeProvider: TimeProvider,
    private val idProvider: IdProvider,
    private val profileGenerationToken: ProfileGenerationToken? = null,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(HistoryTab.Logs)
    val selectedTab: StateFlow<HistoryTab> = _selectedTab.asStateFlow()

    val logs =
        historyRepository
            .observeAllLogs()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val records =
        recordRepository
            .observeAllRecords()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Add Record form state
    private val _addRecordShown = MutableStateFlow(false)
    val addRecordShown: StateFlow<Boolean> = _addRecordShown.asStateFlow()

    private val _addRecordError = MutableStateFlow<String?>(null)
    val addRecordError: StateFlow<String?> = _addRecordError.asStateFlow()

    private val _isSavingRecord = MutableStateFlow(false)
    val isSavingRecord: StateFlow<Boolean> = _isSavingRecord.asStateFlow()
    private val _editingRecord = MutableStateFlow<PersonalRecord?>(null)
    val editingRecord: StateFlow<PersonalRecord?> = _editingRecord.asStateFlow()

    // Exercise name suggestions from both plans and existing records
    private val _exerciseSuggestions = MutableStateFlow<List<String>>(emptyList())
    val exerciseSuggestions: StateFlow<List<String>> = _exerciseSuggestions.asStateFlow()

    init {
        profileGenerationToken?.let { token ->
            viewModelScope.launch { runCatching { token.initialize() } }
        }
    }

    fun selectTab(tab: HistoryTab) {
        _selectedTab.value = tab
    }

    fun today(): LocalDate = timeProvider.today()

    val zoneId: ZoneId
        get() = timeProvider.zoneId

    private fun loadSuggestions() {
        viewModelScope.launch {
            val planNames = planRepository.getAllExerciseNames()
            val recordNames = recordRepository.getAllRecordExerciseNames()
            _exerciseSuggestions.value = (planNames + recordNames).distinct().sorted()
        }
    }

    fun showAddRecord() {
        if (_isSavingRecord.value) return
        _editingRecord.value = null
        _addRecordError.value = null
        loadSuggestions()
        _addRecordShown.value = true
    }

    fun showEditRecord(record: PersonalRecord) {
        if (_isSavingRecord.value || record.sourceType != RecordSource.Manual) return
        _editingRecord.value = record
        _addRecordError.value = null
        loadSuggestions()
        _addRecordShown.value = true
    }

    fun hideAddRecord() {
        if (_isSavingRecord.value) return
        _editingRecord.value = null
        _addRecordShown.value = false
        _addRecordError.value = null
    }

    fun clearAddRecordError() {
        _addRecordError.value = null
    }

    fun saveRecord(draft: ValidatedRecordDraft, onSaved: () -> Unit) {
        if (_isSavingRecord.value || !_addRecordShown.value) return
        val validation =
            RecordDraftValidator()
                .validate(
                    draft.exerciseName,
                    draft.weightKg.toString(),
                    draft.achievedOn,
                    draft.note.orEmpty(),
                    today()
                )
        if (validation is RecordDraftResult.Invalid) {
            _addRecordError.value = validation.errors.values.first()
            return
        }
        val validDraft = (validation as RecordDraftResult.Valid).draft
        val editingId = _editingRecord.value?.id
        val expectedProfileGeneration = profileGenerationToken?.current()
        if (profileGenerationToken != null && expectedProfileGeneration == null) {
            _addRecordError.value = "Profile is not ready. Please try again."
            return
        }
        _isSavingRecord.value = true
        _addRecordError.value = null
        viewModelScope.launch {
            try {
                try {
                    if (editingId != null) {
                        recordRepository.updateManualRecord(
                            editingId,
                            validDraft,
                            expectedProfileGeneration
                        )
                    } else {
                        val record =
                            PersonalRecord(
                                id = idProvider.newId(),
                                exerciseName = validDraft.exerciseName,
                                normalizedExerciseName = validDraft.normalizedExerciseName,
                                weightKg = validDraft.weightKg,
                                achievedOn = validDraft.achievedOn,
                                note = validDraft.note,
                                createdAt = timeProvider.epochMillis(),
                            )
                        recordRepository.insertRecord(record, expectedProfileGeneration)
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: SQLiteConstraintException) {
                    _addRecordError.value =
                        "A record with this exercise, date, and weight already exists."
                    return@launch
                } catch (_: Exception) {
                    _addRecordError.value = "Unable to save record. Please try again."
                    return@launch
                }
                _addRecordShown.value = false
                _editingRecord.value = null
                onSaved()
            } finally {
                _isSavingRecord.value = false
            }
        }
    }

    fun deleteEditingRecord() {
        val id = _editingRecord.value?.id ?: return
        if (_isSavingRecord.value) return
        val generation = profileGenerationToken?.current()
        if (profileGenerationToken != null && generation == null) {
            _addRecordError.value = "Profile is not ready. Please try again."
            return
        }
        _isSavingRecord.value = true
        _addRecordError.value = null
        viewModelScope.launch {
            try {
                recordRepository.deleteManualRecord(id, generation)
                _addRecordShown.value = false
                _editingRecord.value = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _addRecordError.value = "Unable to delete record. Please try again."
            } finally {
                _isSavingRecord.value = false
            }
        }
    }
}

enum class HistoryTab {
    Logs,
    Records
}
