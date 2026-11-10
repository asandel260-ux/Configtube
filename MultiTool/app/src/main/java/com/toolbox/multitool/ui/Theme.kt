package com.toolbox.multitool.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// Teal brand palette, used on Android 10/11 and when dynamic colour is unavailable.
private val LightColors = lightColorScheme(
    primary = Color(0xFF00696B), onPrimary = Color.White,
    primaryContainer = Color(0xFF6FF6F8), onPrimaryContainer = Color(0xFF002021),
    secondary = Color(0xFF4A6364), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E9), onSecondaryContainer = Color(0xFF051F20),
    tertiary = Color(0xFF4B607C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E4FF), onTertiaryContainer = Color(0xFF041C35),
    background = Color(0xFFF4FAFA), surface = Color(0xFFF4FAFA),
    surfaceContainerLow = Color(0xFFEEF5F5), surfaceContainer = Color(0xFFE8F0F0), surfaceContainerHigh = Color(0xFFE2EAEA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4CDADC), onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51), onPrimaryContainer = Color(0xFF6FF6F8),
    secondary = Color(0xFFB1CCCD), onSecondary = Color(0xFF1C3435),
    secondaryContainer = Color(0xFF334B4C), onSecondaryContainer = Color(0xFFCCE8E9),
    tertiary = Color(0xFFB3C8E8), onTertiary = Color(0xFF1C314B),
    tertiaryContainer = Color(0xFF334863), onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF0E1415), surface = Color(0xFF0E1415),
    surfaceContainerLow = Color(0xFF161D1D), surfaceContainer = Color(0xFF1A2121), surfaceContainerHigh = Color(0xFF252B2B),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun MultiToolTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, content = content)
}
