package io.github.pixelblue.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF3D8BFF)
val AccentPink = Color(0xFFFF3DAE)

private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1B2E52),
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = AccentPink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A1A38),
    onSecondaryContainer = Color(0xFFFFD8EC),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF14171E),
    onSurface = Color(0xFFE6E8EE),
    surfaceVariant = Color(0xFF1E222B),
    onSurfaceVariant = Color(0xFFA9AFBC),
    surfaceContainer = Color(0xFF181B22),
    surfaceContainerHigh = Color(0xFF1E222B),
    outline = Color(0xFF3A404C),
)

@Composable
fun PixelBlueTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
