package com.studyflow.app

import android.app.Activity
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

@Composable
fun StudyTheme(s: Settings, content: @Composable () -> Unit) {
    val dark = when (s.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    val accent = Color(s.accent)
    val onAccent = if (accent.luminance() > 0.5f) Color.Black else Color.White

    var scheme = if (s.dynamic && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else if (dark) {
        val bg = if (s.amoled) Color.Black else Color(0xFF121214)
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lerp(bg, accent, 0.30f),
            onPrimaryContainer = Color.White,
            secondary = lerp(accent, Color.White, 0.35f),
            tertiary = lerp(accent, Color(0xFF43A047), 0.5f),
            background = bg,
            onBackground = Color(0xFFE6E6EA),
            surface = bg,
            onSurface = Color(0xFFE6E6EA),
            surfaceVariant = lerp(bg, Color.White, 0.12f),
            onSurfaceVariant = Color(0xFFB9B9C2),
            outline = Color(0xFF6E6E78),
        ).copy(
            surfaceContainerLow = lerp(bg, Color.White, 0.04f),
            surfaceContainer = lerp(bg, Color.White, 0.07f),
            surfaceContainerHigh = lerp(bg, Color.White, 0.10f),
            surfaceContainerHighest = lerp(bg, Color.White, 0.13f),
        )
    } else {
        val bg = Color(0xFFFAF9FC)
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = lerp(Color.White, accent, 0.22f),
            onPrimaryContainer = Color.Black,
            secondary = lerp(accent, Color.Black, 0.2f),
            tertiary = lerp(accent, Color(0xFF2E7D32), 0.5f),
            background = bg,
            onBackground = Color(0xFF1B1B1F),
            surface = bg,
            onSurface = Color(0xFF1B1B1F),
            surfaceVariant = lerp(Color.White, accent, 0.10f),
            onSurfaceVariant = Color(0xFF45454D),
            outline = Color(0xFF8A8A94),
        ).copy(
            surfaceContainerLow = lerp(Color.White, accent, 0.03f),
            surfaceContainer = lerp(Color.White, accent, 0.06f),
            surfaceContainerHigh = lerp(Color.White, accent, 0.09f),
            surfaceContainerHighest = lerp(Color.White, accent, 0.12f),
        )
    }
    if (dark && s.amoled && s.dynamic) scheme = scheme.copy(background = Color.Black, surface = Color.Black)

    val r = s.corner
    val shapes = Shapes(
        extraSmall = RoundedCornerShape((r * 0.3f).dp),
        small = RoundedCornerShape((r * 0.6f).dp),
        medium = RoundedCornerShape(r.dp),
        large = RoundedCornerShape((r * 1.2f).dp),
        extraLarge = RoundedCornerShape((r * 1.5f).dp),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            w.statusBarColor = scheme.background.toArgb()
            w.navigationBarColor = scheme.surfaceContainer.toArgb()
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * s.fontScale)) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, content = content)
    }
}
