package com.example.ironpath.ui.screens.aiprivacy

import com.example.ironpath.domain.planner.OnDeviceModelClient
import com.example.ironpath.domain.planner.OnDeviceModelGeneration
import com.example.ironpath.domain.planner.OnDeviceModelPrompt
import com.example.ironpath.domain.planner.OnDeviceModelStatus
import com.example.ironpath.util.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AiPrivacyViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `model statuses become truthful user-visible availability`() = runTest {
        val expectations =
            mapOf(
                OnDeviceModelStatus.AVAILABLE to "Available on this device",
                OnDeviceModelStatus.DOWNLOADABLE to
                    "Available after the on-device model is downloaded",
                OnDeviceModelStatus.DOWNLOADING to "On-device model download in progress",
                OnDeviceModelStatus.UNAVAILABLE to
                    "Unavailable on this device — rule-based planning remains available",
            )

        expectations.forEach { (status, expected) ->
            val viewModel = AiPrivacyViewModel(FakeOnDeviceModelClient(status))

            advanceUntilIdle()

            assertEquals(expected, viewModel.uiState.value.availability)
        }
    }

    @Test
    fun `status failure reports unavailable without removing the offline fallback`() = runTest {
        val viewModel =
            AiPrivacyViewModel(
                FakeOnDeviceModelClient(error = IllegalStateException("provider failure"))
            )

        advanceUntilIdle()

        assertEquals(
            "Unavailable on this device — rule-based planning remains available",
            viewModel.uiState.value.availability,
        )
    }

    @Test
    fun `status timeout reports unavailable without removing the offline fallback`() = runTest {
        val viewModel = AiPrivacyViewModel(FakeOnDeviceModelClient(hang = true))

        advanceUntilIdle()

        assertEquals(
            "Unavailable on this device — rule-based planning remains available",
            viewModel.uiState.value.availability,
        )
    }

    private class FakeOnDeviceModelClient(
        private val status: OnDeviceModelStatus? = null,
        private val error: Throwable? = null,
        private val hang: Boolean = false,
    ) : OnDeviceModelClient {
        override suspend fun checkStatus(): OnDeviceModelStatus {
            if (hang) delay(Long.MAX_VALUE)
            error?.let { throw it }
            return checkNotNull(status)
        }

        override suspend fun generate(prompt: OnDeviceModelPrompt): OnDeviceModelGeneration =
            error("Generation is outside this test")
    }
}
