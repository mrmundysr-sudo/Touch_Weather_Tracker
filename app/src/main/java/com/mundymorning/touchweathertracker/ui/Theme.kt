package com.mundymorning.touchweathertracker.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** Severity palette. Colors are never the only signal: every use also carries an icon and a text label. */
data class TwColors(
    val warning: Color, val onWarning: Color, val watch: Color, val onWatch: Color,
    val advisory: Color, val onAdvisory: Color, val statement: Color, val onStatement: Color,
    val observed: Color, val forecast: Color, val model: Color, val sky: Color, val skyDeep: Color,
)

private val LightTw = TwColors(
    warning = Color(0xFFB3261E), onWarning = Color.White, watch = Color(0xFFA85600), onWatch = Color.White,
    advisory = Color(0xFF7A5C00), onAdvisory = Color.White, statement = Color(0xFF3B5B7A), onStatement = Color.White,
    observed = Color(0xFF00695C), forecast = Color(0xFF0B5FA5), model = Color(0xFF6A3FA0), sky = Color(0xFFD7EAFB), skyDeep = Color(0xFF0B2A4A),
)
private val DarkTw = TwColors(
    warning = Color(0xFFFFB4AB), onWarning = Color(0xFF410002), watch = Color(0xFFFFB77C), onWatch = Color(0xFF331900),
    advisory = Color(0xFFE9C46A), onAdvisory = Color(0xFF2A1F00), statement = Color(0xFFA9C7E8), onStatement = Color(0xFF0A2540),
    observed = Color(0xFF6FD6C4), forecast = Color(0xFF8AC4FF), model = Color(0xFFD0B8FF), sky = Color(0xFF16314D), skyDeep = Color(0xFF07111D),
)

val LocalTw = staticCompositionLocalOf { LightTw }
object Tw { val colors: TwColors @Composable get() = LocalTw.current }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B5FA5), onPrimary = Color.White, primaryContainer = Color(0xFFD3E6FA), onPrimaryContainer = Color(0xFF05233F),
    secondary = Color(0xFF00695C), onSecondary = Color.White, secondaryContainer = Color(0xFFCDEFE8), onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF8A5A00), tertiaryContainer = Color(0xFFFFDEA8), onTertiaryContainer = Color(0xFF2B1800),
    background = Color(0xFFF4F8FC), onBackground = Color(0xFF0B2239), surface = Color(0xFFFFFFFF), onSurface = Color(0xFF0B2239),
    surfaceVariant = Color(0xFFE3EDF7), onSurfaceVariant = Color(0xFF3F566C), outline = Color(0xFF6D8499), outlineVariant = Color(0xFFC5D4E3),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002), surfaceContainer = Color(0xFFEAF2FA),
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8AC4FF), onPrimary = Color(0xFF00315A), primaryContainer = Color(0xFF0F3E69), onPrimaryContainer = Color(0xFFD3E6FA),
    secondary = Color(0xFF6FD6C4), onSecondary = Color(0xFF00382F), secondaryContainer = Color(0xFF004F44), onSecondaryContainer = Color(0xFFCDEFE8),
    tertiary = Color(0xFFFFB950), tertiaryContainer = Color(0xFF5E3C00), onTertiaryContainer = Color(0xFFFFDEA8),
    background = Color(0xFF0A1522), onBackground = Color(0xFFE6EEF7), surface = Color(0xFF10202F), onSurface = Color(0xFFE6EEF7),
    surfaceVariant = Color(0xFF1A3047), onSurfaceVariant = Color(0xFFB3C6D9), outline = Color(0xFF7F93A8), outlineVariant = Color(0xFF2D4660),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6), surfaceContainer = Color(0xFF14283B),
)

private val Sans = FontFamily.SansSerif
private val TwTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 72.sp, lineHeight = 76.sp, letterSpacing = (-1.5).sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.4.sp),
)

val TwShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
)

@Composable
fun TwTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTw provides if (dark) DarkTw else LightTw) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = TwTypography, shapes = TwShapes, content = content)
    }
}
