package com.example.ironpath.ui.screens.plan

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.planner.RemotePlanningExperimentState
import com.example.ironpath.domain.planner.RemotePlanningRoute
import com.example.ironpath.domain.planner.TrainingNoteForSharing
import com.example.ironpath.ui.testing.TestTags

@Composable
internal fun RemoteAiLab(
    state: RemotePlanningExperimentState,
    onEnabledChanged: (Boolean) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onOptionChanged: (String) -> Unit,
) {
    Text(
        "Remote AI Lab",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.testTag(TestTags.PLAN_REMOTE_AI_LAB)
    )
    Spacer(Modifier.height(12.dp))
    Column(Modifier.fillMaxWidth().selectableGroup()) {
        state.options.forEach { option ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = state.optionId == option.id,
                            role = Role.RadioButton,
                            onClick = { onOptionChanged(option.id) }
                        )
                        .testTag("plan_remote_option_" + option.id),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = state.optionId == option.id, onClick = null)
                Text(
                    option.label,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
    Text(
        if (state.optionId == RemotePlanningRoute.DEEPSEEK.name) {
            "DeepSeek receives goal, days, experience, equipment, movement limits and eligible exercises. You can choose to share Training Notes for a single request. Training history and Records stay on this device."
        } else {
            "Only goal, days, experience, equipment, movement limits and eligible exercises are sent to the selected provider. Notes and training history stay on device."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Use selected remote provider",
            modifier = Modifier.weight(1f).padding(end = 16.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        Switch(
            checked = state.enabled,
            onCheckedChange = onEnabledChanged,
            modifier =
                Modifier.testTag(TestTags.PLAN_REMOTE_AI_TOGGLE).semantics {
                    contentDescription = "Use remote AI experiment"
                }
        )
    }
    Text(
        "Debug only. Provider charges may apply. One request; no automatic retry. Cancel stops local waiting but may not stop provider processing or charges.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Key stays in memory. Disable, switch route or end the process to clear it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.enabled) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChanged,
            label = { Text("Provider API key") },
            supportingText = { Text("Required for remote generation. Never stored on disk.") },
            modifier = Modifier.fillMaxWidth().testTag(TestTags.PLAN_REMOTE_AI_KEY),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
        )
    }
}

@Composable
internal fun RemoteTrainingNotesDialog(
    action: String,
    notes: List<TrainingNoteForSharing>,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit,
) {
    var shareNotes by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$action with DeepSeek") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = shareNotes,
                                role = Role.Checkbox,
                                onValueChange = { shareNotes = it },
                            )
                            .testTag("plan_share_training_notes"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = shareNotes, onCheckedChange = null)
                    Text("Share filled Training Notes for this request")
                }
                Text("DeepSeek receives the text below only if you check the box and continue.")
                notes.forEach { note ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(note.field.title, style = MaterialTheme.typography.titleSmall)
                        if (note.text.isEmpty()) {
                            Text("No text to share", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Text(note.text, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (note.wasTruncated) {
                            Text(
                                "Shortened to fit the ${note.field.sendLimit}-character send limit.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Text(
                    "This choice resets for each request. Training history and Records stay on this device."
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(shareNotes) }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
