package com.example.ironpath.domain.planner

import kotlinx.coroutines.flow.StateFlow

data class RemotePlanningExperimentState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val apiKey: String = "",
) {
    val configured: Boolean
        get() = available && enabled && apiKey.isNotBlank()

    override fun toString() =
        "RemotePlanningExperimentState(available=$available, enabled=$enabled, apiKey=<redacted>)"
}

interface RemotePlanningExperiment {
    val state: StateFlow<RemotePlanningExperimentState>

    fun setEnabled(enabled: Boolean)

    fun setApiKey(apiKey: String)
}
