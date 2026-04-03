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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ironpath.data.local.entity.PersonalRecord
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.theme.IronPathTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
internal fun AddRecordScreen(
  suggestions: List<String>,
  onSave: (PersonalRecord) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var exerciseName by remember { mutableStateOf("") }
  var weightText by remember { mutableStateOf("") }
  var dateText by remember {
    mutableStateOf(LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
  }
  var note by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }

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
      modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
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
      onValueChange = { exerciseName = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("e.g. Deadlift") },
      singleLine = true,
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
          onValueChange = { weightText = it },
          modifier = Modifier.fillMaxWidth(),
          placeholder = { Text("0.0") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
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
      onValueChange = { dateText = it },
      modifier = Modifier.fillMaxWidth(),
      placeholder = { Text("YYYY-MM-DD") },
      singleLine = true,
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

    // Save button
    GreenGradientButton(
      text = "Save",
      onClick = {
        val name = exerciseName.trim()
        val weight = weightText.toDoubleOrNull()
        val date =
          try {
            LocalDate.parse(dateText)
          } catch (_: Exception) {
            null
          }

        when {
          name.isEmpty() -> errorMessage = "Exercise name is required"
          weight == null || weight <= 0 -> errorMessage = "Weight must be a positive number"
          date == null -> errorMessage = "Invalid date format (use YYYY-MM-DD)"
          date.isAfter(LocalDate.now()) -> errorMessage = "Date cannot be in the future"
          else -> {
            errorMessage = null
            onSave(
              PersonalRecord(
                exerciseName = name,
                normalizedExerciseName = name.lowercase().trim(),
                weightKg = weight,
                achievedOn = dateText,
                note = note.ifBlank { null },
              ),
            )
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
      modifier = Modifier.fillMaxWidth().clickable(onClick = onCancel).padding(vertical = 12.dp),
    )

    Spacer(Modifier.height(32.dp))
  }
}

// -- Preview --

@Preview(showBackground = true)
@Composable
private fun PreviewAddRecord() {
  IronPathTheme {
    Surface(color = MaterialTheme.colorScheme.surface) {
      AddRecordScreen(
        suggestions = listOf("Bench Press", "Squat", "Deadlift"),
        onSave = {},
        onCancel = {},
      )
    }
  }
}
