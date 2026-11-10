package com.syncit.app.ui.glass

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.syncit.app.ui.common.rememberInstalledAppIcon
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.PlatformColors
import com.syncit.core.model.StreamingService
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.absoluteValue

/** The backdrop every glass panel on the current screen blurs. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** True on screens whose backdrop is a blurred movie artwork: panels there stay glass. */
val LocalArtworkBackdrop = staticCompositionLocalOf { false }

val PillShape = RoundedCornerShape(percent = 50)
val PanelShape = RoundedCornerShape(16.dp)

/** Buttons, inputs and the segmented control. */
val ControlShape = RoundedCornerShape(12.dp)

/** Small buttons and chips inside rows. */
val ChipShape = RoundedCornerShape(8.dp)

/**
 * Screen root: the plain page colour, or the movie's blurred [artwork], captured by Haze so the
 * floating layers drawn in [content] (sheets, banners, the tab bar) can blur it.
 */
@Composable
fun GlassScreen(
    modifier: Modifier = Modifier,
    artwork: ImageBitmap? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val haze = rememberHazeState()
    Box(modifier.fillMaxSize().background(Glass.palette.base)) {
        GlassBackdrop(artwork, Modifier.fillMaxSize().hazeSource(haze))
        CompositionLocalProvider(LocalHazeState provides haze, LocalArtworkBackdrop provides (artwork != null)) { content() }
    }
}

@Composable
private fun GlassBackdrop(artwork: ImageBitmap?, modifier: Modifier) {
    val p = Glass.palette
    Box(modifier.background(p.base)) {
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().scale(1.15f).blur(60.dp),
            )
            // Modifier.blur is a no-op before Android 12, so cover the sharp image more there.
            val scrim = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) p.artworkScrim else p.base.copy(alpha = 0.82f)
            Box(Modifier.fillMaxSize().background(scrim))
        }
    }
}

/**
 * True when the user turned animations off (Settings → Accessibility → Remove animations, or
 * animator duration scale 0 in developer options). Endless motion should stop then.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * A surface clipped to [shape] with a hairline edge. Solid ([tint], else the card colour) by
 * default; frosted glass (blurred backdrop + tint) only when [floating] or over a movie artwork.
 * Falls back to a translucent fill where blur isn't available.
 */
// Composable on purpose: it reads the palette and the screen's HazeState from composition.
@Suppress("ComposableModifierFactory", "ModifierFactoryExtensionFunction")
@Composable
fun Modifier.glass(shape: Shape = PanelShape, tint: Color? = null, blur: Dp = 28.dp, floating: Boolean = false): Modifier {
    val p = Glass.palette
    val haze = LocalHazeState.current
    val base = this.clip(shape)
    if (!floating && !LocalArtworkBackdrop.current) {
        return base.background(tint ?: p.surface).border(1.dp, p.hairline, shape)
    }
    val fill = tint ?: p.glassTint
    val surface = if (haze != null) {
        base.hazeEffect(
            state = haze,
            style = HazeStyle(
                backgroundColor = p.base,
                tints = listOf(HazeTint(fill)),
                blurRadius = blur,
                noiseFactor = 0.05f,
                fallbackTint = HazeTint(p.glassFallback),
            ),
        )
    } else {
        base.background(p.glassFallback)
    }
    return surface.border(1.dp, p.hairline, shape)
}

/** A control on a card: the raised step with a hairline (secondary buttons, inputs, chips). */
@Suppress("ComposableModifierFactory", "ModifierFactoryExtensionFunction")
@Composable
fun Modifier.raised(shape: Shape = ControlShape): Modifier {
    val p = Glass.palette
    return if (LocalArtworkBackdrop.current) glass(shape) else clip(shape).background(p.raised).border(1.dp, p.hairline, shape)
}

/** A glass card. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = PanelShape,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .glass(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

enum class GlassButtonStyle { Primary, Secondary, Danger }

/** Button: Primary is the solid accent, Secondary the raised surface, Danger red text on it. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GlassButtonStyle = GlassButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val p = Glass.palette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val textColor = when (style) {
        GlassButtonStyle.Primary -> p.onAccent
        GlassButtonStyle.Secondary -> p.textPrimary
        GlassButtonStyle.Danger -> p.danger
    }
    val surface = when (style) {
        GlassButtonStyle.Primary -> Modifier.clip(ControlShape).background(p.accent)
        else -> Modifier.raised(ControlShape)
    }
    // Pressed: a quick light wash on top of the shrink, so the tap reads even on busy backdrops.
    val pressWash by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 60 else 180), label = "wash")
    val washColor = if (style == GlassButtonStyle.Primary) Color.Black.copy(alpha = 0.14f) else p.textPrimary.copy(alpha = 0.08f)
    Row(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 44.dp)
            .then(surface)
            .drawWithContent {
                drawContent()
                if (pressWash > 0f) drawRect(washColor, alpha = pressWash)
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            // While loading the label is replaced by a spinner; keep it for screen readers.
            .semantics(mergeDescendants = true) {
                if (loading) {
                    contentDescription = text
                    stateDescription = "Loading"
                }
            }
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = textColor, strokeWidth = 2.dp)
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = textColor)
        }
    }
}

/**
 * Round button for top-bar actions: a raised circle, or with [plain] a bare 24 dp icon like
 * Instagram's top bar (the 40 dp touch area stays).
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    plain: Boolean = false,
    enabled: Boolean = true,
) {
    val surface = if (plain) Modifier.clip(CircleShape) else Modifier.raised(CircleShape)
    Box(
        modifier = modifier
            .size(40.dp)
            .then(surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Glass.palette.textPrimary,
            modifier = Modifier.size(if (plain) 24.dp else 20.dp),
        )
    }
}

/** Top bar: back or picture, title, and plain actions. */
@Composable
fun GlassTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        navigation?.invoke()
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = Glass.palette.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        actions()
    }
}

