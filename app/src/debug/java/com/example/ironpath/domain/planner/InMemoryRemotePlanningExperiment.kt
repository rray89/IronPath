package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class InMemoryRemotePlanningExperiment @Inject constructor() : RemotePlanningExperiment {
    private val mutableState = MutableStateFlow(RemotePlanningExperimentState(available = true))
    override val state: StateFlow<RemotePlanningExperimentState> = mutableState.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        mutableState.update {
            if (enabled) it.copy(enabled = true) else it.copy(enabled = false, apiKey = "")
        }
    }

    override fun setApiKey(apiKey: String) {
        mutableState.update { it.copy(apiKey = apiKey.trim().take(MAX_API_KEY_LENGTH)) }
    }

    private companion object {
        const val MAX_API_KEY_LENGTH = 512
    }
}
