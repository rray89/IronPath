package com.example.ironpath.domain.planner

import kotlinx.coroutines.flow.StateFlow

data class RemotePlanningOption(
    val id: String,
    val label: String,
    val supportsOneRequestNotes: Boolean = false,
)

data class RemotePlanningExperimentState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val apiKey: String = "",
    val optionId: String = "",
    val options: List<RemotePlanningOption> = emptyList(),
    val revision: Long = 0,
) {
    val configured: Boolean
        get() = available && enabled && apiKey.isNotBlank()

    val canShareTrainingNotes: Boolean
        get() = configured && options.any { it.id == optionId && it.supportsOneRequestNotes }

    override fun toString() =
        "RemotePlanningExperimentState(available=$available, enabled=$enabled, apiKey=<redacted>)"
}

interface RemotePlanningExperiment {
    val state: StateFlow<RemotePlanningExperimentState>

    fun setEnabled(enabled: Boolean)

    fun setApiKey(apiKey: String)

    fun setOption(optionId: String) = Unit
}
