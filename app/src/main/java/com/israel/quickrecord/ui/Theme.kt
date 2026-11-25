package com.israel.quickrecord.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The red of a recording light. Kept fixed so "recording" always reads the same. */
val RecordRed = Color(0xFFE5383B)
val OkGreen = Color(0xFF34C759)
val WarnAmber = Color(0xFFFFB020)

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFB7A6FF),
    onPrimary = Color(0xFF2A0F8F),
    primaryContainer = Color(0xFF4A2FD0),
    onPrimaryContainer = Color(0xFFE7DEFF),
    secondaryContainer = Color(0xFF332D4F),
    surface = Color(0xFF111015),
    background = Color(0xFF111015),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF5B3AF0),
    primaryContainer = Color(0xFFE7DEFF),
    onPrimaryContainer = Color(0xFF1A0063),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Light),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** Big timer digits: equal-width numbers so the timer doesn't jiggle. */
val TimerStyle = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Light, fontFeatureSettings = "tnum", letterSpacing = 1.sp)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
)

/**
 * Follows the phone: light or dark, and your wallpaper's colours (Android's
 * "Material You"), like the rest of the system.
 */
@Composable
fun QuickRecordTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = try {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } catch (_: Exception) {
        if (dark) FallbackDark else FallbackLight
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
