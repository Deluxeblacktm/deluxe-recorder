package com.deluxe.recorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Crimson = Color(0xFFFF2E54)
private val StatusGreen = Color(0xFF00E676)

private val DarkColors = darkColorScheme(
    primary = Crimson,
    onPrimary = Color.White,
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF0D0D0E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF13151D),
    onSurfaceVariant = Color(0xFFA0A0A5),
    secondary = Color(0xFFA0A0A5),
    onSecondary = Color(0xFF000000),
    outlineVariant = Color(0xFF1E1E22),
    error = Color(0xFFEF4444)
)

@Composable
fun DeluxeTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
