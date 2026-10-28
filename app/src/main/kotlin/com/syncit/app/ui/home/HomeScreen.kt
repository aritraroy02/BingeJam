package com.syncit.app.ui.home

import android.content.Intent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncit.app.data.UpdateChecker
import com.syncit.app.social.ActivityLinks
import com.syncit.app.social.ActivityText
import com.syncit.app.ui.common.BrandWordmark
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.BadgedIconButton
import com.syncit.app.ui.glass.CountBadge
import com.syncit.app.ui.glass.Dot
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassSmallButton
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.social.ActivityAppBadge
import com.syncit.app.ui.social.ActivityThumb
import com.syncit.app.ui.theme.Glass
import com.syncit.app.ui.theme.Inter
import com.syncit.core.config.Brand
import com.syncit.core.model.MediaKind

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onEnterRoom: (String) -> Unit,
    onJoinCode: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val p = Glass.palette
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val update by viewModel.update.collectAsStateWithLifecycle()
    val me by viewModel.myProfile.collectAsStateWithLifecycle()
    val friends by viewModel.friends.collectAsStateWithLifecycle()
    val requests by viewModel.incomingRequestCount.collectAsStateWithLifecycle()
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
                    Avatar(
                        me?.displayName ?: "?",
                        size = 46.dp,
                        photoUrl = me?.photoUrl,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onOpenSettings),
                    )
                },
                actions = {
                    BadgedIconButton(Icons.Filled.Person, "Friends", requests, onOpenFriends)
                    GlassIconButton(Icons.Filled.Settings, "Settings", onOpenSettings)
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BrandWordmark()
                Text(
                    "Hey ${me?.displayName?.substringBefore(' ')?.ifBlank { null } ?: "there"}. ${Brand.TAGLINE}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = p.textSecondary,
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

                WatchPartyCard(
                    ui = ui,
                    onCreate = { viewModel.create(onEnterRoom) },
                    onToggleCode = viewModel::toggleCodeField,
                    onCodeChange = viewModel::onCodeChange,
                    onJoinCode = onJoinCode,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Friends", Modifier.weight(1f))
                    GlassSmallButton("Add friends", onClick = onOpenFriends, primary = false)
                }
                val rows = friends
                when {
                    rows == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = p.accent)
                    }
                    rows.isEmpty() -> GlassPanel(Modifier.fillMaxWidth()) {
                        Text("No friends yet", style = MaterialTheme.typography.titleMedium, color = p.textPrimary)
                        Text(
                            "Add friends by their username to see what they're watching and jump into their watch party.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary,
                        )
                        GlassButton("Find friends", onClick = onOpenFriends, icon = Icons.Filled.Add, modifier = Modifier.fillMaxWidth())
                    }
                    else -> GlassPanel(Modifier.fillMaxWidth(), spacing = 4.dp, contentPadding = PaddingValues(10.dp)) {
                        for (row in rows) {
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

/** Compact create/join card: the v1 room flow, still one tap away. */
@Composable
private fun WatchPartyCard(
    ui: HomeViewModel.UiState,
    onCreate: () -> Unit,
    onToggleCode: () -> Unit,
    onCodeChange: (String) -> Unit,
    onJoinCode: (String) -> Unit,
) {
    val p = Glass.palette
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("Watch party", style = MaterialTheme.typography.titleLarge, color = p.textPrimary)
        Text(
            "Everyone plays on their own phone. When anyone plays, pauses or skips, everyone follows.",
            style = MaterialTheme.typography.bodyMedium,
            color = p.textSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Start", onClick = onCreate, icon = Icons.Filled.PlayArrow, loading = ui.busy, modifier = Modifier.weight(1f))
            GlassButton(
                "Join with code",
                onClick = onToggleCode,
                style = GlassButtonStyle.Secondary,
                modifier = Modifier.weight(1.3f),
            )
        }
        if (ui.showCodeField) {
            GlassTextField(
                value = ui.code,
                onValueChange = onCodeChange,
                placeholder = "ROOM CODE",
                textStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 6.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (ui.codeValid) onJoinCode(ui.code) }),
                modifier = Modifier.fillMaxWidth(),
                trailing = {
                    GlassIconButton(Icons.AutoMirrored.Filled.ArrowForward, "Continue", onClick = { if (ui.codeValid) onJoinCode(ui.code) })
                },
            )
        }
        ui.lastRoom?.let { last ->
            GlassButton(
                text = "Back to room $last",
                icon = Icons.Filled.Refresh,
                style = GlassButtonStyle.Secondary,
                onClick = { onJoinCode(last) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
                Text(it, style = MaterialTheme.typography.labelMedium, color = p.accent2)
            }
            if (activity != null) {
                Spacer(Modifier.height(2.dp))
                if (activity.app.kind == MediaKind.MOVIE_TV && activity.app.joinable) {
                    GlassSmallButton(
                        if (row.joinRequested) "Asked to join" else "Ask to join",
                        onClick = onAskToJoin,
                        enabled = !row.joinRequested,
                    )
                } else {
                    GlassSmallButton("Open in ${activity.app.displayName}", onClick = onOpenApp, primary = false)
                }
            }
        }
        if (activity != null) {
            Spacer(Modifier.width(10.dp))
            ActivityThumb(activity)
        }
        Spacer(Modifier.width(6.dp))
        Box {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onChat),
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
                        Text(it, style = MaterialTheme.typography.labelMedium, color = p.accent2)
                    }
                }
            }
            if (activity.app.joinable) {
                GlassButton(
                    if (row.joinRequested) "Asked to join" else "Ask to join",
                    onClick = onAskToJoin,
                    enabled = !row.joinRequested,
                    icon = Icons.Filled.PlayArrow,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                GlassButton("Open in ${activity.app.displayName}", onClick = onOpenApp, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
            }
        }
    } else {
        Text(row.status, style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
    }
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
