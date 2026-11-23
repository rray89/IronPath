package com.example.ironpath.ui.screens.plan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.planner.PlanningFailure

internal fun AiGenerationUiState.remoteFailure(): PlanningFailure.Remote? =
    (this as? AiGenerationUiState.Failed)?.failure as? PlanningFailure.Remote

@Composable
internal fun RemotePlanningFailureNotice(
    failure: PlanningFailure.Remote,
    draftRetained: Boolean,
    onDismiss: () -> Unit
) {
    Surface(
        modifier =
            Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics {
                liveRegion = LiveRegionMode.Polite
            },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${failure.provider} could not generate a plan",
                style = MaterialTheme.typography.titleMedium
            )
            Text(failure.kind.guidance)
            failure.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                if (draftRetained) "Your existing draft is still available."
                else "Your plan settings are still here."
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
