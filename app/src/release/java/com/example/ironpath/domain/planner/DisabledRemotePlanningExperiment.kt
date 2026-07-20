package com.example.ironpath.domain.planner

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@Singleton
class DisabledRemotePlanningExperiment @Inject constructor() : RemotePlanningExperiment {
    override val state: StateFlow<RemotePlanningExperimentState> =
        MutableStateFlow(RemotePlanningExperimentState())

    override fun setEnabled(enabled: Boolean) = Unit

    override fun setApiKey(apiKey: String) = Unit
}
