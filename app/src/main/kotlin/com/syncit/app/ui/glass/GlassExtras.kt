package com.syncit.app.ui.glass

import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animate
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.MutableIntState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.Inter
import kotlinx.coroutines.delay

/**
 * Count of bottom sheets open on screen, provided by the main navigation so it can hide the
 * floating tab bar (which would otherwise cover the sheet's buttons). Null outside it.
 */
val LocalOpenSheets = staticCompositionLocalOf<MutableIntState?> { null }

/**
 * Bottom sheet drawn in the screen's own window (not a dialog), so the glass panel still blurs
 * the screen's backdrop. Back and a tap on the scrim dismiss it. While it's open the tab bar hides.
 */
@Composable
fun GlassBottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    val openSheets = LocalOpenSheets.current
    if (visible && openSheets != null) {
        DisposableEffect(openSheets) {
            openSheets.intValue++
            onDispose { openSheets.intValue-- }
        }
    }
    // Swipe down to close: the sheet follows the finger (and overscroll of its content); past
    // DISMISS_DRAG or a fast flick it closes, otherwise it springs back.
    var dragY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) { if (visible) dragY = 0f }
    val scope = rememberCoroutineScope()
    val dismissPx = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
    val dismiss by rememberUpdatedState(onDismiss)
    val settle: (Float) -> Unit = { velocity ->
        if (dragY > dismissPx || velocity > DISMISS_VELOCITY) {
            dismiss()
        } else {
            scope.launch { animate(dragY, 0f) { value, _ -> dragY = value } }
        }
    }
    val sheetScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // Pulled down already and now dragging up: bring the sheet back before scrolling.
                if (available.y < 0 && dragY > 0) {
                    val used = maxOf(available.y, -dragY)
                    dragY += used
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // The content is at its top (or can't scroll): the rest of a downward drag moves the sheet.
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    dragY += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dragY <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = (1f - dragY / (dismissPx * 3f)).coerceIn(0.3f, 1f) }
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            visible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .offset { IntOffset(0, dragY.roundToInt()) }
                    .fillMaxWidth()
                    .padding(8.dp)
                    .glass(RoundedCornerShape(24.dp), tint = Glass.palette.glassTint, floating = true)
                    // Swallow taps so they don't reach the scrim.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .imePadding()
                    .heightIn(max = 640.dp)
                    .nestedScroll(sheetScroll)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(PillShape)
                        .background(Glass.palette.textTertiary),
                )
                content()
            }
        }
    }
}

/** Small count bubble for in-app badges (requests, unread messages). Hidden at 0. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val p = Glass.palette
    Box(
        modifier
            .heightIn(min = 20.dp)
            .clip(PillShape)
            .background(p.accent)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = p.onAccent,
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
        )
    }
}

/** A [GlassIconButton] with a [CountBadge] on its corner. */
@Composable
fun BadgedIconButton(icon: ImageVector, contentDescription: String, count: Int, onClick: () -> Unit, plain: Boolean = false) {
    Box {
        GlassIconButton(icon, if (count > 0) "$contentDescription, $count new" else contentDescription, onClick, plain = plain)
        CountBadge(count, Modifier.align(Alignment.TopEnd))
    }
}

/** Tappable chip, e.g. a username suggestion; selected is inverted (light on dark). */
@Composable
fun GlassChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val p = Glass.palette
    val surface = if (selected) {
        Modifier.clip(ChipShape).background(p.textPrimary)
    } else {
        Modifier.raised(ChipShape)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) p.base else p.textPrimary,
        modifier = modifier
            .then(surface)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Small action used inside list rows ("Ask to join", "Accept"). */
@Composable
fun GlassSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val p = Glass.palette
    val surface = if (primary && enabled) {
        Modifier.clip(ChipShape).background(p.accent)
    } else {
        Modifier.raised(ChipShape)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            !enabled -> p.textTertiary
            primary -> p.onAccent
            else -> p.textPrimary
        },
        maxLines = 1,
        modifier = modifier
            .then(surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Title + description + switch, for settings-style toggles. */
@Composable
fun GlassSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val p = Glass.palette
    // One toggle for the whole row, so TalkBack reads "title, subtitle, switch, on" once
    // instead of the row and the switch as two separate controls.
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = p.textPrimary)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = p.accent, checkedThumbColor = p.onAccent),
        )
    }
}

/** Transient glass message at the bottom of a screen; disappears after a few seconds. */
@Composable
fun NoticeBanner(message: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(4_000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Text(
            message.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = Glass.palette.textPrimary,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .glass(RoundedCornerShape(12.dp), floating = true)
                .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
                // Screen readers announce it without the user having to find it.
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** A coloured dot, e.g. the green "playing now" marker. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/**
 * The app's signature (the startup animation's two friends, red and orange) meeting: marks
 * something live and in sync, like a room people are in. Used for that and nothing else.
 */
@Composable
fun SyncDots(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val p = Glass.palette
    Box(modifier.size(width = size * 1.6f, height = size)) {
        Box(Modifier.size(size).clip(CircleShape).background(p.friendA))
        Box(Modifier.align(Alignment.CenterEnd).size(size).clip(CircleShape).background(p.friendB))
    }
}

/** How far a bottom sheet must be pulled down to close when let go. */
private val DISMISS_DRAG = 120.dp

/** A downward flick faster than this (px/s) closes a bottom sheet. */
private const val DISMISS_VELOCITY = 1_800f
