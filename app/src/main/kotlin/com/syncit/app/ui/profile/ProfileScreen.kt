package com.syncit.app.ui.profile

import com.syncit.core.model.Presence
import com.syncit.app.ui.glass.GlassSegmented
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.social.ActivityText
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.social.ActivityThumb
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.Activity
import com.syncit.core.model.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Your own profile as friends see it: picture, name, @username, bio, friends, what you're sharing. */
class ProfileViewModel(container: AppContainer) : ViewModel() {
    private val social = container.social
    private val prefs = container.prefs

    /** Invisible: friends see you offline and nothing you play is shared. */
    val invisible: StateFlow<Boolean> = prefs.invisible
    val sharedApps = prefs.sharedApps

    fun setInvisible(value: Boolean) = prefs.setInvisible(value)

    /** Do not disturb: friends see a red dot; pop-ups here are silent. */
    val dnd: StateFlow<Boolean> = prefs.dnd

    /** Online, Do not disturb or Invisible (like the PC's status menu). */
    fun setStatus(status: Int) {
        prefs.setInvisible(status == 2)
        prefs.setDnd(status == 1)
    }

    /** Our presence as friends read it: this phone's activity, a PC's game or music. */
    val own: StateFlow<Presence?> = social.presence.observeOwn()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profile: StateFlow<UserProfile?> = social.profiles.observeMyProfile()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val friendCount: StateFlow<Int?> = social.friends.observeFriends()
        .map { it.size }
        .catch { emit(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sharingNow: StateFlow<Activity?> = social.activityTracker.current

    fun describe(activity: Activity): String = ActivityText.activityLine(activity)
}

/** One activity in "What friends see", like a Discord profile card. */
@Composable
private fun ActivityCard(label: String, content: @Composable () -> Unit) {
    val p = Glass.palette
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.raised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.textSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBack: (() -> Unit)?,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onInvite: () -> Unit = {},
) {
    val p = Glass.palette
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val friendCount by viewModel.friendCount.collectAsStateWithLifecycle()
    val sharingNow by viewModel.sharingNow.collectAsStateWithLifecycle()
    val invisible by viewModel.invisible.collectAsStateWithLifecycle()
    val dnd by viewModel.dnd.collectAsStateWithLifecycle()
    val own by viewModel.own.collectAsStateWithLifecycle()
    val shared by viewModel.sharedApps.collectAsStateWithLifecycle()
    var viewingPhoto by remember { mutableStateOf(false) }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = profile?.username?.let { "@$it" } ?: "Profile",
                navigation = onBack?.let { back -> { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", back) } },
                actions = { GlassIconButton(Icons.Filled.Settings, "Settings", onOpenSettings, plain = true) },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp + LocalBottomBarInset.current),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    val photo = profile?.photoUrl
                    Avatar(
                        profile?.displayName ?: "?",
                        size = 84.dp,
                        photoUrl = photo,
                        modifier = Modifier.clip(CircleShape).clickable(enabled = photo != null, onClickLabel = "View profile picture") { viewingPhoto = true },
                    )
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            friendCount?.toString() ?: "–",
                            style = MaterialTheme.typography.titleMedium,
                            color = p.textPrimary,
                        )
                        Text(if (friendCount == 1) "friend" else "friends", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        profile?.displayName.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = p.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    profile?.bio?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Edit profile", onClick = onEditProfile, style = GlassButtonStyle.Secondary, modifier = Modifier.weight(1f))
                    GlassButton(
                        "Invite friends",
                        onClick = onInvite,
                        style = GlassButtonStyle.Secondary,
                        enabled = profile != null,
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionLabel("Your status", Modifier.padding(top = 8.dp))
                GlassSegmented(
                    options = listOf("Online", "Do not disturb", "Invisible"),
                    selected = if (invisible) 2 else if (dnd) 1 else 0,
                    onSelect = viewModel::setStatus,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        invisible -> "You look offline to friends and nothing you play is shared."
                        dnd -> "Friends see a red dot. Notifications here arrive silently."
                        else -> "Friends see you online and what you share."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = p.textSecondary,
                )

                // What friends see, read back from the server (this phone, and any PC signed in).
                SectionLabel("What friends see", Modifier.padding(top = 8.dp))
                GlassPanel(Modifier.fillMaxWidth(), spacing = 12.dp) {
                    val presence = own
                    val line = when {
                        invisible -> "Offline"
                        presence == null -> "Online"
                        else -> ActivityText.statusLine(presence, 0L)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(profile?.displayName ?: "?", size = 40.dp, photoUrl = profile?.photoUrl, online = !invisible)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(profile?.displayName.orEmpty(), style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(line, style = MaterialTheme.typography.bodySmall, color = if (dnd && !invisible) p.danger else p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!invisible && presence != null && presence.online) {
                        presence.game?.let { game ->
                            ActivityCard(label = "Playing on PC") {
                                Box(
                                    Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(p.raised),
                                    contentAlignment = Alignment.Center,
                                ) { Text(game.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = p.textSecondary) }
                                Spacer(Modifier.width(12.dp))
                                Text(game, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        presence.activity?.let { a ->
                            ActivityCard(label = if (a.onComputer) "On PC" else "On this phone") {
                                ActivityThumb(a, size = 48.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(viewModel.describe(a), style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                            }
                        }
                    }
                    if (!invisible && presence?.activity == null && presence?.game == null) {
                        Text(
                            if (shared.isEmpty()) "You're not sharing any apps. Pick them in Settings → Activity sharing." else "Nothing playing in a shared app right now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textSecondary,
                        )
                    }
                }
            }
        }
    }
    val photo = profile?.photoUrl
    if (viewingPhoto && photo != null) PhotoViewer(photo, profile?.displayName.orEmpty()) { viewingPhoto = false }
}
