package com.example.ironpath.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import com.example.ironpath.domain.identity.IdProvider
import com.example.ironpath.domain.time.TimeProvider
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

    private var isSavingRecord = false

    // Edit Record state
    private val _editingRecord = MutableStateFlow<PersonalRecord?>(null)
    val editingRecord: StateFlow<PersonalRecord?> = _editingRecord.asStateFlow()

    // Edit Record error (e.g. duplicate constraint)
    private val _editRecordError = MutableStateFlow<String?>(null)
    val editRecordError: StateFlow<String?> = _editRecordError.asStateFlow()

    // Exercise name suggestions from both plans and existing records
    private val _exerciseSuggestions = MutableStateFlow<List<String>>(emptyList())
    val exerciseSuggestions: StateFlow<List<String>> = _exerciseSuggestions.asStateFlow()

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
        loadSuggestions()
        _addRecordShown.value = true
    }

    fun hideAddRecord() {
        _addRecordShown.value = false
        _addRecordError.value = null
    }

    fun clearAddRecordError() {
        _addRecordError.value = null
    }

    fun showEditRecord(record: PersonalRecord) {
        loadSuggestions()
        _editingRecord.value = record
    }

    fun hideEditRecord() {
        _editingRecord.value = null
        _editRecordError.value = null
    }

    fun clearEditRecordError() {
        _editRecordError.value = null
    }

    fun saveRecord(draft: ValidatedRecordDraft, onSaved: () -> Unit) {
        if (isSavingRecord) return
        isSavingRecord = true
        _addRecordError.value = null
        viewModelScope.launch {
            try {
                try {
                    val record =
                        PersonalRecord(
                            id = idProvider.newId(),
                            exerciseName = draft.exerciseName,
                            normalizedExerciseName = draft.normalizedExerciseName,
                            weightKg = draft.weightKg,
                            achievedOn = draft.achievedOn,
                            note = draft.note,
                            createdAt = timeProvider.epochMillis(),
                        )
                    recordRepository.insertRecord(record)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    _addRecordError.value = "Unable to save record. Please try again."
                    return@launch
                }
                _addRecordShown.value = false
                onSaved()
            } finally {
                isSavingRecord = false
            }
        }
    }

    fun updateRecord(record: PersonalRecord, onUpdated: () -> Unit = {}) {
        viewModelScope.launch {
            val isDuplicate =
                recordRepository.isDuplicateExcluding(
                    normalizedName = record.normalizedExerciseName,
                    date = record.achievedOn,
                    weight = record.weightKg,
                    excludeId = record.id,
                )
            if (isDuplicate) {
                _editRecordError.value =
                    "A record with this exercise, date, and weight already exists"
                return@launch
            }
            recordRepository.updateRecord(record)
            _editingRecord.value = null
            _editRecordError.value = null
            onUpdated()
        }
    }

    fun deleteRecord(id: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            recordRepository.deleteRecord(id)
            _editingRecord.value = null
            onDeleted()
        }
    }
}

enum class HistoryTab {
    Logs,
    Records
}
