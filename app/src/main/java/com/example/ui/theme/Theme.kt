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
    primary = NxCoral,
    onPrimary = Color.White,
    primaryContainer = NxCoralContainerDark,
    onPrimaryContainer = Color(0xFFFFD9D9),
    secondary = NxSteelBlue,
    onSecondary = Color(0xFF062338),
    secondaryContainer = NxSteelBlueContainerDark,
    onSecondaryContainer = Color(0xFFD2EBFF),
    tertiary = NxAmber,
    onTertiary = Color(0xFF2B1900),
    tertiaryContainer = Color(0xFF3D2800),
    onTertiaryContainer = Color(0xFFFFE4B5),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkBackground,
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF2E333D),
    outline = DarkOutline,
    outlineVariant = DarkOutline.copy(alpha = 0.45f),
    error = Color(0xFFF87171),
    onError = Color(0xFF3A0606),
    errorContainer = Color(0xFF451215),
    onErrorContainer = Color(0xFFFFDADA)
)

private val LightColorScheme = lightColorScheme(
    primary = NxCoralLight,
    onPrimary = Color.White,
    primaryContainer = NxCoralContainerLight,
    onPrimaryContainer = Color(0xFF410004),
    secondary = NxSteelBlueLight,
    onSecondary = Color.White,
    secondaryContainer = NxSteelBlueContainerLight,
    onSecondaryContainer = Color(0xFF001E36),
    tertiary = NxAmberLight,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEDD5),
    onTertiaryContainer = Color(0xFF331B00),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurface,
    surfaceContainerLow = LightBackground,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceVariant,
    surfaceContainerHighest = LightSurfaceHigh,
    outline = LightOutline,
    outlineVariant = LightOutline.copy(alpha = 0.6f),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF450A0A)
)

val NxDumpShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
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
