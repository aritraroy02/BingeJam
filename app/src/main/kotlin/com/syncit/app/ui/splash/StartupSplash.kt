package com.syncit.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.syncit.app.R
import com.syncit.app.ui.glass.rememberReduceMotion
import com.syncit.app.ui.theme.Inter
import kotlinx.coroutines.delay
import kotlin.math.hypot

/**
 * Cold-start animation, "Two friends, one play" (approved from a browser preview, 2026-11-03).
 * A red and an orange-red dot fly in and draw the two sync arrows, the ring spins once into sync,
 * play drops in, a dark-red flood reveals "BingeJam", then everything glides away into the app.
 *
 * About 3.2 s; a tap skips to the exit. With animations turned off it shows the finished logo for a
 * moment and fades. Drawn over the app, so Home is already loading underneath.
 */
@Composable
fun StartupSplash(onDone: () -> Unit) {
    val reduce = rememberReduceMotion()
    val time = remember { Animatable(if (reduce) STILL_AT else 0f) }
    val fade = remember { Animatable(1f) }
    var skipped by remember { mutableStateOf(false) }
    val finish by rememberUpdatedState(onDone)

    LaunchedEffect(skipped) {
        if (reduce) {
            if (!skipped) delay(1_200)
            fade.animateTo(0f, tween(300))
        } else {
            if (skipped && time.value < EXIT_AT) time.snapTo(EXIT_AT)
            time.animateTo(END, tween((END - time.value).toInt().coerceAtLeast(1), easing = LinearEasing))
        }
        finish()
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .pointerInput(Unit) { detectTapGestures { skipped = true } }
            .semantics { contentDescription = "BingeJam" },
    ) {
        val logo = min(maxWidth * 0.8f, maxHeight * 0.42f)
        val centerY = maxHeight * CENTER_Y

        Canvas(Modifier.fillMaxSize()) { drawBackdrop(time.value) }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val e = progress(time.value, EXIT_AT, 450f, EXIT)
                    translationY = -0.18f * size.height * e
                    scaleX = 1f - 0.28f * e
                    scaleY = scaleX
                    alpha = 1f - e
                },
        ) {
            Canvas(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = centerY - logo / 2)
                    .size(logo),
            ) { drawLogo(time.value) }

            Column(
                Modifier.align(Alignment.TopCenter).padding(top = centerY + logo * 0.3f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row {
                    "BingeJam".forEachIndexed { i, letter ->
                        Text(
                            letter.toString(),
                            style = WORD,
                            modifier = Modifier.graphicsLayer {
                                val p = progress(time.value, 1_400f + 40f * i, 380f, RISE)
                                translationY = (1f - p) * 18.dp.toPx()
                                alpha = p.coerceIn(0f, 1f)
                            },
                        )
                    }
                }
                Text(
                    "Watch together",
                    style = TAG,
                    modifier = Modifier.graphicsLayer {
                        val p = progress(time.value, 1_800f, 400f, EASE_OUT)
                        translationY = (1f - p) * 6.dp.toPx()
                        alpha = p
                    },
                )
            }
        }
    }
}

/** Black screen, then the dark-red flood opening from the logo; both fade out on exit. */
private fun DrawScope.drawBackdrop(t: Float) {
    val a = 1f - progress(t, 2_800f, 450f, EASE)
    drawRect(SCREEN, alpha = a)
    val reveal = progress(t, 1_300f, 600f, FLOOD)
    if (reveal <= 0f) return
    val c = Offset(size.width / 2f, size.height * CENTER_Y)
    val clip = Path().apply { addOval(Rect(c, reveal * hypot(size.width, size.height))) }
    clipPath(clip) {
        drawRect(Brush.verticalGradient(0f to Color(0xFF0B0B0B), 0.45f to Color(0xFF1A0306), 1f to Color(0xFF4A0010)), alpha = a)
        drawRect(Brush.radialGradient(listOf(Color(0x73FF2E4D), Color(0x00FF2E4D)), c, size.width * 0.75f), alpha = a)
        drawRect(
            Brush.radialGradient(listOf(Color(0xB3C8001E), Color(0x00C8001E)), Offset(size.width / 2f, size.height * 1.1f), size.width * 0.95f),
            alpha = a,
        )
    }
}

/**
 * The launcher glyph in its 108-unit icon box (the glyph's own 48-unit box sits at 30,30): ring of
 * two arrows of radius 14 and width 4, play triangle, frosted disc of radius 26.
 */
