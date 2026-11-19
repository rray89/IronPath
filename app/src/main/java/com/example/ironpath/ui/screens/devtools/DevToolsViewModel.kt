package com.example.ironpath.ui.screens.devtools

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.dev.DevToolsSeeder
import com.example.ironpath.domain.account.ProfileGenerationToken
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DevToolsViewModel
@Inject
constructor(
    private val seeder: DevToolsSeeder,
    private val profileGenerationToken: ProfileGenerationToken? = null,
) : ViewModel() {

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _showClearConfirm = MutableStateFlow(false)
    val showClearConfirm: StateFlow<Boolean> = _showClearConfirm.asStateFlow()
    private var clearProfileGeneration: Long? = null

    init {
        profileGenerationToken?.let { token ->
            viewModelScope.launch { runCatching { token.initialize() } }
        }
    }

    fun seedPlanForToday() {
        val generation = requestProfileGeneration() ?: return
        runAction("Plan seeded for today") { seeder.seedPlanForToday(generation) }
    }

    fun seedPlanForTomorrow() {
        val generation = requestProfileGeneration() ?: return
        runAction("Plan seeded for tomorrow") { seeder.seedPlanForTomorrow(generation) }
    }

    fun seedHistoryLogs() {
        val generation = requestProfileGeneration() ?: return
        runAction("History logs seeded") { seeder.seedHistoryLogs(generation) }
    }

    fun seedRecords() {
        val generation = requestProfileGeneration() ?: return
        runAction("Personal records seeded") { seeder.seedRecords(generation) }
    }

    fun requestClearConfirmation() {
        clearProfileGeneration = requestProfileGeneration() ?: return
        _showClearConfirm.value = true
    }

    fun dismissClearConfirmation() {
        _showClearConfirm.value = false
    }

    fun confirmClearAllData(onComplete: () -> Unit) {
        _showClearConfirm.value = false
        val expectedProfileGeneration = clearProfileGeneration
        clearProfileGeneration = null
        viewModelScope.launch {
            try {
                seeder.clearAllData(expectedProfileGeneration)
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

    private fun requestProfileGeneration(): Long? =
        if (profileGenerationToken == null) null else profileGenerationToken.current()

    private suspend fun showStatus(message: String) {
        _status.value = message
        delay(3_000)
        _status.value = null
    }
}
