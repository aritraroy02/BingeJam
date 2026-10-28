package com.syncit.app.ui.chat

import android.text.format.DateFormat
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.syncit.app.ui.glass.PillShape
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

/** Vertical gap above a message: tight inside a group, normal otherwise. */
internal fun messageGap(grouped: Boolean) = if (grouped) 2.dp else 8.dp

/**
 * [items] (oldest first) as chat rows with times and day separators, kept fresh: re-read once a
 * minute so "Today" rolls over at midnight and changes to the 12/24-hour setting or time zone
 * show up. [serverNowMs] is the server clock (message stamps are server time).
 */
@Composable
internal fun <T> rememberChatRows(
    items: List<T>,
    id: (T) -> String,
    sender: (T) -> String,
    sentAt: (T) -> Long,
    serverNowMs: () -> Long,
): List<ChatTimes.Row<T>> {
    val context = LocalContext.current
    val tick by produceState(0) {
        while (true) {
            delay(60_000)
            value = value + 1
        }
    }
    val is24h = remember(tick) { DateFormat.is24HourFormat(context) }
    val zone = remember(tick) { ZoneId.systemDefault() }
    val now = remember(tick, items) { serverNowMs() }
    // The rest of the UI is English, so dates are too ("Monday", "12 October").
    return remember(items, now, is24h, zone) { ChatTimes.rows(items, id, sender, sentAt, now, zone, is24h, Locale.ENGLISH) }
}

/** Centred glass pill between days: "Today", "Yesterday", "Monday", "12 October". */
@Composable
internal fun DaySeparator(label: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Glass.palette.textSecondary,
            modifier = Modifier.glass(PillShape).padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

/** The friend's side of the chat: a bubble with three dots bouncing in turn, like WhatsApp. */
@Composable
internal fun TypingBubble(name: String, modifier: Modifier = Modifier) {
    val p = Glass.palette
    val transition = rememberInfiniteTransition(label = "typing")
    Row(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .glass(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .padding(horizontal = 16.dp, vertical = 15.dp)
                .clearAndSetSemantics { contentDescription = "${name.ifEmpty { "Your friend" }} is typing" },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i ->
                // 0 = resting, 1 = top of the bounce. Each dot starts a little after the previous one.
                val lift by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 1_200
                            0f at 0
                            1f at 220
                            0f at 440
                            0f at 1_200
                        },
                        initialStartOffset = StartOffset(i * 160),
                    ),
                    label = "dot$i",
                )
                Box(
                    Modifier
                        // Read in the layer block so the bounce redraws without recomposing.
                        .graphicsLayer {
                            translationY = -5.dp.toPx() * lift
                            alpha = 0.45f + 0.55f * lift
                        }
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(p.textSecondary),
                )
            }
        }
    }
}
