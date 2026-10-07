package com.syncit.app.ui.room

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.ui.common.ArtworkCard
import com.syncit.app.ui.common.rememberArtwork
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSegmented
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.PillShape
import com.syncit.app.ui.glass.PlatformBadge
import com.syncit.app.ui.glass.StatusPill
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.RoomLinks
import com.syncit.core.config.SyncStatusLabels
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.SyncSessionState

/** How long "PLAY NOW" stays up after the target time. */
private const val PLAY_NOW_VISIBLE_MS = 2_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomScreen(viewModel: RoomViewModel, onBack: () -> Unit, onLeft: () -> Unit) {
    val context = LocalContext.current
    val p = Glass.palette
    val room by viewModel.room.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val sync by viewModel.sync.collectAsStateWithLifecycle()
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()
    val serverNow by viewModel.serverNow.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val reactions by viewModel.reactions.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.enter() }

    val artwork = rememberArtwork(preview?.artworkJpeg)
    val currentRoom = room

    GlassScreen(artwork = artwork) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            GlassTopBar(
                navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                actions = {
                    GlassIconButton(Icons.Filled.Share, "Invite", onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, RoomLinks.shareText(viewModel.code))
                        context.startActivity(Intent.createChooser(send, "Invite to Sync it"))
                    })
                    GlassIconButton(Icons.AutoMirrored.Filled.ExitToApp, "Leave room", onClick = { confirmLeave = true })
                },
            )

            // The hero gives way to the chat while typing.
            AnimatedVisibility(visible = !WindowInsets.isImeVisible, enter = fadeIn(), exit = fadeOut()) {
                RoomHero(
                    code = viewModel.code,
                    artwork = artwork,
                    preview = preview,
                    room = currentRoom,
                    sync = sync,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            val online = currentRoom?.members?.count { it.online } ?: 0
            GlassSegmented(
                options = listOf("Chat", "People · $online", "Sync"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (currentRoom == null) {
                    Text(
                        "Connecting to room…",
                        color = p.textSecondary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    when (tab) {
                        0 -> ChatTab(
                            chat = chat,
                            myUid = myUid,
                            reactions = reactions,
                            onSend = viewModel::sendChat,
                            onReact = viewModel::sendReaction,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp).navigationBarsPadding(),
                        )
                        1 -> PeopleTab(
                            room = currentRoom,
                            myUid = myUid,
                            serverNow = serverNow,
                            myEngineDriftMs = sync.driftMs,
                            modifier = Modifier.fillMaxSize(),
                        )
                        else -> SyncTab(
                            room = currentRoom,
                            sync = sync,
                            isHost = currentRoom.hostUid == myUid,
                            onResume = viewModel::enter,
                            onStartCountdown = viewModel::startCountdown,
                            onSaveTitle = viewModel::setTitle,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        CountdownOverlay(room = currentRoom, serverNow = serverNow)
    }

    message?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            confirmButton = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            text = { Text(it) },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this room?") },
            text = { Text("You'll stop syncing with everyone. You can rejoin with the code ${viewModel.code}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    ChatBubbleService.stop(context)
                    viewModel.leave(onLeft)
                }) { Text("Leave", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
}

/** Now playing: artwork, platform, title, sync status. */
@Composable
private fun RoomHero(
    code: String,
    artwork: ImageBitmap?,
    preview: RoomPreview?,
    room: Room?,
    sync: SyncSessionState,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    val service = preview?.service ?: room?.title?.service ?: sync.service
    val title = preview?.title ?: if (service != null) "Start the movie in ${service.displayName}" else "Nothing playing yet"
    val subtitle = preview?.subtitle
        ?: if (preview?.title == null) "Open Netflix or JioHotstar and press play. Everyone follows." else null
    val statusText = SyncStatusLabels.label(sync.status, service?.displayName ?: "Netflix or JioHotstar")

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (artwork != null && artwork.width >= artwork.height) {
            // Landscape art: title over a gradient, like a streaming app.
            Box(Modifier.fillMaxWidth().heightIn(max = 210.dp)) {
                ArtworkCard(artwork, Modifier.fillMaxWidth())
                Box(
                    Modifier
                        .matchParentSize()
                        .padding(1.dp)
                        .background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))),
                            shape = RoundedCornerShape(22.dp),
                        ),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (service != null) PlatformBadge(service)
                    Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (artwork != null) {
                    ArtworkCard(artwork, Modifier.width(92.dp), corner = 16)
                    Spacer(Modifier.width(16.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (service != null) PlatformBadge(service)
                    Text(title, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    subtitle?.let { Text(it, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(statusText, sync.status.color())
            Text(
                code,
                style = MaterialTheme.typography.labelMedium,
                color = p.textSecondary,
                letterSpacing = 2.sp,
                modifier = Modifier.glass(PillShape).padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
internal fun SyncStatus.color(): Color {
    val p = Glass.palette
    return when (this) {
        SyncStatus.SYNCING -> p.success
        SyncStatus.CORRECTING, SyncStatus.CATCHING_UP, SyncStatus.WAITING_FOR_ROOM -> p.warning
        SyncStatus.IDLE -> p.textTertiary
        else -> p.danger
    }
}

/**
 * Manual "3, 2, 1, play": covers the whole screen on every phone. Each phone counts down to
 * the same server time (countdown.playAt) using its own server-time estimate, so the
 * countdowns line up without trusting device clocks.
 */
@Composable
private fun CountdownOverlay(room: Room?, serverNow: Long) {
    val p = Glass.palette
    val remainingMs = room?.countdown?.let { it.playAt - serverNow }
    val active = remainingMs != null && remainingMs > -PLAY_NOW_VISIBLE_MS
    AnimatedVisibility(visible = active, enter = fadeIn(), exit = fadeOut()) {
        val ms = remainingMs ?: 0L
        val label = when {
            ms <= 0 -> "PLAY"
            ms > 3_000 -> "Ready?"
            else -> ((ms + 999) / 1000).toString() // ceil: 3, 2, 1
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(200.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                    Text(label, fontSize = if (label.length > 2) 40.sp else 88.sp, fontWeight = FontWeight.ExtraBold, color = p.textPrimary)
                }
                Text(
                    if (ms <= 0) "Everyone press play now" else "Get ready to press play together",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }
    }
}
