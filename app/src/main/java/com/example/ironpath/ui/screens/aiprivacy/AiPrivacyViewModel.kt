package com.example.ironpath.ui.screens.aiprivacy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ironpath.domain.planner.OnDeviceModelClient
import com.example.ironpath.domain.planner.OnDeviceModelStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AiPrivacyUiState(val availability: String = "Checking on-device availability…")

@HiltViewModel
class AiPrivacyViewModel @Inject constructor(private val onDeviceModelClient: OnDeviceModelClient) :
    ViewModel() {
    private val _uiState = MutableStateFlow(AiPrivacyUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val availability =
                try {
                    withTimeoutOrNull(STATUS_TIMEOUT_MILLIS) { onDeviceModelClient.checkStatus() }
                        ?.availabilityText() ?: UNAVAILABLE_TEXT
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    UNAVAILABLE_TEXT
                }
            _uiState.value = AiPrivacyUiState(availability)
        }
    }

    private fun OnDeviceModelStatus.availabilityText(): String =
        when (this) {
            OnDeviceModelStatus.AVAILABLE -> "Available on this device"
            OnDeviceModelStatus.DOWNLOADABLE -> "Available after the on-device model is downloaded"
            OnDeviceModelStatus.DOWNLOADING -> "On-device model download in progress"
            OnDeviceModelStatus.UNAVAILABLE -> UNAVAILABLE_TEXT
        }

    private companion object {
        const val STATUS_TIMEOUT_MILLIS = 10_000L
        const val UNAVAILABLE_TEXT =
            "Unavailable on this device — rule-based planning remains available"
    }
}
