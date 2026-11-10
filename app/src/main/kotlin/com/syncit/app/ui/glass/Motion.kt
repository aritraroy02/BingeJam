package com.syncit.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.theme.Glass

/** Ease-out with a soft landing; used for things arriving on screen. */
val EmphasizedEase = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * Entrance for the [index]-th section of a screen: fades and rises into place, each one 60 ms
 * after the previous, so a screen assembles instead of popping in. Skipped with reduced motion.
 */
fun Modifier.staggerIn(index: Int): Modifier = composed {
    val reduce = rememberReduceMotion()
    if (reduce) return@composed this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 380, delayMillis = (index.coerceAtMost(8)) * 60, easing = EmphasizedEase))
    }
    val rise = with(LocalDensity.current) { 16.dp.toPx() }
    graphicsLayer {
        val t = progress.value
        alpha = t
        translationY = (1f - t) * rise
        val s = 0.97f + 0.03f * t
        scaleX = s
        scaleY = s
    }
}

/**
 * A quick "pop" each time [trigger] changes (a reaction tapped, a message sent): scales up and
 * springs back. Reduced motion keeps it still.
 */
fun Modifier.popOn(trigger: Any?): Modifier = composed {
    val reduce = rememberReduceMotion()
    val scale = remember { Animatable(1f) }
    LaunchedEffect(trigger) {
        if (reduce || trigger == null) return@LaunchedEffect
        scale.snapTo(1.25f)
        scale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 600f))
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Soft light sweeping across loading placeholders. Static with reduced motion. */
@Composable
private fun shimmerBrush(): Brush {
    val p = Glass.palette
    val base = p.textPrimary.copy(alpha = 0.07f)
    val shine = p.textPrimary.copy(alpha = 0.16f)
    if (rememberReduceMotion()) return Brush.linearGradient(listOf(base, base))
    val x by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -400f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmer-x",
    )
    return Brush.linearGradient(
        colors = listOf(base, shine, base),
        start = androidx.compose.ui.geometry.Offset(x, 0f),
        end = androidx.compose.ui.geometry.Offset(x + 400f, 0f),
    )
}

/**
 * Placeholder rows shaped like the friend list (avatar, name, status) while it loads, so the
 * screen keeps its layout instead of jumping when the data arrives.
 */
@Composable
fun SkeletonRows(count: Int, modifier: Modifier = Modifier) {
    val brush = shimmerBrush()
    Column(modifier.semantics { contentDescription = "Loading" }) {
        repeat(count) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(brush))
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(Modifier.width(140.dp).height(16.dp).clip(RoundedCornerShape(8.dp)).background(brush))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(8.dp)).background(brush))
                }
            }
        }
    }
}
