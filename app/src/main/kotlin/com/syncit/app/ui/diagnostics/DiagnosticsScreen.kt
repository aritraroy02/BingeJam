package com.syncit.app.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.permissions.Permissions
import com.syncit.app.ui.common.ArtworkCard
import com.syncit.app.ui.common.rememberArtwork
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.PanelShape
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.Inter
import com.syncit.core.config.StreamingConfig
import com.syncit.core.model.MediaSessionInfo
import com.syncit.media.PlaybackStateMapper
import com.syncit.sync.PositionMath

/**
 * Phase 0: lists every active media session with its live PlaybackState and metadata, and
 * lets you send play/pause/seek to one, to see what Netflix/JioHotstar expose on a real phone.
 */
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val p = Glass.palette
    val accessGranted by viewModel.accessGranted.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val now by viewModel.nowElapsed.collectAsStateWithLifecycle()
    val selectedKey by viewModel.selectedKey.collectAsStateWithLifecycle()
    val lastResult by viewModel.lastResult.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = "Media diagnostics",
                navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "Start a movie in Netflix or JioHotstar, then come back. Every app with an active media " +
                            "session is listed and updates live (also logged to `adb logcat -s SyncItDiag`).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                    )
                    Text(
                        "Configured: Netflix = ${StreamingConfig.NETFLIX_PACKAGE}, JioHotstar = " +
                            StreamingConfig.JIOHOTSTAR_PACKAGE.ifBlank { "(not set)" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = Inter,
                        color = p.textTertiary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (!accessGranted) {
                    item {
                        GlassPanel {
                            Text("Notification Access is off", style = MaterialTheme.typography.titleMedium, color = p.danger)
                            Text("Without it, Android won't show us other apps' media sessions.", color = p.textSecondary)
                            GlassButton("Open settings", onClick = { Permissions.openNotificationAccessSettings(context) })
                        }
                    }
                }
                if (accessGranted && sessions.isEmpty()) {
                    item { Text("No active media sessions. Start playing something.", color = p.textSecondary) }
                }
                items(sessions, key = { it.sessionKey }) { session ->
                    SessionCard(
                        session = session,
                        nowElapsed = now,
                        selected = session.sessionKey == selectedKey,
                        onSelect = { viewModel.select(session.sessionKey) },
                    )
                }
            }

            // Fixed at the bottom so the buttons don't move as cards update.
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .glass(PanelShape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    lastResult ?: if (selectedKey == null) "Tap a session to control it" else "Send to the selected session",
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = selectedKey != null
                    GlassButton("Play", onClick = viewModel::play, enabled = enabled, modifier = Modifier.weight(1f))
                    GlassButton("Pause", onClick = viewModel::pause, enabled = enabled, modifier = Modifier.weight(1f))
                    GlassButton("+30s", onClick = viewModel::seekForward30, enabled = enabled, style = GlassButtonStyle.Secondary, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: MediaSessionInfo, nowElapsed: Long, selected: Boolean, onSelect: () -> Unit) {
    val p = Glass.palette
    val artwork = rememberArtwork(session.metadata?.artworkJpeg)
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(BorderStroke(2.dp, p.accent), PanelShape) else Modifier),
        spacing = 2.dp,
        onClick = onSelect,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (artwork != null) {
                ArtworkCard(artwork, Modifier.width(72.dp), corner = 12)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(session.packageName, style = MaterialTheme.typography.titleSmall, fontFamily = Inter, color = p.textPrimary)
                val meta = session.metadata
                Field("title", meta?.title ?: "(none)")
                Field("subtitle", meta?.subtitle ?: "(none)")
                Field("artwork", meta?.artworkJpeg?.let { "${artwork?.width ?: "?"}×${artwork?.height ?: "?"}, ${it.size / 1024} KB" } ?: "(none)")
            }
        }
        Spacer(Modifier.width(4.dp))
        val s = session.snapshot
        if (s == null) {
            Field("state", "no PlaybackState published")
            return@GlassPanel
        }
        Field("state", PlaybackStateMapper.stateLabel(s.rawState))
        Field("position (reported)", formatMs(s.positionMs))
        Field("position (live)", formatMs(PositionMath.localLivePositionMs(s, nowElapsed)))
        Field("speed", s.speed.toString())
        Field("actions", "0x${s.rawActions.toString(16)}")
        Text(
            s.actions.sortedBy { it.ordinal }.joinToString(", ").ifEmpty { "(none)" },
            style = MaterialTheme.typography.bodySmall,
            color = p.textTertiary,
        )
        Field("play/pause · seek", "${yesNo(s.supportsPlayPause)} · ${yesNo(s.supportsSeek)}")
    }
}

@Composable
private fun Field(label: String, value: String) {
    Text(
        "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = Inter,
        color = Glass.palette.textSecondary,
    )
}

private fun yesNo(value: Boolean) = if (value) "yes" else "NO"

internal fun formatMs(ms: Long): String {
    if (ms < 0) return "unknown ($ms)"
    val totalSeconds = ms / 1000
    return "%d:%02d:%02d.%03d".format(totalSeconds / 3600, (totalSeconds % 3600) / 60, totalSeconds % 60, ms % 1000)
}
