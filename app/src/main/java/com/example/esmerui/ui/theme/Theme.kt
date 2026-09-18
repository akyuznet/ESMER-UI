package com.example.esmerui.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// A launcher wants one consistent look on every head unit, so no dynamic colour
// and no light variant - the car display is dark by design.
private val LauncherColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentDim,
    onPrimaryContainer = OnSurface,
    secondary = Accent,
    onSecondary = OnAccent,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = OnSurfaceMuted,
    outline = Outline,
)

@Composable
fun ESMERUITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LauncherColorScheme,
        typography = Typography,
        content = content
    )
}