private fun DrawScope.drawLogo(t: Float) {
    val u = size.width / 108f
    val center = Offset(size.width / 2f, size.height / 2f)
    fun g(x: Float, y: Float) = Offset((x + 30f) * u, (y + 30f) * u)

    // Ripple out of the finished logo.
    val rr = (t - 1_350f) / 800f
    if (rr in 0f..1f) {
        val p = EASE_OUT.transform(rr)
        val a = if (p < 0.06f) p / 0.06f * 0.85f else 0.85f * (1f - (p - 0.06f) / 0.94f)
        val s = 1f + 1.7f * p
        drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 1f)), 26f * u * s, center, style = Stroke(1.2f * u * s))
    }

    // Frosted disc behind the glyph.
    val d = progress(t, 1_300f, 400f, EASE_OUT)
    if (d > 0f) {
        val r = 26f * u * (0.8f + 0.2f * d)
        drawCircle(FRIEND_A.copy(alpha = 0.16f * d), r, center)
        drawCircle(Color(0xFFFF5A6E).copy(alpha = 0.6f * d), r, center, style = Stroke(0.6f * u))
    }

    // The two arrows: drawn by the friends, then one spin into sync, turning white.
    val drawn = progress(t, 380f, 420f, DRAW)
    val white = ((t - 880f) / 500f).coerceIn(0f, 1f)
    val colorA = lerp(FRIEND_B, Color.White, white)
    val colorB = lerp(FRIEND_A, Color.White, white)
    rotate(-360f * progress(t, 800f, 550f, SPIN), center) {
        if (drawn > 0f) {
            val ring = Rect(center, 14f * u)
            val stroke = Stroke(4f * u, cap = StrokeCap.Butt)
            drawArc(colorA, 28f, -118f * drawn, false, ring.topLeft, ring.size, style = stroke)
            drawArc(colorB, 208f, -118f * drawn, false, ring.topLeft, ring.size, style = stroke)
        }
        val hp = ((t - 700f) / 260f).coerceIn(0f, 1f)
        if (hp > 0f) {
            val s = if (hp < 0.6f) 1.2f * EASE_OUT.transform(hp / 0.6f) else 1.2f - 0.2f * EASE_OUT.transform((hp - 0.6f) / 0.4f)
            val a = (hp / 0.6f).coerceAtMost(1f)
            drawTriangle(colorA.copy(alpha = a), g(16f, 10f), s, g(24f, 2f), g(16f, 10f), g(24f, 18f))
            drawTriangle(colorB.copy(alpha = a), g(32f, 38f), s, g(24f, 46f), g(32f, 38f), g(24f, 30f))
        }
    }

    // Play drops in with a small bounce.
    val pp = (t - 1_150f) / 450f
    if (pp > 0f) {
        val (dy, s, a) = when {
            pp < 0.55f -> EASE_OUT.transform(pp / 0.55f).let { Triple(-26f + 28f * it, 0.6f + 0.48f * it, it) }
            pp < 0.78f -> EASE_OUT.transform((pp - 0.55f) / 0.23f).let { Triple(2f - 3f * it, 1.08f - 0.11f * it, 1f) }
            else -> EASE_OUT.transform(((pp - 0.78f) / 0.22f).coerceAtMost(1f)).let { Triple(-1f + it, 0.97f + 0.03f * it, 1f) }
        }
        val shift = Offset(0f, dy * u)
        drawTriangle(Color.White.copy(alpha = a), g(25f, 24f) + shift, s, g(20f, 17f) + shift, g(30f, 24f) + shift, g(20f, 31f) + shift)
    }

    // The two friends fly in from the sides and vanish where their arrows start.
    drawFriend(t, FRIEND_A, g(11.64f, 17.43f), Offset(-120f * u, -10f * u))
    drawFriend(t, FRIEND_B, g(36.36f, 30.57f), Offset(120f * u, 10f * u))
}

private fun DrawScope.drawFriend(t: Float, color: Color, at: Offset, from: Offset) {
    val p = (t - 50f) / 450f
    if (p !in 0f..1f) return
    val u = size.width / 108f
    val pos = if (p < 0.7f) at + from * (1f - FLY.transform(p / 0.7f)) else at
    val a = when {
        p < 0.15f -> p / 0.15f
        p < 0.7f -> 1f
        else -> 1f - (p - 0.7f) / 0.3f
    }
    val s = if (p < 0.7f) 1f else 1f - 0.7f * (p - 0.7f) / 0.3f
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.6f * a), color.copy(alpha = 0f)), pos, 7f * u * s), 7f * u * s, pos)
    drawCircle(color.copy(alpha = a), 2.6f * u * s, pos)
}

/** Triangle [p1]-[p2]-[p3] scaled by [scale] around [pivot]. */
private fun DrawScope.drawTriangle(color: Color, pivot: Offset, scale: Float, p1: Offset, p2: Offset, p3: Offset) {
    if (scale <= 0f) return
    fun s(p: Offset) = pivot + (p - pivot) * scale
    val path = Path().apply {
        moveTo(s(p1).x, s(p1).y)
        lineTo(s(p2).x, s(p2).y)
        lineTo(s(p3).x, s(p3).y)
        close()
    }
    drawPath(path, color)
}

private fun progress(t: Float, start: Float, duration: Float, easing: Easing = LinearEasing): Float =
    easing.transform(((t - start) / duration).coerceIn(0f, 1f))

private const val END = 3_250f
private const val EXIT_AT = 2_750f
/** Reduce motion: the finished logo and name, before the exit. */
private const val STILL_AT = 2_300f
/** Logo centre, as a fraction of the screen height. */
private const val CENTER_Y = 0.43f

private val SCREEN = Color(0xFF050505)
private val FRIEND_A = Color(0xFFFF2E4D)
private val FRIEND_B = Color(0xFFFF7A45)

private val EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val FLY = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private val DRAW = CubicBezierEasing(0.5f, 0f, 0.2f, 1f)
private val SPIN = CubicBezierEasing(0.65f, 0f, 0.25f, 1f)
private val FLOOD = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)
private val RISE = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.2f)
private val EXIT = CubicBezierEasing(0.5f, 0f, 0.7f, 0.4f)

/** Inter's variable weight doesn't take effect on some phones (seen on OPPO), so the name's weight is synthesised. */
private val InterHeavy = FontFamily(Font(R.font.inter, FontWeight.Normal))

private val WORD = TextStyle(
    color = Color.White,
    fontFamily = InterHeavy,
    fontSize = 36.sp,
    fontWeight = FontWeight.ExtraBold,
    fontSynthesis = FontSynthesis.Weight,
    letterSpacing = (-0.5).sp,
    shadow = Shadow(Color(0x73FF1E3C), Offset(0f, 4f), 40f),
)
private val TAG = TextStyle(color = Color(0xFFFF8A9A), fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp)
