package com.multikit.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4FF),
    onPrimaryContainer = Color(0xFF001A43),
    secondary = Color(0xFF00696E),
    secondaryContainer = Color(0xFF6FF6FE),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFDFCFF),
    surfaceVariant = Color(0xFFE1E2EC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFADC6FF),
    onPrimary = Color(0xFF002E6C),
    primaryContainer = Color(0xFF004397),
    onPrimaryContainer = Color(0xFFD9E4FF),
    secondary = Color(0xFF4CD9E2),
    secondaryContainer = Color(0xFF004F53),
    background = Color(0xFF101418),
    surface = Color(0xFF171B21),
    surfaceVariant = Color(0xFF44474F),
)

@Composable
fun MultiKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = MaterialTheme.typography,
        content = content,
    )
}