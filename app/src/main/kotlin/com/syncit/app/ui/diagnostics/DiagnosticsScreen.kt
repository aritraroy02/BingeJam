package com.syncit.app.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.permissions.Permissions
import com.syncit.core.config.StreamingConfig
import com.syncit.core.model.MediaSessionInfo
import com.syncit.media.PlaybackStateMapper
import com.syncit.sync.PositionMath

/**
 * Phase 0 debug screen: every active media session, live, plus play/pause/seek buttons.
 * Used on a real phone to learn what Netflix and JioHotstar expose.
 */
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val accessGranted by viewModel.accessGranted.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val now by viewModel.nowElapsed.collectAsStateWithLifecycle()
    val selectedKey by viewModel.selectedKey.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    TextButton(onClick = onBack) { Text("← Back") }
                }
                Text("Media session diagnostics", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Start a movie in Netflix or JioHotstar, then come back here. Every app with an " +
                        "active media session is listed below and updates live. Also logged to " +
                        "`adb logcat -s SyncItDiag`.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (!accessGranted) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Notification Access is off", fontWeight = FontWeight.Bold)
                            Text("Without it, Android won't show us other apps' media sessions.")
                            Button(onClick = { Permissions.openNotificationAccessSettings(context) }) {
                                Text("Open settings")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Configured: Netflix = ${StreamingConfig.NETFLIX_PACKAGE}, JioHotstar = " +
                        StreamingConfig.JIOHOTSTAR_PACKAGE.ifBlank { "(not set: find it below, see RUN_ON_MY_MACHINE.md)" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (accessGranted && sessions.isEmpty()) {
                item { Text("No active media sessions. Start playing something.") }
            }

            items(sessions, key = { it.sessionKey }) { session ->
                SessionCard(
                    session = session,
                    nowElapsed = now,
                    selected = session.sessionKey == selectedKey,
                    onSelect = { viewModel.select(session.sessionKey) },
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Send to selected session", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::play, enabled = selectedKey != null) { Text("Play") }
                        Button(onClick = viewModel::pause, enabled = selectedKey != null) { Text("Pause") }
                        OutlinedButton(onClick = viewModel::seekForward30, enabled = selectedKey != null) { Text("Seek +30s") }
                    }
                    lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: MediaSessionInfo,
    nowElapsed: Long,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(session.packageName, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            val s = session.snapshot
            if (s == null) {
                Text("No PlaybackState published")
                return@Column
            }
            val live = PositionMath.localLivePositionMs(s, nowElapsed)
            Field("state", PlaybackStateMapper.stateLabel(s.rawState))
            Field("position (reported)", formatMs(s.positionMs))
            Field("position (live, extrapolated)", formatMs(live))
            Field("lastPositionUpdateTime", "${s.lastPositionUpdateElapsedMs} (now ${nowElapsed})")
            Field("speed", s.speed.toString())
            Field("actions", "0x${s.rawActions.toString(16)}")
            Text(
                s.actions.sortedBy { it.ordinal }.joinToString(", ").ifEmpty { "(none)" },
                style = MaterialTheme.typography.bodySmall,
            )
            Field("play/pause supported", yesNo(s.supportsPlayPause))
            Field("SEEK_TO supported", yesNo(s.supportsSeek))
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Text("$label: $value", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
}

private fun yesNo(value: Boolean) = if (value) "yes" else "NO"

internal fun formatMs(ms: Long): String {
    if (ms < 0) return "unknown ($ms)"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "%d:%02d:%02d.%03d (%d ms)".format(h, m, s, ms % 1000, ms)
}
