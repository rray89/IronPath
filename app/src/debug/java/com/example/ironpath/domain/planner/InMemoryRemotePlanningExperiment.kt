package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class InMemoryRemotePlanningExperiment @Inject constructor() : RemotePlanningExperiment {
    private val mutableState =
        MutableStateFlow(
            RemotePlanningExperimentState(
                available = true,
                optionId = RemotePlanningRoute.GEMINI.name,
                options = RemotePlanningRoute.entries.map { it.option },
            )
        )
    override val state: StateFlow<RemotePlanningExperimentState> = mutableState.asStateFlow()

    override fun setEnabled(enabled: Boolean) = change {
        if (enabled) copy(enabled = true) else copy(enabled = false, apiKey = "")
    }

    override fun setApiKey(apiKey: String) = change {
        copy(apiKey = apiKey.trim().take(MAX_API_KEY_LENGTH))
    }

    override fun setOption(optionId: String) {
        if (RemotePlanningRoute.entries.none { it.name == optionId }) return
        change {
            if (this.optionId == optionId) this
            else copy(optionId = optionId, apiKey = "", enabled = false)
        }
    }

    private fun change(
        transform: RemotePlanningExperimentState.() -> RemotePlanningExperimentState
    ) {
        mutableState.update { previous ->
            val next = previous.transform()
            if (next == previous) previous else next.copy(revision = previous.revision + 1)
        }
    }

    private companion object {
        const val MAX_API_KEY_LENGTH = 512
    }
}
