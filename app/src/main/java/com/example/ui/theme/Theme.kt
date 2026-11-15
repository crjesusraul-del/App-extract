package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = NxCrimson,
    onPrimary = Color.White,
    primaryContainer = NxCrimsonContainerDark,
    onPrimaryContainer = Color(0xFFFFDADE),
    secondary = NxCyan,
    onSecondary = Color(0xFF002026),
    secondaryContainer = NxCyanContainerDark,
    onSecondaryContainer = Color(0xFFB4F4FF),
    tertiary = NxAmber,
    onTertiary = Color(0xFF2B1900),
    tertiaryContainer = NxAmberContainerDark,
    onTertiaryContainer = Color(0xFFFFE5B8),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceHigh,
    outline = DarkOutline,
    outlineVariant = DarkOutline.copy(alpha = 0.5f),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0006)
)

private val LightColorScheme = lightColorScheme(
    primary = NxCrimsonDark,
    onPrimary = Color.White,
    primaryContainer = NxCrimsonContainerLight,
    onPrimaryContainer = Color(0xFF41000A),
    secondary = NxCyanDark,
    onSecondary = Color.White,
    secondaryContainer = NxCyanContainerLight,
    onSecondaryContainer = Color(0xFF001F25),
    tertiary = NxAmber,
    onTertiary = Color(0xFF2B1900),
    tertiaryContainer = NxAmberContainerLight,
    onTertiaryContainer = Color(0xFF332000),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.6f),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

val NxDumpShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NxDumpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NxDumpTypography,
        shapes = NxDumpShapes,
        content = content
    )
}
