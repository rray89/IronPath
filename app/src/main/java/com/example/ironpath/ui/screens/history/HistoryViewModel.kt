package com.example.ironpath.ui.screens.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.data.repository.HistoryRepository
import com.example.ironpath.data.repository.PlanRepository
import com.example.ironpath.data.repository.RecordRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(
  private val historyRepository: HistoryRepository,
  private val recordRepository: RecordRepository,
  private val planRepository: PlanRepository,
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

  // Exercise name suggestions from both plans and existing records
  private val _exerciseSuggestions = MutableStateFlow<List<String>>(emptyList())
  val exerciseSuggestions: StateFlow<List<String>> = _exerciseSuggestions.asStateFlow()

  fun selectTab(tab: HistoryTab) {
    _selectedTab.value = tab
  }

  fun showAddRecord() {
    viewModelScope.launch {
      val planNames = planRepository.getAllExerciseNames()
      val recordNames = recordRepository.getAllRecordExerciseNames()
      _exerciseSuggestions.value = (planNames + recordNames).distinct().sorted()
    }
    _addRecordShown.value = true
  }

  fun hideAddRecord() {
    _addRecordShown.value = false
  }

  fun saveRecord(record: PersonalRecord, onSaved: () -> Unit) {
    viewModelScope.launch {
      recordRepository.insertRecord(record)
      _addRecordShown.value = false
      onSaved()
    }
  }
}

enum class HistoryTab {
  Logs,
  Records
}
