package com.syncit.app.ui.home

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.data.UpdateChecker
import com.syncit.app.social.ActivityLinks
import com.syncit.app.social.ActivityText
import com.syncit.app.ui.common.rememberArtwork
import com.syncit.app.ui.glass.SkeletonRows
import com.syncit.app.ui.glass.rememberReduceMotion
import com.syncit.app.ui.glass.staggerIn
import com.syncit.app.ui.social.color
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.CountBadge
import com.syncit.app.ui.glass.Dot
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.social.ActivityAppBadge
import com.syncit.app.ui.social.ActivityThumb
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEnterRoom: (String) -> Unit,
    onStartRoomWith: (code: String, friendUid: String) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val p = Glass.palette
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val me by viewModel.myProfile.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sheetUid by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshLastRoom() }

    val openActivity: (FriendRow) -> Unit = { row ->
        val activity = row.activity
        if (activity != null && !ActivityLinks.open(context, activity)) {
            viewModel.showMessage("${activity.app.displayName} isn't installed")
        }
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                navigation = {
                    // Instagram-style top bar: your picture and plain icons.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(
                            me?.displayName ?: "?",
                            size = 36.dp,
                            photoUrl = me?.photoUrl,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(role = Role.Button, onClick = onOpenProfile)
                                .semantics { contentDescription = "Your profile" },
                        )
                    }
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // What friends are playing right now, up top: the quickest way into something.
                val live = friends.orEmpty().filter { it.online && it.activity != null }
                if (live.isNotEmpty()) {
                    LiveNowRow(live, onOpen = { sheetUid = it.uid }, modifier = Modifier.staggerIn(0))
                }

                update?.let { available ->
                    GlassPanel {
                        Text("${Brand.NAME} ${available.version} is available", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        available.notes?.lineSequence()?.firstOrNull { it.isNotBlank() }?.let {
                            Text(it.trimStart('-', '*', ' '), style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                        }
                        GlassButton(
                            text = "Download update",
                            icon = Icons.Filled.Refresh,
                            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, UpdateChecker.DOWNLOAD_URL.toUri())) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp).staggerIn(4)) {
                    val online = friends?.count { it.online } ?: 0
                    SectionLabel(if (online > 0) "Friends · $online online" else "Friends", Modifier.weight(1f))
                    GlassSmallButton("Add friends", onClick = onOpenFriends, primary = false)
                }
                val rows = friends
                when {
                    rows == null -> GlassPanel(Modifier.fillMaxWidth().staggerIn(5), spacing = 0.dp, contentPadding = PaddingValues(10.dp)) {
                        SkeletonRows(3)
                    }
                    rows.isEmpty() -> GlassPanel(Modifier.fillMaxWidth().staggerIn(5)) {
                        Text("Better with friends", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        Text(
                            "Add friends by their username. You'll see what they're watching or listening to, " +
                                "and can jump into their watch party or jam with one tap.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary,
                        )
                        GlassButton("Find friends", onClick = onOpenFriends, icon = Icons.Filled.Add, modifier = Modifier.fillMaxWidth())
                    }
                    else -> GlassPanel(Modifier.fillMaxWidth().staggerIn(5), spacing = 4.dp, contentPadding = PaddingValues(10.dp)) {
                        // Online first, then whoever was around most recently (the VM's order).
                        for (row in rows.sortedByDescending { it.online }) {
                            FriendRowView(
                                row = row,
                                onClick = { sheetUid = row.uid },
                                onChat = { onOpenChat(row.uid) },
                                onAskToJoin = { viewModel.askToJoin(row) },
                                onOpenApp = { openActivity(row) },
                            )
                        }
                    }
                }
            }
        }

        val selected = friends?.firstOrNull { it.uid == sheetUid }
        GlassBottomSheet(visible = selected != null, onDismiss = { sheetUid = null }) {
            if (selected != null) {
                FriendSheetContent(
                    row = selected,
                    onMessage = {
                        sheetUid = null
                        onOpenChat(selected.uid)
                    },
                    starting = ui.busy,
                    onStartRoom = {
                        viewModel.startRoomWith(selected) { code, uid ->
                            sheetUid = null
                            onStartRoomWith(code, uid)
                        }
                    },
                    onAskToJoin = { viewModel.askToJoin(selected) },
                    onOpenApp = { openActivity(selected) },
                    onUnfriend = {
                        sheetUid = null
                        viewModel.unfriend(selected.uid)
                    },
                    onBlock = {
                        sheetUid = null
                        viewModel.block(selected.uid)
                    },
                )
            }
        }

        NoticeBanner(ui.message, viewModel::dismissMessage, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * Friends playing something right now, as big artwork cards in a swipeable row. A pulsing
 * "LIVE" dot and the friend's face on each card; tap opens the friend sheet (join / message).
 */
@Composable
private fun LiveNowRow(rows: List<FriendRow>, onOpen: (FriendRow) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            LivePulse()
            Spacer(Modifier.width(8.dp))
            SectionLabel("Live now", Modifier.weight(1f))
        }
        // Bleed the row to the screen edges so cards scroll under the gutters.
        LazyRow(
            modifier = Modifier.layout { measurable, constraints ->
                val gutter = 20.dp.roundToPx()
                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + gutter * 2))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-gutter, 0) }
            },
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows, key = { it.uid }) { row -> LiveCard(row, onClick = { onOpen(row) }) }
        }
    }
}

