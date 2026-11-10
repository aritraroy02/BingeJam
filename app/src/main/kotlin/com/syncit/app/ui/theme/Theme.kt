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
 * Palette (unslop pass, 2026-11-10; direction: late-night, warm, a little mischievous, a dark room
 * lit by one screen). Warm neutral surfaces, one red accent from the startup animation, and the
 * colour left to the content: posters, artwork and faces. [surface] is a card, [raised] a control
 * or input on it, [hairline] every border. Glass ([glassTint], blurred) only on layers that float
 * over content: the tab bar, sheets, banners and the room over its movie artwork.
 */
@Immutable
data class GlassPalette(
    val isDark: Boolean,
    val base: Color,
    val surface: Color,
    val raised: Color,
    val hairline: Color,
    val glassTint: Color,
    val glassFallback: Color,
    val strokeTop: Color,
    val strokeBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Fill for the primary action, my chat bubbles and selections ([onAccent] text, >= 4.5:1). */
    val accent: Color,
    /** Same as [accent]; kept so older call sites keep compiling. No gradients. */
    val accent2: Color,
    /** The accent for text, links and icons (>= 4.5:1 on [raised]). */
    val accentText: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    /** Laid over a blurred movie artwork backdrop so text stays readable. */
    val artworkScrim: Color,
) {
    /** The two friends from the startup animation: together they mark "live" and "in sync". */
    val friendA: Color get() = Color(0xFFFF2E4D)
    val friendB: Color get() = Color(0xFFFF7A45)
}

private val DarkText = Color(0xFFEFE9DE)
private val LightText = Color(0xFF1F1B16)

val DarkGlass = GlassPalette(
    isDark = true,
    base = Color(0xFF121110),
    surface = Color(0xFF1A1917),
    raised = Color(0xFF24221F),
    hairline = DarkText.copy(alpha = 0.08f),
    glassTint = Color(0xFF1A1917).copy(alpha = 0.72f),
    glassFallback = Color(0xF21E1C1A),
    strokeTop = DarkText.copy(alpha = 0.10f),
    strokeBottom = DarkText.copy(alpha = 0.06f),
    textPrimary = DarkText,
    textSecondary = Color(0xFFAAA398),
    // >= 4.5:1 on raised (#24221F).
    textTertiary = Color(0xFF948E84),
    accent = Color(0xFFC8102E), // white on it 5.9:1
    accent2 = Color(0xFFC8102E),
    accentText = Color(0xFFFF5468), // 5.1:1 on raised, 6.0:1 on base
    onAccent = Color.White,
    success = Color(0xFF4CC38A),
    warning = Color(0xFFE5A93B),
    danger = Color(0xFFF87171),
    artworkScrim = Color.Black.copy(alpha = 0.50f),
)

val LightGlass = GlassPalette(
    isDark = false,
    base = Color(0xFFF7F4EE),
    surface = Color(0xFFFDFBF7),
    raised = Color(0xFFEFE9DF),
    hairline = LightText.copy(alpha = 0.08f),
    glassTint = Color(0xFFFDFBF7).copy(alpha = 0.80f),
    glassFallback = Color(0xF5FDFBF7),
    strokeTop = LightText.copy(alpha = 0.10f),
    strokeBottom = LightText.copy(alpha = 0.06f),
    textPrimary = LightText,
    textSecondary = Color(0xFF5E554A),
    // >= 4.5:1 on raised (#EFE9DF).
    textTertiary = Color(0xFF6B6257),
    accent = Color(0xFFC8102E),
    accent2 = Color(0xFFC8102E),
    accentText = Color(0xFFB80F2B), // 5.5:1 on raised
    onAccent = Color.White,
    success = Color(0xFF15803D),
    warning = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    artworkScrim = Color.White.copy(alpha = 0.45f),
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
// 12 sp secondary text, ~20 sp screen titles. Weights 400 to 600; hierarchy from size and colour.
private fun glassTypography(inter: FontFamily) = with(inter) {
    Typography(
        displaySmall = style(30, FontWeight.Medium, 36, -0.3f),
        headlineLarge = style(26, FontWeight.Medium, 32, -0.2f),
        headlineMedium = style(22, FontWeight.Medium, 28),
        headlineSmall = style(20, FontWeight.Medium, 28),
        titleLarge = style(18, FontWeight.Medium, 24),
        titleMedium = style(16, FontWeight.Medium, 24),
        titleSmall = style(14, FontWeight.Medium, 20),
        bodyLarge = style(15, FontWeight.Normal, 22),
        bodyMedium = style(14, FontWeight.Normal, 20),
        bodySmall = style(12, FontWeight.Normal, 16),
        labelLarge = style(14, FontWeight.Medium, 20),
        labelMedium = style(12, FontWeight.Medium, 16),
        labelSmall = style(11, FontWeight.Medium, 16, 0.1f),
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
            surface = p.surface, onSurface = p.textPrimary,
            surfaceVariant = p.raised, onSurfaceVariant = p.textSecondary,
            surfaceContainerHigh = p.raised, error = p.danger,
        )
    } else {
        lightColorScheme(
            primary = p.accentText, onPrimary = p.onAccent, secondary = p.accent2,
            background = p.base, onBackground = p.textPrimary,
            surface = p.surface, onSurface = p.textPrimary,
            surfaceVariant = p.raised, onSurfaceVariant = p.textSecondary,
            surfaceContainerHigh = p.raised, error = p.danger,
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
