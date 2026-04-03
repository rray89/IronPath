package com.example.ironpath.ui.screens.devtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevToolsScreen(
  onBack: () -> Unit,
  onClearComplete: () -> Unit,
  viewModel: DevToolsViewModel = koinViewModel(),
) {
  val status by viewModel.status.collectAsStateWithLifecycle()
  val showClearConfirm by viewModel.showClearConfirm.collectAsStateWithLifecycle()

  if (showClearConfirm) {
    AlertDialog(
      onDismissRequest = viewModel::dismissClearConfirmation,
      title = { Text("Clear all data?") },
      text = {
        Text(
          "This will permanently delete all local IronPath data — plans, sessions, history, and records."
        )
      },
      confirmButton = {
        TextButton(onClick = { viewModel.confirmClearAllData(onClearComplete) }) {
          Text("Clear", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = {
        TextButton(onClick = viewModel::dismissClearConfirmation) { Text("Cancel") }
      },
    )
  }

  Column(
    modifier = Modifier.fillMaxSize().systemBarsPadding(),
  ) {
    TopAppBar(
      title = {
        Text(
          text = "DEV TOOLS",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      },
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      },
      colors =
        TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
    )

    Column(
      modifier =
        Modifier.weight(1f)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "Internal testing helpers",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Spacer(Modifier.height(8.dp))

      DevToolsButton("Seed 3-Day Plan (Today)", onClick = viewModel::seedPlanForToday)
      DevToolsButton("Seed 3-Day Plan (Tomorrow)", onClick = viewModel::seedPlanForTomorrow)
      DevToolsButton("Seed History Logs", onClick = viewModel::seedHistoryLogs)
      DevToolsButton("Seed Records", onClick = viewModel::seedRecords)

      Spacer(Modifier.height(8.dp))

      DevToolsButton(
        label = "Clear All Data",
        onClick = viewModel::requestClearConfirmation,
        destructive = true,
      )

      // Status message sits below all buttons — always visible, scrolls with content
      if (status != null) {
        Spacer(Modifier.height(8.dp))
        Text(
          text = status!!,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun DevToolsButton(
  label: String,
  onClick: () -> Unit,
  destructive: Boolean = false,
) {
  Card(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
      ),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color =
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
    )
  }
}
