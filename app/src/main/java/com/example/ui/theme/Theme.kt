package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ElectricTeal,
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF004F4A),
    onPrimaryContainer = Color(0xFF8DF2EB),
    background = OledBlack, // Pitch black backdrop
    onBackground = Color(0xFFE1E3E2),
    surface = DarkSurface, // Dark charcoal surface
    onSurface = Color(0xFFE1E3E2),
    surfaceVariant = DarkSurfaceVariant, // Raised charcoal separator
    onSurfaceVariant = Color(0xFFC0C9C7),
    outline = Color(0xFF8A9391),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LightColorScheme = lightColorScheme(
    primary = DarkElectricTeal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF8DF2EB),
    onPrimaryContainer = Color(0xFF00201D),
    background = PureWhite,
    onBackground = Color(0xFF191C1C),
    surface = LightSurface,
    onSurface = Color(0xFF191C1C),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF3F4948),
    outline = Color(0xFF6F7978),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color is intentionally bypassed/disabled here to maintain high density pure black styling
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
