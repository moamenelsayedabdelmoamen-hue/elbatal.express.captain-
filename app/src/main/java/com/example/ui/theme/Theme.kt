package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ExpressRed,
    onPrimary = Color.White,
    primaryContainer = ExpressRedDark,
    onPrimaryContainer = Color.White,
    secondary = HeroYellow,
    onSecondary = Color.Black,
    secondaryContainer = HeroYellowDark,
    onSecondaryContainer = Color.White,
    tertiary = StatusOnlineGreen,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = TextPrimaryDark,
    surface = DarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondaryDark,
    outline = DarkCardBorder
)

@Composable
fun ElBatalTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}

