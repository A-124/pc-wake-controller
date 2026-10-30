package com.pcwake.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.pcwake.app.core.ThemeMode

val AccentGreen = Color(0xFF35D0A0)
val AccentBlue = Color(0xFF5E9BFF)
val AccentAmber = Color(0xFFFFB454)
val DangerRed = Color(0xFFFF6B7A)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4FF),
    onPrimary = Color(0xFF002E66),
    primaryContainer = Color(0xFF12355F),
    onPrimaryContainer = Color(0xFFD9E7FF),
    secondary = AccentGreen,
    onSecondary = Color(0xFF00281C),
    secondaryContainer = Color(0xFF0E3B2D),
    onSecondaryContainer = Color(0xFFA5F2D8),
    tertiary = AccentAmber,
    onTertiary = Color(0xFF3D2600),
    tertiaryContainer = Color(0xFF3E2C0B),
    onTertiaryContainer = Color(0xFFFFE2B0),
    error = DangerRed,
    onError = Color(0xFF3B0010),
    errorContainer = Color(0xFF4C1B25),
    onErrorContainer = Color(0xFFFFD9DE),
    background = Color(0xFF0A0F1D),
    onBackground = Color(0xFFE8EDF9),
    surface = Color(0xFF111829),
    onSurface = Color(0xFFE8EDF9),
    surfaceVariant = Color(0xFF1A2337),
    onSurfaceVariant = Color(0xFFA6B1CB),
    outline = Color(0xFF3A4560),
    outlineVariant = Color(0xFF262F45),
    inverseSurface = Color(0xFFE8EDF9),
    inverseOnSurface = Color(0xFF162033),
    surfaceContainer = Color(0xFF151D30),
    surfaceContainerHigh = Color(0xFF1A2337),
    surfaceContainerHighest = Color(0xFF202A41),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E5FA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E6FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF00795E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA8F1DB),
    onSecondaryContainer = Color(0xFF002017),
    tertiary = Color(0xFF8A5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDFA3),
    onTertiaryContainer = Color(0xFF2B1B00),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF131A26),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF131A26),
    surfaceVariant = Color(0xFFE7ECF7),
    onSurfaceVariant = Color(0xFF455064),
    outline = Color(0xFF9AA5B9),
    outlineVariant = Color(0xFFD5DCE9),
    surfaceContainer = Color(0xFFF0F3FA),
    surfaceContainerHigh = Color(0xFFE9EEF8),
    surfaceContainerHighest = Color(0xFFE2E8F4),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 25.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

@Composable
fun PcWakeTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
