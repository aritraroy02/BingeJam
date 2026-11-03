package com.syncit.app.ui.theme

import android.content.res.AssetManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    /** Gradient start for filled buttons, my chat bubbles, selected pills ([onAccent] text). */
    val accent: Color,
    /** Gradient end; with [accent] it must keep [onAccent] text ≥ 4.5:1. */
    val accent2: Color,
    /** The accent for text, links and icons drawn on glass (≥ 4.5:1 against it). */
    val accentText: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Laid over a blurred movie artwork backdrop so text stays readable. */
    val artworkScrim: Color,
)

// "Sunset": warm orange into magenta over a dusk-purple glow. Friendly and social.
val DarkGlass = GlassPalette(
    isDark = true,
    base = Color(0xFF0A0606),
    blobs = listOf(Color(0xFFEA580C), Color(0xFFBE185D), Color(0xFF6D28D9)),
    glassTint = Color.White.copy(alpha = 0.09f),
    glassFallback = Color(0xE01E1A1A),
    strokeTop = Color.White.copy(alpha = 0.30f),
    strokeBottom = Color.White.copy(alpha = 0.05f),
    textPrimary = Color(0xFFF5F7FA),
    textSecondary = Color.White.copy(alpha = 0.70f),
    // ≥ 4.5:1 on the darkest glass (0.45 was ~4.4:1).
    textTertiary = Color.White.copy(alpha = 0.56f),
    accent = Color(0xFFC2410C), // burnt orange, white on it 5.2:1
    accent2 = Color(0xFFBE185D), // magenta, white on it 6.0:1
    accentText = Color(0xFFFB923C), // 7.6:1 on dark glass
    onAccent = Color.White,
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    // A clear red, apart from the orange highlights.
    danger = Color(0xFFF87171),
    artworkScrim = Color.Black.copy(alpha = 0.45f),
)

val LightGlass = GlassPalette(
    isDark = false,
    base = Color(0xFFFFF7F2),
    blobs = listOf(Color(0xFFFED7AA), Color(0xFFFBCFE8), Color(0xFFDDD6FE)),
    glassTint = Color.White.copy(alpha = 0.55f),
    glassFallback = Color(0xF0FFFFFF),
    strokeTop = Color.White.copy(alpha = 0.95f),
    strokeBottom = Color.White.copy(alpha = 0.30f),
    textPrimary = Color(0xFF0B0D12),
    textSecondary = Color(0xFF0B0D12).copy(alpha = 0.68f),
    // ≥ 4.5:1 on the light base (0.42 was ~2.9:1, unreadable for hints and labels).
    textTertiary = Color(0xFF0B0D12).copy(alpha = 0.60f),
    accent = Color(0xFFC2410C),
    accent2 = Color(0xFFBE185D),
    accentText = Color(0xFFC2410C), // 4.9:1 on the light base
    onAccent = Color.White,
    success = Color(0xFF059669),
    warning = Color(0xFFD97706),
    danger = Color(0xFFB91C1C),
    artworkScrim = Color.White.copy(alpha = 0.40f),
)

val LocalGlassPalette = staticCompositionLocalOf { DarkGlass }

/** Shorthand: `Glass.palette.accent`. */
object Glass {
    val palette: GlassPalette
        @Composable get() = LocalGlassPalette.current
}

/**
 * Inter (variable font, assets/fonts/inter.ttf), one instance per weight. Loaded from assets because
 * Compose sets a res/font font's weight through Paint, which Android 15+ ignores for apps targeting
 * it (every heading drew at regular weight); asset fonts are built with the weight baked in.
 */
private fun interFamily(assets: AssetManager) = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
        .map { Font(INTER_PATH, assets, it) },
)

private const val INTER_PATH = "fonts/inter.ttf"

/** The app font, for text that sets its own family. */
val Inter: FontFamily
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.bodyMedium.fontFamily ?: FontFamily.Default

private fun FontFamily.style(size: Int, weight: FontWeight, line: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = this,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

// Sized like Instagram's Android app (user's choice, 2026-10-29): 14 sp body text and list titles,
// 12 sp secondary text, ~20 sp screen titles.
private fun glassTypography(inter: FontFamily) = with(inter) {
    Typography(
        displaySmall = style(30, FontWeight.ExtraBold, 36, -0.8f),
        headlineLarge = style(26, FontWeight.Bold, 32, -0.6f),
        headlineMedium = style(22, FontWeight.Bold, 28, -0.4f),
        headlineSmall = style(20, FontWeight.Bold, 26, -0.3f),
        titleLarge = style(18, FontWeight.SemiBold, 24, -0.2f),
        titleMedium = style(16, FontWeight.SemiBold, 21),
        titleSmall = style(14, FontWeight.SemiBold, 19),
        bodyLarge = style(15, FontWeight.Normal, 21),
        bodyMedium = style(14, FontWeight.Normal, 19),
        bodySmall = style(12, FontWeight.Normal, 16),
        labelLarge = style(14, FontWeight.SemiBold, 18),
        labelMedium = style(12, FontWeight.Medium, 16),
        labelSmall = style(11, FontWeight.SemiBold, 14, 0.6f),
    )
}

@Composable
fun SyncItTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkGlass else LightGlass
    // Material components (dialogs, text selection, ripples) get colours from the same palette.
    val scheme = if (dark) {
        // primary is what dialog buttons are written in, so it's the readable text red.
        darkColorScheme(
            primary = p.accentText, onPrimary = p.onAccent, secondary = p.accent2,
            background = p.base, onBackground = p.textPrimary,
            surface = Color(0xFF1A1414), onSurface = p.textPrimary,
            surfaceVariant = Color(0xFF261E1E), onSurfaceVariant = p.textSecondary,
            surfaceContainerHigh = Color(0xFF211A1A), error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.accentText, onPrimary = p.onAccent, secondary = p.accent2,
            background = p.base, onBackground = p.textPrimary,
            surface = Color(0xFFFFFBF8), onSurface = p.textPrimary,
            surfaceVariant = Color(0xFFF6E7DE), onSurfaceVariant = p.textSecondary,
            surfaceContainerHigh = Color(0xFFFCF2EC), error = p.danger,
        )
    }
    val assets = LocalContext.current.applicationContext.assets
    val typography = remember(assets) { glassTypography(interFamily(assets)) }
    CompositionLocalProvider(LocalGlassPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
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
