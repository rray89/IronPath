package com.example.ironpath.ui.screens.plan

import androidx.compose.runtime.Composable
import com.example.ironpath.domain.planner.RemotePlanningExperimentState

/** Inert source-set seam: the remote experiment UI is only compiled in Debug. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun RemoteAiLab(
    state: RemotePlanningExperimentState,
    onEnabledChanged: (Boolean) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onOptionChanged: (String) -> Unit,
) = Unit
