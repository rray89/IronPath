package com.example.ironpath.ui.screens.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.domain.validation.RecordDraftResult
import com.example.ironpath.domain.validation.RecordDraftValidator
import com.example.ironpath.domain.validation.RecordField
import com.example.ironpath.domain.validation.ValidatedRecordDraft
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.LocalDate

@Composable
internal fun AddRecordScreen(
    suggestions: List<String>,
    today: LocalDate,
    onSave: (ValidatedRecordDraft) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    existingRecord: PersonalRecord? = null,
    onDelete: (() -> Unit)? = null,
    externalError: String? = null,
    onExternalErrorConsumed: () -> Unit = {},
) {
    val isEditMode = existingRecord != null
    val validator = remember { RecordDraftValidator() }

    var exerciseName by remember { mutableStateOf(existingRecord?.exerciseName ?: "") }
    var weightText by remember {
        mutableStateOf(
            existingRecord?.weightKg?.let {
                if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
            } ?: "",
        )
    }
    var dateText by remember { mutableStateOf(existingRecord?.achievedOn ?: today.toString()) }
    var note by remember { mutableStateOf(existingRecord?.note ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var fieldErrors by remember { mutableStateOf<Map<RecordField, String>>(emptyMap()) }

    // Show errors surfaced asynchronously from the ViewModel (e.g. duplicate-record constraint)
    LaunchedEffect(externalError) {
        if (externalError != null) {
            errorMessage = externalError
            onExternalErrorConsumed()
        }
    }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    // Delete confirmation dialog
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = {
                Text(
                    text = "Delete Record",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    text = "Delete this record? This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDelete?.invoke()
                    },
                ) {
                    Text(
                        text = "DELETE",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("CANCEL") }
            },
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = if (isEditMode) "EDIT RECORD" else "ADD RECORD",
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
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. Deadlift") },
            singleLine = true,
            isError = RecordField.ExerciseName in fieldErrors,
            supportingText =
                fieldErrors[RecordField.ExerciseName]?.let { message -> { Text(message) } },
            colors = fieldColors,
        )

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
                    modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth(),
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
            modifier = Modifier.fillMaxWidth().height(100.dp),
            placeholder = { Text("How did it feel?") },
            colors = fieldColors,
        )

        if (errorMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = errorMessage!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.weight(1f))

        // Save / Update button
        GreenGradientButton(
            text = if (isEditMode) "Update Record" else "Save",
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

        // Delete button (edit mode only)
        if (isEditMode && onDelete != null) {
            Text(
                text = "DELETE RECORD",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier =
                    Modifier.fillMaxWidth()
                        .clickable { showDeleteConfirmation = true }
                        .padding(vertical = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
        }

        // Cancel
        Text(
            text = "CANCEL",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.fillMaxWidth().clickable(onClick = onCancel).padding(vertical = 12.dp),
        )

        Spacer(Modifier.height(32.dp))
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

@Preview(showBackground = true)
@Composable
private fun PreviewEditRecord() {
    IronPathTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AddRecordScreen(
                suggestions = listOf("Bench Press", "Squat", "Deadlift"),
                today = LocalDate.parse("2026-07-16"),
                onSave = {},
                onCancel = {},
                existingRecord =
                    PersonalRecord(
                        id = "preview-record",
                        exerciseName = "Bench Press",
                        normalizedExerciseName = "bench press",
                        weightKg = 100.0,
                        achievedOn = "2026-03-23",
                        note = "Felt strong",
                        createdAt = 1L,
                    ),
                onDelete = {},
            )
        }
    }
}