@Composable
private fun LiveCard(row: FriendRow, onClick: () -> Unit) {
    val p = Glass.palette
    val activity = row.activity ?: return
    val art = rememberArtwork(activity.artworkJpeg)
    val shape = RoundedCornerShape(22.dp)
    val name = row.friend.profile.displayName
    val what = activity.title?.takeIf { it.isNotBlank() } ?: activity.app.displayName
    Box(
        Modifier
            .width(168.dp)
            .height(220.dp)
            .clip(shape)
            .background(Brush.linearGradient(listOf(activity.app.color(), activity.app.color().copy(alpha = 0.45f))))
            .border(1.dp, Brush.linearGradient(listOf(p.strokeTop, p.strokeBottom)), shape)
            .clickable(onClickLabel = "Open $name", role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {},
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        // Scrim so white text stays readable on any artwork.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.10f), Color.Black.copy(alpha = 0.78f)))))
        Avatar(
            name,
            size = 36.dp,
            online = true,
            photoUrl = row.friend.profile.photoUrl,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ActivityAppBadge(activity.app, onArtwork = true)
            Text(what, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                (if (activity.playing) "" else "Paused · ") + name.substringBefore(' '),
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/** A red dot with a ring that pulses outward, like a live broadcast. Still with reduced motion. */
@Composable
private fun LivePulse() {
    val red = Color(0xFFFF4D6D)
    val reduce = rememberReduceMotion()
    val t = if (reduce) {
        0f
    } else {
        val v by rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
            label = "live-t",
        )
        v
    }
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(14.dp)
                .graphicsLayer {
                    scaleX = 0.6f + t * 0.8f
                    scaleY = 0.6f + t * 0.8f
                    alpha = (1f - t) * 0.6f
                }
                .clip(CircleShape)
                .background(red),
        )
        Box(Modifier.size(8.dp).clip(CircleShape).background(red))
    }
}

@Composable
private fun FriendRowView(
    row: FriendRow,
    onClick: () -> Unit,
    onChat: () -> Unit,
    onAskToJoin: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val p = Glass.palette
    val activity = row.activity
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.friend.profile.displayName, size = 46.dp, online = row.online, photoUrl = row.friend.profile.photoUrl)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                row.friend.profile.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activity != null) {
                    Dot(if (activity.playing) p.success else p.warning)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    row.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activity != null) p.textPrimary else p.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ActivityText.watchPartyLabel(activity)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = p.accentText)
            }
            if (activity != null) {
                Spacer(Modifier.height(2.dp))
                // Watch parties and jams alike: the room is the point. "Open in …" lives in the sheet.
                val ask = row.askLabel
                if (ask != null) {
                    GlassSmallButton(ask, onClick = onAskToJoin, enabled = !row.joinRequested)
                } else {
                    GlassSmallButton("Open in ${activity.app.displayName}", onClick = onOpenApp, primary = false)
                }
            }
        }
        if (activity != null) {
            Spacer(Modifier.width(10.dp))
            ActivityThumb(activity, size = 40.dp)
        }
        Spacer(Modifier.width(6.dp))
        Box {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onChat),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "Message ${row.friend.profile.displayName}", tint = p.textSecondary, modifier = Modifier.size(20.dp))
            }
            CountBadge(row.unread, Modifier.align(Alignment.TopEnd))
        }
    }
}

@Composable
private fun FriendSheetContent(
    row: FriendRow,
    starting: Boolean,
    onStartRoom: () -> Unit,
    onMessage: () -> Unit,
    onAskToJoin: () -> Unit,
    onOpenApp: () -> Unit,
    onUnfriend: () -> Unit,
    onBlock: () -> Unit,
) {
    val p = Glass.palette
    val profile = row.friend.profile
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(profile.displayName, size = 64.dp, online = row.online, photoUrl = profile.photoUrl)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary)
            Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
        }
    }
    profile.bio?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary) }
    val activity = row.activity
    if (activity != null) {
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityThumb(activity, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActivityAppBadge(activity.app)
                    Text(row.status, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                    ActivityText.watchPartyLabel(activity)?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = p.accentText)
                    }
                }
            }
            val ask = row.askLabel
            if (ask != null) {
                GlassButton(
                    ask,
                    onClick = onAskToJoin,
                    enabled = !row.joinRequested,
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // Jams: still handy to just look the song up on your own (no room, no sync).
            if (ask == null || row.isJam) {
                GlassButton("Open in ${activity.app.displayName}", onClick = onOpenApp, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Text(row.status, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
    }
    GlassButton(
        "Start a room with ${profile.displayName.substringBefore(' ').ifEmpty { "them" }}",
        onClick = onStartRoom,
        icon = Icons.Filled.Add,
        loading = starting,
        style = if (row.askLabel != null) GlassButtonStyle.Secondary else GlassButtonStyle.Primary,
        modifier = Modifier.fillMaxWidth(),
    )
    GlassButton("Message", onClick = onMessage, icon = Icons.AutoMirrored.Filled.Send, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        GlassButton("Unfriend", onClick = { confirm = "unfriend" }, style = GlassButtonStyle.Secondary, modifier = Modifier.weight(1f))
        GlassButton("Block", onClick = { confirm = "block" }, style = GlassButtonStyle.Danger, modifier = Modifier.weight(1f))
    }

    confirm?.let { action ->
        val block = action == "block"
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (block) "Block ${profile.displayName}?" else "Remove ${profile.displayName}?") },
            text = {
                Text(
                    if (block) {
                        "They won't be able to message you, send requests or see your activity. You can unblock them from Friends."
                    } else {
                        "You'll stop seeing each other's activity. You can add each other again later."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (block) onBlock() else onUnfriend()
                }) { Text(if (block) "Block" else "Remove", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
