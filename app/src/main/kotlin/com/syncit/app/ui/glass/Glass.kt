package com.syncit.app.ui.glass

import android.os.Build
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

val PillShape = RoundedCornerShape(percent = 50)
val PanelShape = RoundedCornerShape(28.dp)

/**
 * Screen root: an aurora (or the movie's blurred [artwork]) as the backdrop, captured by Haze so
 * every [glass] panel drawn in [content] blurs it.
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
        CompositionLocalProvider(LocalHazeState provides haze) { content() }
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
        } else {
            Aurora()
        }
    }
}

/** Three soft colour blobs drifting slowly. Radial gradients, so no blur is needed. */
@Composable
private fun Aurora() {
    val p = Glass.palette
    val t by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "aurora-t",
    )
    val alpha = if (p.isDark) 0.55f else 0.75f
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val spots = listOf(
            Offset(w * (0.15f + 0.20f * t), h * (0.12f + 0.06f * t)) to w * 0.85f,
            Offset(w * (0.95f - 0.25f * t), h * (0.42f + 0.10f * t)) to w * 0.80f,
            Offset(w * (0.30f + 0.15f * t), h * (0.88f - 0.08f * t)) to w * 0.90f,
        )
        spots.forEachIndexed { i, (center, radius) ->
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(p.blobs[i].copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
    }
}

/**
 * Frosted glass: blurred backdrop + tint, clipped to [shape], with a light-catching edge.
 * Falls back to a translucent fill where blur isn't available.
 */
// Composable on purpose: it reads the palette and the screen's HazeState from composition.
@Suppress("ComposableModifierFactory", "ModifierFactoryExtensionFunction")
@Composable
fun Modifier.glass(shape: Shape = PanelShape, tint: Color? = null, blur: Dp = 28.dp): Modifier {
    val p = Glass.palette
    val haze = LocalHazeState.current
    val fill = tint ?: p.glassTint
    val base = this.clip(shape)
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
    return surface.border(1.dp, Brush.linearGradient(listOf(p.strokeTop, p.strokeBottom)), shape)
}

/** A glass card. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = PanelShape,
    contentPadding: PaddingValues = PaddingValues(20.dp),
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

/** Pill button. Primary is the accent gradient with a glossy top; Secondary is glass. */
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
        GlassButtonStyle.Primary -> Modifier
            .clip(PillShape)
            .background(Brush.horizontalGradient(listOf(p.accent, p.accent2)))
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)))
            .border(1.dp, Color.White.copy(alpha = 0.25f), PillShape)
        else -> Modifier.glass(PillShape)
    }
    Row(
        modifier = modifier
            .scale(scale)
            .heightIn(min = 54.dp)
            .then(surface)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled && !loading, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = textColor, strokeWidth = 2.dp)
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = textColor)
        }
    }
}

/** Round glass button for top-bar actions. */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(46.dp).glass(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Glass.palette.textPrimary, modifier = Modifier.size(22.dp))
    }
}

/** Floating top bar: controls float over the backdrop like iOS liquid glass. */
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
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

/** Single-line glass text field with a placeholder. */
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
    shape: Shape = PillShape,
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
                    .heightIn(min = 54.dp)
                    .glass(shape)
                    .padding(start = 20.dp, end = if (trailing != null) 6.dp else 20.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, tint = p.textTertiary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
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

/** Glass segmented control with a sliding accent pill. */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val p = Glass.palette
    BoxWithConstraints(modifier.fillMaxWidth().height(48.dp).glass(PillShape).padding(4.dp)) {
        val segment = maxWidth / options.size
        val offset by animateDpAsState(segment * selected, label = "segment")
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .clip(PillShape)
                .background(Brush.horizontalGradient(listOf(p.accent, p.accent2)))
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent))),
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(PillShape)
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == selected) p.onAccent else p.textSecondary,
                    )
                }
            }
        }
    }
}

/** NETFLIX / JioHotstar / Prime Video / Spotify / YouTube … chip in the app's brand colour. */
@Composable
fun PlatformBadge(service: StreamingService, modifier: Modifier = Modifier) {
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
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = if (service == StreamingService.NETFLIX) 1.2.sp else 0.2.sp,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Small glass pill with a coloured dot, e.g. "● In sync". */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.glass(PillShape).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Glass.palette.textPrimary, maxLines = 1)
    }
}

private val AvatarGradients = listOf(
    Color(0xFF7C6CFF) to Color(0xFF3FB7FF),
    Color(0xFFFF6B9A) to Color(0xFFFFA45C),
    Color(0xFF22C55E) to Color(0xFF14B8A6),
    Color(0xFFF59E0B) to Color(0xFFEF4444),
    Color(0xFF8B5CF6) to Color(0xFFEC4899),
)

/** Initial on a gradient circle chosen from the name, with an optional online dot. */
@Composable
fun Avatar(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp, online: Boolean? = null) {
    val (a, b) = AvatarGradients[name.hashCode().absoluteValue % AvatarGradients.size]
    Box(modifier.size(size)) {
        Box(
            Modifier.fillMaxSize().clip(CircleShape).background(Brush.linearGradient(listOf(a, b))),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.trim().take(1).uppercase().ifEmpty { "?" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
                textAlign = TextAlign.Center,
            )
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

/** Small uppercase label above a group. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Glass.palette.textTertiary,
        modifier = modifier.padding(start = 6.dp),
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
            .clip(RoundedCornerShape(18.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(p.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = p.accent, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        trailing?.invoke()
    }
}
