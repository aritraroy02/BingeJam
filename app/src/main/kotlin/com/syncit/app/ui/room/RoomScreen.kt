package com.syncit.app.ui.room

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.syncit.app.overlay.ChatBubbleService
import com.syncit.app.social.RoomWording
import com.syncit.app.ui.common.ArtworkCard
import com.syncit.app.ui.common.rememberArtwork
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSegmented
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.PillShape
import com.syncit.app.ui.glass.PlatformBadge
import com.syncit.app.ui.glass.StatusPill
import com.syncit.app.ui.glass.glass
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.JamFollow
import com.syncit.core.session.SyncSessionState
import com.syncit.sync.PositionMath

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
    var inviteOpen by rememberSaveable { mutableStateOf(false) }
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val invited by viewModel.invited.collectAsStateWithLifecycle()
    val netflixId by viewModel.netflixId.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.enter() }

    val artwork = rememberArtwork(preview?.artworkJpeg)
    val currentRoom = room
    // What the room plays: the host's live preview first, then the shared link, then our own session.
    val roomService = preview?.service ?: currentRoom?.title?.service ?: sync.service
    val jam = roomService?.isJam == true
    // Invite text built here so it carries the current product name (core's RoomLinks.shareText
    // still says the old one) and says "jam" for music/YouTube rooms.
    val inviteText = RoomWording.shareText(viewModel.code, jam)
    val isHost = currentRoom != null && currentRoom.hostUid == myUid

    // "Play in <app>": jams open the host's exact track/video; watch parties the link or the app.
    val openInApp: (StreamingService) -> Unit = { service ->
        val opened = if (service.isJam) {
            val ref = preview?.takeIf { it.service == service }?.playableRef
            openJamItem(context, service, ref, currentRoom?.title)
        } else {
            if (service == StreamingService.NETFLIX) {
                openNetflix(context, netflixId, NetflixLinks.query(preview), currentRoom?.title)
            } else {
                openForPlay(context, service, currentRoom?.title)
            }
        }
        if (!opened) {
            Toast.makeText(context, "${service.displayName} isn't installed", Toast.LENGTH_LONG).show()
        }
    }

    // Jams: open the member's app by itself when it has no media session yet (NEEDS_APP), only
    // while this screen is in front, and once per room + host track (see JamAutoOpen). Waits for
    // the room so the host is never mistaken for a member.
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val autoKey = JamAutoOpen.key(viewModel.code, preview?.playableRef, preview?.title)
    val roomLoaded = currentRoom != null
    LaunchedEffect(resumed, roomLoaded, isHost, roomService, sync.jamFollow, autoKey) {
        val service = roomService ?: return@LaunchedEffect
        if (roomLoaded &&
            JamAutoOpen.shouldOpen(resumed, isHost, service, sync.jamFollow, autoKey, viewModel.autoOpenedKeys()) &&
            autoKey != null && viewModel.claimAutoOpen(autoKey)
        ) {
            openInApp(service)
        }
    }

    GlassScreen(artwork = artwork) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            GlassTopBar(
                navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                actions = {
                    GlassIconButton(Icons.Filled.Add, "Invite friends", onClick = { inviteOpen = true })
                    GlassIconButton(Icons.Filled.Share, "Share invite link", onClick = {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, inviteText)
                        context.startActivity(Intent.createChooser(send, "Invite to ${Brand.NAME}"))
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
                    service = roomService,
                    room = currentRoom,
                    sync = sync,
                    isHost = isHost,
                    serverNow = serverNow,
                    netflixId = netflixId,
                    onPlay = openInApp,
                    onFollowHost = viewModel::followHost,
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
                            serverNowMs = viewModel::serverNowMs,
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
                            service = roomService,
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

        InviteFriendsSheet(
            visible = inviteOpen,
            jam = jam,
            friends = friends,
            invited = invited,
            memberUids = currentRoom?.members.orEmpty().filter { it.online }.map { it.uid }.toSet(),
            onInvite = viewModel::inviteFriend,
            onShareLink = {
                inviteOpen = false
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, inviteText)
                context.startActivity(Intent.createChooser(send, "Invite to ${Brand.NAME}"))
            },
            onDismiss = { inviteOpen = false },
        )
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

/** Now playing: artwork, platform, title, sync status, and a nudge for members who aren't on it. */
@Composable
private fun RoomHero(
    code: String,
    artwork: ImageBitmap?,
    preview: RoomPreview?,
    service: StreamingService?,
    room: Room?,
    sync: SyncSessionState,
    isHost: Boolean,
    serverNow: Long,
    netflixId: String?,
    onPlay: (StreamingService) -> Unit,
    onFollowHost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Glass.palette
    val jam = service?.isJam == true
    val title = preview?.title ?: when {
        service == null -> "Nothing playing yet"
        jam && isHost -> "Play something in ${service.displayName}"
        jam -> "Waiting for the host's track"
        else -> "Start the movie in ${service.displayName}"
    }
    val subtitle = preview?.subtitle ?: when {
        preview?.title != null -> null
        service == null -> "Press play in Netflix, Prime Video, Spotify, YouTube or another supported app. Everyone follows."
        jam -> "Everyone follows the host from track to track."
        else -> "Open ${service.displayName} and press play. Everyone follows."
    }
    val statusText = MemberPrompts.statusLabel(sync.status, service)
    // Jams: "Jam with Riya" next to the platform badge (just "Jam" for the host).
    val jamLabel = if (jam) preview?.hostName?.takeIf { it.isNotBlank() && !isHost }?.let { "Jam with $it" } ?: "Jam" else null
    val prompt = MemberPrompts.of(isHost, service, sync.status, sync.jamFollow)

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
                    HeroBadges(service, jamLabel, labelColor = Color.White.copy(alpha = 0.85f))
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
                    HeroBadges(service, jamLabel, labelColor = p.accent2)
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
        if (service != null) {
            if (jam) {
                JamPrompt(prompt, service, preview, sync, onPlay, onFollowHost)
            } else if (prompt == MemberPrompt.OPEN_APP) {
                WatchPartyPrompt(service, preview, room, sync, serverNow, netflixId, onPlay)
            }
        }
    }
}

@Composable
private fun HeroBadges(service: StreamingService?, jamLabel: String?, labelColor: Color) {
    if (service == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PlatformBadge(service)
        jamLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * Watch parties: members who aren't watching the host's title yet get one tap into it. For a
 * Netflix movie whose ID was found ([netflixId]), that starts the movie itself; otherwise we open
 * the app (the host's link, or a search for the movie) and say which episode to start. Once it
 * matches, the coordinator joins it to the room's position.
 */
@Composable
private fun WatchPartyPrompt(
    service: StreamingService,
    preview: RoomPreview?,
    room: Room?,
    sync: SyncSessionState,
    serverNow: Long,
    netflixId: String?,
    onPlay: (StreamingService) -> Unit,
) {
    val p = Glass.palette
    val uriHandler = LocalUriHandler.current
    val episode = listOfNotNull(preview?.subtitle, preview?.title).joinToString(" · ")
    val at = room?.state?.let { formatClock(PositionMath.roomExpectedPositionMs(it, serverNow)) }
    GlassButton(
        text = "Play in ${service.displayName}",
        icon = Icons.Filled.PlayArrow,
        onClick = { onPlay(service) },
        modifier = Modifier.fillMaxWidth(),
    )
    val direct = netflixId != null && service == StreamingService.NETFLIX
    val start = when {
        direct -> "Opens ${preview?.title ?: "the movie"} in ${service.displayName}"
        episode.isNotEmpty() -> "Start $episode"
        else -> "Start what the host is watching"
    }
    val jump = if (at != null) " and ${Brand.NAME} jumps you to $at." else ", and ${Brand.NAME} lines you up with the room."
    Text(
        (if (sync.status == SyncStatus.OTHER_TITLE) "You're on a different episode. " else "") + start + jump,
        style = MaterialTheme.typography.bodySmall,
        color = if (sync.status == SyncStatus.OTHER_TITLE) p.warning else p.textSecondary,
    )
    if (direct) {
        // Required credit for Watchmode's free plan, which found the Netflix ID.
        Text(
            "Movie found with Watchmode",
            style = MaterialTheme.typography.labelSmall,
            color = p.textSecondary,
            modifier = Modifier.clickable { uriHandler.openUri("https://www.watchmode.com") },
        )
    }
}

/**
 * Jams: members follow the host's track automatically (RoomSyncCoordinator in :media). The UI
 * steps in only when it must: opening the app when it has no media session yet (NEEDS_APP; a
 * background service can't launch apps), or "Back to the jam" when the app ignored the request
 * or the member picked another track.
 */
@Composable
private fun JamPrompt(
    prompt: MemberPrompt,
    service: StreamingService,
    preview: RoomPreview?,
    sync: SyncSessionState,
    onPlay: (StreamingService) -> Unit,
    onFollowHost: () -> Unit,
) {
    val p = Glass.palette
    val app = service.displayName
    val what = preview?.title?.takeIf { it.isNotBlank() } ?: "the host's track"
    when (prompt) {
        MemberPrompt.NONE -> Unit
        MemberPrompt.STARTING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), color = p.accent, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Starting $what in $app…", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
        }
        MemberPrompt.OPEN_APP -> {
            GlassButton(
                text = "Play in $app",
                icon = Icons.Filled.PlayArrow,
                onClick = { onPlay(service) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Opens $what in $app. After that, ${Brand.NAME} keeps you on the host's track.",
                style = MaterialTheme.typography.bodySmall,
                color = p.textSecondary,
            )
        }
        MemberPrompt.BACK_TO_JAM -> {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassButton(
                    text = "Back to the jam",
                    icon = Icons.Filled.PlayArrow,
                    onClick = onFollowHost,
                    modifier = Modifier.weight(1f),
                )
                GlassButton("Open $app", onClick = { onPlay(service) }, style = GlassButtonStyle.Secondary)
            }
            Text(
                if (sync.jamFollow == JamFollow.FAILED) {
                    "$app didn't start $what. Try Back to the jam, or open it in $app."
                } else {
                    "You're on a different track. Back to the jam plays $what where the host is."
                },
                style = MaterialTheme.typography.bodySmall,
                color = p.warning,
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
