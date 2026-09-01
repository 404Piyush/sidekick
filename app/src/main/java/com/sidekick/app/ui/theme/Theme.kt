package com.sidekick.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD6D6D6),
    onPrimary = Color(0xFF1A1A1A),
    secondary = Color(0xFF9A9A9A),
    onSecondary = Color(0xFF1A1A1A),
    background = Color(0xFF121212),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF333333),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFECECEC),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF252525),
    onPrimary = Color(0xFFF5F3EF),
    secondary = Color(0xFF545454),
    onSecondary = Color(0xFFF5F3EF),
    background = Color(0xFFF5F3EF),
    onBackground = Color(0xFF252525),
    surface = Color(0xFFF5F3EF),
    onSurface = Color(0xFF252525),
    surfaceVariant = Color(0xFFEAE7E1),
    onSurfaceVariant = Color(0xFF545454),
)

@Composable
fun SidekickTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = SidekickTypography,
        content = content,
    )
}
