package dev.smartdisplay.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SmartDisplayColors = darkColorScheme(
    primary = Pink500,
    onPrimary = Ink,
    primaryContainer = PinkContainer,
    onPrimaryContainer = Pink300,
    secondary = Blue500,
    onSecondary = Ink,
    secondaryContainer = BlueContainer,
    onSecondaryContainer = Blue300,
    tertiary = Lavender500,
    onTertiary = Ink,
    tertiaryContainer = LavenderContainer,
    onTertiaryContainer = Lavender300,
    error = Danger,
    onError = Ink,
    errorContainer = DangerContainer,
    onErrorContainer = Danger,
    background = Bg0,
    onBackground = TextPrimary,
    surface = Bg0,
    onSurface = TextPrimary,
    surfaceVariant = Bg2,
    onSurfaceVariant = TextSecondary,
    surfaceDim = Bg0,
    surfaceBright = Bg3,
    surfaceContainerLowest = Bg0,
    surfaceContainerLow = Bg1,
    surfaceContainer = Bg2,
    surfaceContainerHigh = Bg3,
    surfaceContainerHighest = Bg4,
    inverseSurface = TextPrimary,
    inverseOnSurface = Ink,
    inversePrimary = PinkContainer,
    outline = BorderStrong,
    outlineVariant = BorderSubtle,
    scrim = Ink,
)

/** Always dark: the display is on day and night, so there is no light theme. */
@Composable
fun SmartDisplayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmartDisplayColors,
        typography = SmartDisplayTypography,
        content = content,
    )
}
