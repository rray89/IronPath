package com.example.ironpath.ui.screens.devtools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.dev.DevToolsSeeder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DevToolsViewModel(private val seeder: DevToolsSeeder) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _showClearConfirm = MutableStateFlow(false)
    val showClearConfirm: StateFlow<Boolean> = _showClearConfirm.asStateFlow()

    fun seedPlanForToday() = runAction("Plan seeded for today") { seeder.seedPlanForToday() }

    fun seedPlanForTomorrow() =
        runAction("Plan seeded for tomorrow") { seeder.seedPlanForTomorrow() }

    fun seedHistoryLogs() = runAction("History logs seeded") { seeder.seedHistoryLogs() }

    fun seedRecords() = runAction("Personal records seeded") { seeder.seedRecords() }

    fun requestClearConfirmation() {
        _showClearConfirm.value = true
    }

    fun dismissClearConfirmation() {
        _showClearConfirm.value = false
    }

    fun confirmClearAllData(onComplete: () -> Unit) {
        _showClearConfirm.value = false
        viewModelScope.launch {
            try {
                seeder.clearAllData()
                onComplete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus(e.message ?: "Failed to clear data")
            }
        }
    }

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                showStatus(successMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showStatus(e.message ?: "Something went wrong")
            }
        }
    }

    private suspend fun showStatus(message: String) {
        _status.value = message
        delay(3_000)
        _status.value = null
    }
}
