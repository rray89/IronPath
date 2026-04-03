package com.example.ironpath.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val IronPathColorScheme =
  darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Primary, // reuse green for secondary in MVP
    onSecondary = OnPrimary,
    surface = Surface,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceBright = SurfaceBright,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = Error,
    onError = OnError,
  )

@Composable
fun IronPathTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = IronPathColorScheme,
    typography = Typography,
    content = content,
  )
}
