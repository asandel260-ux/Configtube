package com.studyflow.app

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// Type scale: strong contrast between sizes, tight tracking on large text,
// open tracking on micro labels (use with .uppercase()).
private val SwissType = Typography(
    headlineMedium = TextStyle(fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.0).sp),
    headlineSmall = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.9.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
)

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
        val bg = if (s.amoled) Color.Black else Color(0xFF0A0A0A)
        val fg = Color(0xFFF2F2F2)
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = Color(0xFF1F1F1F),
            onPrimaryContainer = fg,
            secondary = Color(0xFFBDBDBD),
            tertiary = accent,
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = Color(0xFF1A1A1A),
            onSurfaceVariant = Color(0xFFA3A3A3),
            outline = Color(0xFF3A3A3A),
            outlineVariant = Color(0xFF262626),
        ).copy(
            surfaceContainerLow = lerp(bg, Color.White, 0.03f),
            surfaceContainer = lerp(bg, Color.White, 0.05f),
            surfaceContainerHigh = lerp(bg, Color.White, 0.08f),
            surfaceContainerHighest = lerp(bg, Color.White, 0.11f),
        )
    } else {
        val bg = Color.White
        val fg = Color(0xFF0A0A0A)
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = Color(0xFFEDEDED),
            onPrimaryContainer = fg,
            secondary = Color(0xFF404040),
            tertiary = accent,
            background = bg,
            onBackground = fg,
            surface = bg,
            onSurface = fg,
            surfaceVariant = Color(0xFFF0F0F0),
            onSurfaceVariant = Color(0xFF5C5C5C),
            outline = Color(0xFFCFCFCF),
            outlineVariant = Color(0xFFE6E6E6),
        ).copy(
            surfaceContainerLow = Color(0xFFF8F8F8),
            surfaceContainer = Color(0xFFF4F4F4),
            surfaceContainerHigh = Color(0xFFF0F0F0),
            surfaceContainerHighest = Color(0xFFEBEBEB),
        )
    }
    if (dark && s.amoled && s.dynamic) scheme = scheme.copy(background = Color.Black, surface = Color.Black)

    val r = s.corner
    val shapes = Shapes(
        extraSmall = RoundedCornerShape((r * 0.5f).dp),
        small = RoundedCornerShape(r.dp),
        medium = RoundedCornerShape(r.dp),
        large = RoundedCornerShape((r * 1.5f).dp),
        extraLarge = RoundedCornerShape((r * 2f).dp),
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val w = (view.context as Activity).window
            w.statusBarColor = scheme.background.toArgb()
            w.navigationBarColor = scheme.background.toArgb()
            val c = WindowCompat.getInsetsController(w, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * s.fontScale)) {
        MaterialTheme(colorScheme = scheme, shapes = shapes, typography = SwissType, content = content)
    }
}
