package com.example.ironpath.ui.screens.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun ManualScreen(modifier: Modifier = Modifier) {
    var expandedTopic by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "MANUAL",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Offline guidance for planning and training with IronPath.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        manualTopics.forEach { topic ->
            val isExpanded = expandedTopic == topic.title
            Card(
                onClick = { expandedTopic = if (isExpanded) null else topic.title },
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                    },
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = topic.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isExpanded) {
                        Text(
                            text = topic.guidance,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

private data class ManualTopic(
    val title: String,
    val guidance: String,
)

private val manualTopics =
    listOf(
        ManualTopic(
            title = "Getting started",
            guidance = "Create a plan when you are ready. IronPath works without an account.",
        ),
        ManualTopic(
            title = "Planning a week",
            guidance = "Choose a goal, training days, experience, equipment, and any preferences.",
        ),
        ManualTopic(
            title = "Reviewing and accepting a plan",
            guidance =
                "Review each workout before accepting it. Accepted plans are saved on this device.",
        ),
        ManualTopic(
            title = "Starting and completing a workout",
            guidance = "Start a planned workout, log sets, and complete it when finished.",
        ),
        ManualTopic(
            title = "History and personal records",
            guidance =
                "Completed workouts appear in History. Personal records are added separately.",
        ),
        ManualTopic(
            title = "AI planning and validation",
            guidance = "AI suggestions are validated before they can become a plan.",
        ),
        ManualTopic(
            title = "On-device availability and rule-based fallback",
            guidance =
                "On-device planning is used when available. Rule-based planning remains available offline.",
        ),
        ManualTopic(
            title = "Backup, restore, and local-only behavior",
            guidance =
                "Your durable workout data is saved locally in Room. IronPath cloud backup and " +
                    "restore are not available in this version, and Android cloud backup is " +
                    "turned off. On Android 12 and higher, Android device-to-device transfer can " +
                    "copy your workout database when you set up a new phone.",
        ),
    )
