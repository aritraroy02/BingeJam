package com.syncit.app.ui.home

import com.syncit.core.lookup.TrendingRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.focus.focusRequester
import com.syncit.app.ui.glass.GlassIconButton
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.syncit.app.data.WatchlistItem
import com.syncit.app.social.ActivityLinks
import com.syncit.app.social.ActivityText
import com.syncit.app.ui.common.rememberArtwork
import com.syncit.app.ui.glass.rememberReduceMotion
import com.syncit.app.ui.glass.staggerIn
import com.syncit.app.ui.social.color
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.SyncDots
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.social.ActivityAppBadge
import com.syncit.app.ui.social.ActivityThumb
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand
import com.syncit.core.lookup.TrendingTitle
import com.syncit.core.model.StreamingService

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEnterRoom: (String) -> Unit,
    onStartRoomWith: (code: String, friendUid: String) -> Unit,
    onWatchWithFriends: (code: String) -> Unit,
    onOpenChat: (String) -> Unit,
    liveRooms: List<LiveRoom> = emptyList(),
    onOpenLiveRoom: (LiveRoom) -> Unit = {},
) {
    val p = Glass.palette
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var sheetUid by rememberSaveable { mutableStateOf<String?>(null) }
    val trending by viewModel.trending.collectAsStateWithLifecycle()
    val trendingFilter by viewModel.trendingFilter.collectAsStateWithLifecycle()
    val watchlist by viewModel.watchlist.collectAsStateWithLifecycle()
    var pick by remember { mutableStateOf<Pair<TrendingTitle, StreamingService>?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshLastRoom() }
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = searching) {
        searching = false
        viewModel.setSearchQuery("")
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (searching) {
                TitleSearch(
                    query = searchQuery,
                    results = searchResults,
                    onQuery = viewModel::setSearchQuery,
                    onClose = {
                        searching = false
                        viewModel.setSearchQuery("")
                    },
                    onOpen = { title, service -> pick = title to service },
                )
            } else {
            GlassTopBar(title = "Watch", actions = {
                GlassIconButton(Icons.Filled.Search, "Search titles", { searching = true })
            })
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 2026-11-25 redesign: the room your friends are in first (one tap to Join), then what
                // each friend is playing, then trending.
                liveRooms.firstOrNull()?.let { first ->
                    LiveRoomHero(first, onJoin = { onOpenLiveRoom(first) }, modifier = Modifier.staggerIn(0))
                }
                val live = friends.orEmpty().filter { it.online && it.activity != null }
                if (live.isNotEmpty()) {
                    LiveNowRow(live, onOpen = { sheetUid = it.uid }, modifier = Modifier.staggerIn(0))
                }
                if (liveRooms.size > 1) {
                    LiveRoomsRow(liveRooms.drop(1), onOpen = onOpenLiveRoom, modifier = Modifier.staggerIn(0))
                }

                TrendingSection(
                    rows = trending,
                    filter = trendingFilter,
                    onFilterChange = viewModel::setTrendingFilter,
                    onOpen = { title, service -> pick = title to service },
                    modifier = Modifier.staggerIn(1),
                )

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
            }
            }
        }

        val picked = pick
        GlassBottomSheet(visible = picked != null, onDismiss = { pick = null }) {
            if (picked != null) {
                val (title, service) = picked
                TrendingSheetContent(
                    title = title,
                    service = service,
                    busy = ui.busy,
                    saved = watchlist.any { it.key == WatchlistItem.keyOf(title) },
                    onToggleSaved = { viewModel.toggleWatchlist(title, service) },
                    available = servicesFor(title, trending),
                    onPickService = { other -> pick = title to other },
                    onWithFriends = {
                        viewModel.watchWithFriends(title, service) { code ->
                            pick = null
                            onWatchWithFriends(code)
                        }
                    },
                    onAlone = {
                        viewModel.watchAlone(title, service) { link ->
                            pick = null
                            if (!openTrending(context, service, link, title)) {
                                viewModel.showMessage("${service.displayName} isn't installed")
                            }
                        }
                    },
                )
            }
        }

        FriendSheet(
            viewModel = viewModel,
            uid = sheetUid,
            onDismiss = { sheetUid = null },
            onOpenChat = onOpenChat,
            onStartRoomWith = onStartRoomWith,
        )

        NoticeBanner(
            ui.message,
            viewModel::dismissMessage,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = LocalBottomBarInset.current),
        )
    }
}

