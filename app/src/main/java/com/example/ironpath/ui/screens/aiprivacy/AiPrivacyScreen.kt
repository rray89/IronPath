package com.example.ironpath.ui.screens.aiprivacy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun AiPrivacyScreen(
    modifier: Modifier = Modifier,
    viewModel: AiPrivacyViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AiPrivacyContent(uiState = uiState, modifier = modifier)
}

@Composable
internal fun AiPrivacyContent(
    uiState: AiPrivacyUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "AI & PRIVACY",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PrivacySection(
            title = "Your data",
            body = "Room saves durable workout data locally on this device.",
        )
        PrivacySection(
            title = "Plans",
            body =
                "Accepted structured plans persist locally. Unaccepted AI drafts do not persist.",
        )
        PrivacySection(
            title = "On-device AI",
            body = uiState.availability,
        )
        PrivacySection(
            title = "Release provider order",
            body =
                "On-device planning is tried first. Rule-based planning remains available offline.",
        )
        PrivacySection(
            title = "Planning history",
            body = "Release planning history stays local on this device.",
        )
        PrivacySection(
            title = "Debug Remote AI",
            body =
                "Debug Remote AI can send summarized planning context only when explicitly enabled.",
        )
        PrivacySection(
            title = "Backup and transfer",
            body =
                "IronPath cloud backup and restore are not available in this version, and " +
                    "Android cloud backup is turned off. On Android 12 and higher, Android " +
                    "device-to-device transfer can copy your workout database when you set up a " +
                    "new phone.",
        )
    }
}

@Composable
private fun PrivacySection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