/** Single-line text field on the raised surface, with a placeholder. */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    shape: Shape = ControlShape,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = Glass.palette
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = textStyle.copy(color = p.textPrimary),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .raised(shape)
                    .padding(start = 16.dp, end = if (trailing != null) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, tint = p.textTertiary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = textStyle, color = p.textTertiary, maxLines = 1)
                    inner()
                }
                trailing?.invoke()
            }
        },
    )
}

/** Segmented control: a raised track with the selected segment inverted (light on dark). */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = Glass.palette
    BoxWithConstraints(modifier.fillMaxWidth().height(40.dp).raised(ControlShape).padding(4.dp)) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selected, label = "segment")
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .clip(ChipShape)
                .background(p.textPrimary),
        )
        // Tabs for TalkBack: "Chat, tab, selected, 1 of 3".
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(ChipShape)
                        .selectable(selected = i == selected, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == selected) p.base else p.textSecondary,
                    )
                }
            }
        }
    }
}

/** NETFLIX / JioHotstar / Prime Video / Spotify / YouTube … chip in the app's brand colour. */
@Composable
fun PlatformBadge(service: StreamingService, modifier: Modifier = Modifier, onArtwork: Boolean = false) {
    // The app's real icon + its name when it's installed; the coloured name chip otherwise.
    val icon = rememberInstalledAppIcon(service.packageName)
    if (icon != null) {
        Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            Image(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                service.displayName,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (onArtwork) Color.White else Glass.palette.textPrimary,
                maxLines = 1,
            )
        }
        return
    }
    PlatformNameChip(service, modifier)
}

/** The coloured name chip ("NETFLIX", "Spotify"…) used when the app isn't installed. */
@Composable
fun PlatformNameChip(service: StreamingService, modifier: Modifier = Modifier) {
    fun solid(c: Color) = Brush.horizontalGradient(listOf(c, c))
    val bg = when (service) {
        StreamingService.NETFLIX -> solid(PlatformColors.Netflix)
        StreamingService.JIOHOTSTAR -> Brush.horizontalGradient(listOf(PlatformColors.JioHotstarStart, PlatformColors.JioHotstarEnd))
        StreamingService.PRIME_VIDEO -> solid(PlatformColors.PrimeVideo)
        StreamingService.SPOTIFY -> solid(PlatformColors.Spotify)
        StreamingService.YOUTUBE_MUSIC, StreamingService.YOUTUBE -> solid(PlatformColors.YouTube)
        StreamingService.AMAZON_MUSIC -> solid(PlatformColors.AmazonMusic)
    }
    Text(
        text = when (service) {
            StreamingService.NETFLIX -> "NETFLIX"
            StreamingService.JIOHOTSTAR -> "JioHotstar"
            StreamingService.PRIME_VIDEO -> "prime video"
            StreamingService.SPOTIFY -> "Spotify"
            StreamingService.YOUTUBE_MUSIC -> "YouTube Music"
            StreamingService.AMAZON_MUSIC -> "amazon music"
            StreamingService.YOUTUBE -> "YouTube"
        },
        // Dark text on the light brand colours (Spotify green, Amazon Music teal) stays readable.
        color = when (service) {
            StreamingService.SPOTIFY, StreamingService.AMAZON_MUSIC -> PlatformColors.OnLightBrand
            else -> Color.White
        },
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = if (service == StreamingService.NETFLIX) 1.2.sp else 0.2.sp,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Small status tag with a coloured dot, e.g. "In sync". */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier, inSync: Boolean = false) {
    Row(
        modifier = modifier.raised(ChipShape).padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (inSync) SyncDots() else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Glass.palette.textPrimary, maxLines = 1)
    }
}

/** Deep, warm tones for letter avatars (no picture yet), chosen from the name. */
private val AvatarTones = listOf(
    Color(0xFF7A2E2A),
    Color(0xFF7A5A24),
    Color(0xFF4D5A2E),
    Color(0xFF2F4F55),
    Color(0xFF5B3A4A),
)

/**
 * Profile picture ([photoUrl], still or animated GIF) over the initial on a toned circle chosen
 * from the name; the initial shows while the picture loads or if there is none. Optional online dot.
 */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, online: Boolean? = null, photoUrl: String? = null) {
    val tone = AvatarTones[name.hashCode().absoluteValue % AvatarTones.size]
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(tone),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color(0xFFF3E9DD),
                fontWeight = FontWeight.Medium,
                fontSize = (size.value * 0.42f).sp,
                textAlign = TextAlign.Center,
            )
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (online != null) {
            val p = Glass.palette
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(size * 0.3f)
                    .clip(CircleShape)
                    .background(p.base)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (online) p.success else p.textTertiary),
            )
        }
    }
}

/** Small label above a group, in sentence case. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Glass.palette.textTertiary,
        modifier = modifier.padding(start = 4.dp),
    )
}

/** Row inside a settings-style panel: icon, title, optional subtitle, trailing content. */
@Composable
fun GlassRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = Glass.palette
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        trailing?.invoke()
    }
}
