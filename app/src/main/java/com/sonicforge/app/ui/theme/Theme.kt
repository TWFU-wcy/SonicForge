package com.sonicforge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6BE6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE7FF),
    onPrimaryContainer = Color(0xFF001B3F),
    secondary = Color(0xFF6D5AE6),
    onSecondary = Color.White,
    tertiary = Color(0xFF00A6A6),
    onTertiary = Color.White,
    background = Color(0xFFF2F4FB),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB4FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFDDE7FF),
    secondary = Color(0xFFC3B8FF),
    onSecondary = Color(0xFF2A1E5C),
    tertiary = Color(0xFF5FD6D6),
    onTertiary = Color(0xFF00363A),
    background = Color(0xFF080A10),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
)

@Composable
fun SonicForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
