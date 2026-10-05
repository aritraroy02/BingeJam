package com.syncit.app.ui.room

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncit.core.model.Countdown

/** How long "PLAY NOW" stays up after the target time. */
private const val PLAY_NOW_VISIBLE_MS = 2_000L

/**
 * Manual "3, 2, 1, play" fallback for when we can't control the app. Every phone counts down
 * to the same server time (countdown.playAt), using its own server-time estimate, so the
 * countdowns line up across phones without trusting device clocks.
 */
@Composable
fun CountdownCard(countdown: Countdown?, serverNow: Long, prominent: Boolean, onStart: () -> Unit) {
    val remainingMs = countdown?.let { it.playAt - serverNow }
    val active = remainingMs != null && remainingMs > -PLAY_NOW_VISIBLE_MS

    if (!prominent && !active) {
        // Always available as a manual backup, but kept small when sync works.
        OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text("3, 2, 1 countdown (manual)") }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active) {
                val label = when {
                    remainingMs <= 0 -> "PLAY NOW"
                    remainingMs > 3_000 -> "Get ready…"
                    else -> ((remainingMs + 999) / 1000).toString() // ceil: 3, 2, 1
                }
                Text(label, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text("Everyone presses play on \"PLAY NOW\".", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Manual sync", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Get everyone to the same moment (e.g. the start), paused. Then start the countdown and " +
                        "everyone presses play when it says PLAY NOW.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onStart) { Text("Start 3, 2, 1 countdown") }
            }
        }
    }
}
