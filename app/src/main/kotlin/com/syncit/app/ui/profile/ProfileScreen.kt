package com.syncit.app.ui.profile

import android.content.Intent
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.syncit.app.ui.glass.GlassSwitchRow
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.SectionLabel
import com.syncit.app.ui.nav.LocalBottomBarInset
import com.syncit.app.ui.social.ActivityThumb
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand
import com.syncit.core.config.RoomLinks
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

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBack: (() -> Unit)?,
    onEditProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val p = Glass.palette
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val friendCount by viewModel.friendCount.collectAsStateWithLifecycle()
    val sharingNow by viewModel.sharingNow.collectAsStateWithLifecycle()
    val invisible by viewModel.invisible.collectAsStateWithLifecycle()
    val shared by viewModel.sharedApps.collectAsStateWithLifecycle()

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
                    Avatar(profile?.displayName ?: "?", size = 84.dp, photoUrl = profile?.photoUrl)
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
                        "Share profile",
                        onClick = {
                            val username = profile?.username ?: return@GlassButton
                            val text = "Add me on ${Brand.NAME}: @$username\nhttps://${RoomLinks.WEB_HOST}"
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, "Share your profile"))
                        },
                        style = GlassButtonStyle.Secondary,
                        enabled = profile != null,
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionLabel("Sharing now", Modifier.padding(top = 8.dp))
                GlassPanel(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    GlassSwitchRow(
                        title = "Invisible",
                        subtitle = "Friends see you as offline and nothing you play is shared.",
                        checked = invisible,
                        onCheckedChange = viewModel::setInvisible,
                    )
                    val now = sharingNow
                    if (invisible || now == null) {
                        Text(
                            when {
                                invisible -> "You're invisible."
                                shared.isEmpty() -> "You're not sharing any apps. Pick them in Settings → Activity sharing."
                                else -> "Nothing playing in a shared app right now."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textSecondary,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ActivityThumb(now, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(viewModel.describe(now), style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                        }
                    }
                }
            }
        }
    }
}
