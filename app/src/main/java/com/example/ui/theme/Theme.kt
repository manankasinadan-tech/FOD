package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val FodDarkColorScheme = darkColorScheme(
    primary = CyanTelemetry,
    onPrimary = ObsidianBg,
    primaryContainer = CyanTelemetryContainer,
    onPrimaryContainer = CyanTelemetryMuted,
    secondary = EmeraldVerified,
    onSecondary = ObsidianBg,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = EmeraldVerified,
    tertiary = AmberWarning,
    onTertiary = ObsidianBg,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberWarning,
    error = CrimsonBreak,
    onError = ObsidianBg,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonBreak,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = ObsidianBg,
    surfaceContainerLow = SlateSurface,
    surfaceContainer = SlateSurfaceElevated,
    surfaceContainerHigh = SlateSurfaceHigh,
    outline = SlateOutline,
    outlineVariant = SlateOutlineSubtle
)

val FodShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp)
)

@Composable
fun FodDetectorTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FodDarkColorScheme,
        typography = FodTypography,
        shapes = FodShapes,
        content = content
    )
}
