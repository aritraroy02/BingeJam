package com.syncit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.syncit.app.R

/** User's choice in Settings. SYSTEM follows the phone. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Liquid-glass palette. Panels are blurred backdrop + [glassTint]; [glassFallback] is used where
 * blur isn't available (Android 11 and older, dialogs in their own window).
 */
@Immutable
data class GlassPalette(
    val isDark: Boolean,
    val base: Color,
    val blobs: List<Color>,
    val glassTint: Color,
    val glassFallback: Color,
    val strokeTop: Color,
    val strokeBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accent2: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Laid over a blurred movie artwork backdrop so text stays readable. */
    val artworkScrim: Color,
)

val DarkGlass = GlassPalette(
    isDark = true,
    base = Color(0xFF06070B),
    blobs = listOf(Color(0xFF6D5DFC), Color(0xFF1FB6FF), Color(0xFFFF4D8D)),
    glassTint = Color.White.copy(alpha = 0.09f),
    glassFallback = Color(0xE01A1C24),
    strokeTop = Color.White.copy(alpha = 0.30f),
    strokeBottom = Color.White.copy(alpha = 0.05f),
    textPrimary = Color(0xFFF5F7FA),
    textSecondary = Color.White.copy(alpha = 0.68f),
    textTertiary = Color.White.copy(alpha = 0.45f),
    accent = Color(0xFF7C6CFF),
    accent2 = Color(0xFF3FB7FF),
    onAccent = Color.White,
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFF87171),
    artworkScrim = Color.Black.copy(alpha = 0.45f),
)

val LightGlass = GlassPalette(
    isDark = false,
    base = Color(0xFFEEF1F8),
    blobs = listOf(Color(0xFFB9AEFF), Color(0xFF9BE3FF), Color(0xFFFFC2DA)),
    glassTint = Color.White.copy(alpha = 0.55f),
    glassFallback = Color(0xF0FFFFFF),
    strokeTop = Color.White.copy(alpha = 0.95f),
    strokeBottom = Color.White.copy(alpha = 0.30f),
    textPrimary = Color(0xFF0B0D12),
    textSecondary = Color(0xFF0B0D12).copy(alpha = 0.62f),
    textTertiary = Color(0xFF0B0D12).copy(alpha = 0.42f),
    accent = Color(0xFF5B4BFF),
    accent2 = Color(0xFF1E9BFF),
    onAccent = Color.White,
    success = Color(0xFF059669),
    warning = Color(0xFFD97706),
    danger = Color(0xFFDC2626),
    artworkScrim = Color.White.copy(alpha = 0.40f),
)

val LocalGlassPalette = staticCompositionLocalOf { DarkGlass }

/** Shorthand: `Glass.palette.accent`. */
object Glass {
    val palette: GlassPalette
        @Composable get() = LocalGlassPalette.current
}

/** Inter variable font; each weight sets the font's wght axis (the default variation settings). */
val Inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal),
    Font(R.font.inter, FontWeight.Medium),
    Font(R.font.inter, FontWeight.SemiBold),
    Font(R.font.inter, FontWeight.Bold),
    Font(R.font.inter, FontWeight.ExtraBold),
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private val GlassTypography = Typography(
    displaySmall = style(36, FontWeight.ExtraBold, 42, -1f),
    headlineLarge = style(32, FontWeight.Bold, 38, -0.8f),
    headlineMedium = style(26, FontWeight.Bold, 32, -0.5f),
    headlineSmall = style(22, FontWeight.Bold, 28, -0.3f),
    titleLarge = style(20, FontWeight.SemiBold, 26, -0.2f),
    titleMedium = style(17, FontWeight.SemiBold, 22),
    titleSmall = style(15, FontWeight.SemiBold, 20),
    bodyLarge = style(16, FontWeight.Normal, 23),
    bodyMedium = style(15, FontWeight.Normal, 21),
    bodySmall = style(13, FontWeight.Normal, 18),
    labelLarge = style(15, FontWeight.SemiBold, 20),
    labelMedium = style(13, FontWeight.Medium, 16),
    labelSmall = style(11, FontWeight.SemiBold, 14, 0.8f),
)

@Composable
fun SyncItTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkGlass else LightGlass
    // Material components (dialogs, text selection, ripples) get colours from the same palette.
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2,
            background = p.base, onBackground = p.textPrimary,
            surface = Color(0xFF15171F), onSurface = p.textPrimary,
            surfaceVariant = Color(0xFF1F222C), onSurfaceVariant = p.textSecondary,
            surfaceContainerHigh = Color(0xFF1C1F28), error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.accent, onPrimary = p.onAccent, secondary = p.accent2,
            background = p.base, onBackground = p.textPrimary,
            surface = Color(0xFFF9FAFD), onSurface = p.textPrimary,
            surfaceVariant = Color(0xFFE6E9F2), onSurfaceVariant = p.textSecondary,
            surfaceContainerHigh = Color(0xFFF4F5FA), error = p.danger,
        )
    }
    CompositionLocalProvider(LocalGlassPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = GlassTypography, content = content)
    }
}

/** Platform brand colours for badges and accents. */
object PlatformColors {
    val Netflix = Color(0xFFE50914)
    val JioHotstarStart = Color(0xFF0F3BD8)
    val JioHotstarEnd = Color(0xFF1FA2FF)
    val PrimeVideo = Color(0xFF00A8E1)
    val Spotify = Color(0xFF1DB954)
    val YouTube = Color(0xFFFF0033)
    val AmazonMusic = Color(0xFF25D1DA)

    /** Text on the light brand chips (Spotify, Amazon Music). */
    val OnLightBrand = Color(0xFF0A0A0A)
}
