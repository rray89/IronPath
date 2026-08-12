package com.example.ironpath.ui.screens.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ironpath.ui.components.GreenGradientButton
import com.example.ironpath.ui.testing.TestTags
import com.example.ironpath.ui.theme.AmbientGlow
import com.example.ironpath.ui.theme.SurfaceContainerHigh

@Composable
fun EntryScreen(
    onGetStarted: () -> Unit,
    modifier: Modifier = Modifier,
    continuing: Boolean = false,
) {
    Box(
        modifier =
            modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).drawBehind {
                // Subtle ambient glow behind the logo area
                drawCircle(
                    color = AmbientGlow,
                    radius = size.width * 0.6f,
                    center = center.copy(y = size.height * 0.28f),
                )
            },
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // Logo icon in bracketed container
            Box(
                modifier =
                    Modifier.size(100.dp)
                        .background(
                            color = SurfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                        )
                        .border(
                            width = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(12.dp),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(24.dp))

            // App name
            Text(
                text = "IRONPATH",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(8.dp))

            // Tagline
            Text(
                text = "OWN YOUR PROGRESS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text =
                    "Your training data is already saved on this device. " +
                        "IronPath cloud backup is not available in this version. " +
                        "Android device-to-device transfer may copy it to a new phone during setup.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(48.dp))

            GreenGradientButton(
                text = if (continuing) "Continuing…" else "Continue on this device",
                onClick = onGetStarted,
                enabled = !continuing,
                modifier = Modifier.testTag(TestTags.ENTRY_GET_STARTED),
            )

            Spacer(Modifier.height(32.dp))

            // Version
            Text(
                text = "v1.0.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}