/**
 * Friends playing something right now, as big artwork cards in a swipeable row. A pulsing
 * "LIVE" dot and the friend's face on each card; tap opens the friend sheet (join / message).
 */
@Composable
private fun LiveNowRow(rows: List<FriendRow>, onOpen: (FriendRow) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            SyncDots()
            Spacer(Modifier.width(8.dp))
            SectionLabel("Friends watching", Modifier.weight(1f))
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
    val shape = RoundedCornerShape(16.dp)
    val name = row.friend.profile.displayName
    val what = activity.title?.takeIf { it.isNotBlank() } ?: activity.app.displayName
    Box(
        Modifier
            .width(168.dp)
            .height(220.dp)
            .clip(shape)
            .background(activity.app.color())
            .border(1.dp, p.hairline, shape)
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
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
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

/**
 * The friend sheet (picture, activity, Ask to join, Start a room, Message, Unfriend/Block) for [uid];
 * hidden while [uid] is null. Used by Home's "Live now" and Chats' long-press "View profile".
 * [viewModel] is the activity-scoped Home view model, so both places share its rows and actions;
 * its notices show in `ui.message`.
 */
@Composable
internal fun FriendSheet(
    viewModel: HomeViewModel,
    uid: String?,
    onDismiss: () -> Unit,
    onOpenChat: (String) -> Unit,
    onStartRoomWith: (code: String, friendUid: String) -> Unit,
) {
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val selected = friends?.firstOrNull { it.uid == uid }
    GlassBottomSheet(visible = selected != null, onDismiss = onDismiss) {
        if (selected != null) {
            FriendSheetContent(
                row = selected,
                onMessage = {
                    onDismiss()
                    onOpenChat(selected.uid)
                },
                starting = ui.busy,
                onStartRoom = {
                    viewModel.startRoomWith(selected) { code, friendUid ->
                        onDismiss()
                        onStartRoomWith(code, friendUid)
                    }
                },
                onAskToJoin = { viewModel.askToJoin(selected) },
                onOpenApp = {
                    val activity = selected.activity
                    if (activity != null && !ActivityLinks.open(context, activity)) {
                        viewModel.showMessage("${activity.app.displayName} isn't installed")
                    }
                },
                onUnfriend = {
                    onDismiss()
                    viewModel.unfriend(selected.uid)
                },
                onBlock = {
                    onDismiss()
                    viewModel.block(selected.uid)
                },
            )
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
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(profile.displayName, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary)
            Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
        }
    }
    profile.bio?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary) }
    val activity = row.activity
    if (activity != null) {
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityThumb(activity, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ActivityAppBadge(activity.app)
                    Text(ActivityText.activityLine(activity), style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
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
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** Home's title search: a field, then titles that stream on each service; tap opens the title sheet. */
@Composable
private fun TitleSearch(
    query: String,
    results: List<TrendingRow>?,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    onOpen: (TrendingTitle, StreamingService) -> Unit,
) {
    val p = Glass.palette
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Close search", onClose)
            Spacer(Modifier.width(8.dp))
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            com.syncit.app.ui.glass.GlassTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = "Search movies and series",
                leadingIcon = Icons.Filled.Search,
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(horizontal = 16.dp)
                .padding(bottom = 24.dp + LocalBottomBarInset.current),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                query.trim().length < 2 -> Text("Find a title on Netflix, JioHotstar or Prime Video", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, modifier = Modifier.padding(top = 8.dp))
                results == null -> CircularProgressIndicator(color = p.accent, modifier = Modifier.padding(top = 16.dp))
                results.isEmpty() -> Text("Nothing streaming with that name on Netflix, JioHotstar or Prime Video", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, modifier = Modifier.padding(top = 8.dp))
                else -> for (row in results) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(row.service.displayName)
                        for (title in row.titles) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(title, row.service) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(p.raised)) {
                                    title.posterUrl?.let { coil3.compose.AsyncImage(model = it, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title.title, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        listOfNotNull(title.year?.toString(), if (title.type == com.syncit.core.lookup.TitleType.SERIES) "Series" else "Movie", title.rating?.let { "%.1f".format(it) }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = p.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
