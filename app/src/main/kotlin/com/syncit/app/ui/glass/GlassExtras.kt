package com.syncit.app.ui.glass

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.Inter
import kotlinx.coroutines.delay

/**
 * Bottom sheet drawn in the screen's own window (not a dialog), so the glass panel still blurs
 * the screen's backdrop. Back and a tap on the scrim dismiss it.
 */
@Composable
fun GlassBottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
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
                    .fillMaxWidth()
                    .padding(8.dp)
                    .glass(RoundedCornerShape(32.dp), tint = Glass.palette.glassFallback.copy(alpha = 0.55f))
                    // Swallow taps so they don't reach the scrim.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding()
                    .imePadding()
                    .heightIn(max = 640.dp)
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
            .background(Brush.horizontalGradient(listOf(Color(0xFFFF4D8D), Color(0xFFFF7A59))))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            color = p.onAccent,
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
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

/** Tappable glass chip, e.g. a username suggestion. */
@Composable
fun GlassChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    val p = Glass.palette
    val surface = if (selected) {
        Modifier.clip(PillShape).background(Brush.horizontalGradient(listOf(p.accent, p.accent2)))
    } else {
        Modifier.glass(PillShape)
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) p.onAccent else p.textPrimary,
        modifier = modifier.then(surface).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Small pill-shaped action used inside list rows ("Ask to join", "Accept"). */
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
        Modifier.clip(PillShape).background(Brush.horizontalGradient(listOf(p.accent, p.accent2)))
    } else {
        Modifier.glass(PillShape)
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
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable { onCheckedChange(!checked) }
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
            onCheckedChange = onCheckedChange,
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
                .glass(RoundedCornerShape(20.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        )
    }
}

/** A coloured dot, e.g. the green "playing now" marker. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}
