package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val ClearPathDarkScheme = darkColorScheme(
    primary = WarmAmber,
    onPrimary = OnWarmAmber,
    primaryContainer = WarmAmberContainer,
    onPrimaryContainer = OnWarmAmberContainer,
    secondary = RestrainedCyan,
    onSecondary = OnRestrainedCyan,
    secondaryContainer = RestrainedCyanContainer,
    onSecondaryContainer = OnRestrainedCyanContainer,
    background = ObsidianBackground,
    onBackground = HighContrastText,
    surface = ObsidianSurface,
    onSurface = HighContrastText,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = HighContrastSecondaryText,
    outline = ObsidianBorder,
    error = AlertCoral,
    onError = OnWarmAmber,
    errorContainer = AlertCoralContainer,
    onErrorContainer = HighContrastText
)

private val ClearPathShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun ClearPathTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ClearPathDarkScheme,
        typography = ClearPathTypography,
        shapes = ClearPathShapes,
        content = content
    )
}
