package com.example.ironpath.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ironpath.domain.validation.RecordDraftResult
import com.example.ironpath.domain.validation.RecordDraftValidator
import com.example.ironpath.domain.validation.RecordField
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.LocalDate

@Composable
internal fun AddRecordScreen(
    suggestions: List<String>,
    today: LocalDate,
    onSave: (ValidatedRecordDraft) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    externalError: String? = null,
    onExternalErrorConsumed: () -> Unit = {},
) {
    val validator = remember { RecordDraftValidator() }

    var exerciseName by rememberSaveable { mutableStateOf("") }
    var weightText by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(today.toString()) }
    var note by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var fieldErrors by remember { mutableStateOf<Map<RecordField, String>>(emptyMap()) }
    val filteredSuggestions =
        remember(exerciseName, suggestions) {
            val query = exerciseName.trim()
            if (query.isEmpty()) {
                emptyList()
            } else {
                suggestions
                    .asSequence()
                    .filter { it.contains(query, ignoreCase = true) }
                    .filterNot { it.equals(query, ignoreCase = true) }
                    .distinct()
                    .toList()
            }
        }

    // Keep an asynchronously surfaced duplicate error visible after the ViewModel consumes it.
    LaunchedEffect(externalError) {
        if (externalError != null) {
            errorMessage = externalError
            onExternalErrorConsumed()
        }
    }
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "ADD RECORD",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(24.dp))

        // Exercise Name
        Text(
            text = "EXERCISE NAME",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = exerciseName,
            onValueChange = {
                exerciseName = it
                fieldErrors = fieldErrors - RecordField.ExerciseName
            },
            modifier =
                Modifier.fillMaxWidth()
                    .testTag(TestTags.RECORD_NAME)
                    .recordFieldSemantics(
                        label = "Exercise name",
                        errorMessage = fieldErrors[RecordField.ExerciseName],
                    ),
            placeholder = { Text("e.g. Deadlift") },
            singleLine = true,
            isError = RecordField.ExerciseName in fieldErrors,
            supportingText =
                fieldErrors[RecordField.ExerciseName]?.let { message -> { Text(message) } },
            colors = fieldColors,
        )
        filteredSuggestions.forEach { suggestion ->
            Text(
                text = suggestion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) {
                            exerciseName = suggestion
                            fieldErrors = fieldErrors - RecordField.ExerciseName
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        // Value + Unit
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VALUE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = weightText,
                    onValueChange = {
                        weightText = it
                        fieldErrors = fieldErrors - RecordField.Weight
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .testTag(TestTags.RECORD_WEIGHT)
                            .recordFieldSemantics(
                                label = "Value",
                                errorMessage = fieldErrors[RecordField.Weight],
                            ),
                    placeholder = { Text("0.0") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = RecordField.Weight in fieldErrors,
                    supportingText =
                        fieldErrors[RecordField.Weight]?.let { message -> { Text(message) } },
                    colors = fieldColors,
                )
            }

            Spacer(Modifier.padding(horizontal = 8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "UNIT / TYPE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = "kg",
                    onValueChange = {},
                    modifier =
                        Modifier.fillMaxWidth()
                            .recordFieldSemantics(label = "Unit / type", errorMessage = null),
                    readOnly = true,
                    singleLine = true,
                    colors = fieldColors,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // Date
        Text(
            text = "DATE",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = dateText,
            onValueChange = {
                dateText = it
                fieldErrors = fieldErrors - RecordField.Date
            },
            modifier =
                Modifier.fillMaxWidth()
                    .testTag(TestTags.RECORD_DATE)
                    .recordFieldSemantics(
                        label = "Date",
                        errorMessage = fieldErrors[RecordField.Date],
                    ),
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            isError = RecordField.Date in fieldErrors,
            supportingText = fieldErrors[RecordField.Date]?.let { message -> { Text(message) } },
            colors = fieldColors,
        )

        Spacer(Modifier.height(20.dp))

        // Note (optional)
        Text(
            text = "NOTE (OPTIONAL)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier =
                Modifier.fillMaxWidth()
                    .height(100.dp)
                    .testTag(TestTags.RECORD_NOTE)
                    .recordFieldSemantics(label = "Note (optional)", errorMessage = null),
            placeholder = { Text("How did it feel?") },
            colors = fieldColors,
        )

        if (errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        Spacer(Modifier.height(24.dp))

        // Save button
        GreenGradientButton(
            text = "Save",
            onClick = {
                when (
                    val result =
                        validator.validate(
                            exerciseName = exerciseName,
                            weightText = weightText,
                            dateText = dateText,
                            note = note,
                            today = today,
                        )
                ) {
                    is RecordDraftResult.Invalid -> {
                        fieldErrors = result.errors
                        errorMessage = null
                    }
                    is RecordDraftResult.Valid -> {
                        fieldErrors = emptyMap()
                        errorMessage = null
                        onSave(result.draft)
                    }
                }
            },
        )

        Spacer(Modifier.height(12.dp))

        // Cancel
        Text(
            text = "CANCEL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onCancel)
                    .padding(vertical = 12.dp),
        )

        Spacer(Modifier.height(32.dp))
    }
}

private fun Modifier.recordFieldSemantics(
    label: String,
    errorMessage: String?,
): Modifier = semantics {
    contentDescription = label
    if (errorMessage != null) {
        error(errorMessage)
    }
}

// -- Previews --

@Preview(showBackground = true)
@Composable
private fun PreviewAddRecord() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AddRecordScreen(
                suggestions = listOf("Bench Press", "Squat", "Deadlift"),
                today = LocalDate.parse("2026-07-16"),
                onSave = {},
                onCancel = {},
            )
        }
    }
}
