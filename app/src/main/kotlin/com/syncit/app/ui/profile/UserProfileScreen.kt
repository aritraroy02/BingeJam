package com.syncit.app.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.syncit.app.R
import com.syncit.app.di.AppContainer
import com.syncit.app.social.ActivityText
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassBottomSheet
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassPanel
import com.syncit.app.ui.glass.GlassRow
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.safety.BlockDialog
import com.syncit.app.ui.safety.ReportSheet
import com.syncit.app.ui.social.ActivityThumb
import com.syncit.app.ui.theme.Glass
import com.syncit.core.model.Friend
import com.syncit.core.model.UserProfile
import com.syncit.core.social.ReportReason
import com.syncit.core.social.Safety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Someone's profile (2026-11-10): picture (tap for full screen), name, bio, and what you can do. */
class UserProfileViewModel(val uid: String, private val container: AppContainer) : ViewModel() {
    private val social = container.social

    enum class Relation { ME, FRIEND, INCOMING, REQUESTED, BLOCKED, NONE }

    data class State(val loaded: Boolean, val profile: UserProfile?, val relation: Relation, val friend: Friend?)

    private val _profile = social.profiles.observeProfile(uid).map { it to true }.catch { emit(null to true) }

    val state: StateFlow<State> = combine(
        _profile,
        social.friends.observeFriends().catch { emit(emptyList()) },
        social.friends.observeIncomingRequests().catch { emit(emptyList()) },
        social.friends.observeOutgoingRequests().catch { emit(emptyList()) },
        social.friends.observeBlocked().catch { emit(emptySet()) },
    ) { (profile, loaded), friends, incoming, outgoing, blocked ->
        val friend = friends.firstOrNull { it.profile.uid == uid }
        val relation = when {
            uid == social.auth.currentUid -> Relation.ME
            friend != null -> Relation.FRIEND
            uid in blocked -> Relation.BLOCKED
            incoming.any { it.fromUid == uid } -> Relation.INCOMING
            outgoing.any { it.toUid == uid } -> Relation.REQUESTED
            else -> Relation.NONE
        }
        State(loaded, profile ?: friend?.profile, relation, friend)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State(false, null, Relation.NONE, null))

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun statusLine(friend: Friend): String = ActivityText.statusLine(friend.presence, container.transport.serverClock.serverNowMs())

    fun add() = act("Couldn't send the request") {
        val username = state.value.profile?.username ?: return@act
        social.friends.sendRequest(username)
        _notice.value = "Request sent"
    }

    fun accept() = act("Couldn't accept") { social.friends.accept(uid) }

    fun cancel() = act("Couldn't cancel") { social.friends.cancel(uid) }

    fun unblock() = act("Couldn't unblock") { social.friends.unblock(uid) }

    fun unfriend() = act("Couldn't remove them") { social.friends.unfriend(uid) }

    fun block() = act("Couldn't block them") { social.friends.block(uid) }

    /** With their last messages to us when we're friends (read before blocking, which ends the chat). */
    fun report(reason: ReportReason, details: String?, block: Boolean) = act("Couldn't send the report") {
        val evidence = if (state.value.relation == Relation.FRIEND) {
            runCatching { social.dms.observe(uid).first().filter { it.fromUid == uid && it.watched == null } }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        social.safety.reportUser(uid, state.value.profile?.displayName.orEmpty(), reason, details, evidence.takeLast(Safety.MAX_EVIDENCE), blocked = block)
        if (block) social.friends.block(uid)
        _notice.value = if (block) "Reported and blocked" else "Thanks. Your report was sent."
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun act(failure: String, block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: failure
            } finally {
                _busy.value = false
            }
        }
    }
}

@Composable
fun UserProfileScreen(viewModel: UserProfileViewModel, onBack: () -> Unit, onMessage: (uid: String) -> Unit) {
    val p = Glass.palette
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var reporting by remember { mutableStateOf(false) }
    val profile = state.profile
    val relation = state.relation

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = profile?.username?.let { "@$it" } ?: "Profile",
                navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack) },
                actions = {
                    if (profile != null && relation != UserProfileViewModel.Relation.ME) {
                        GlassIconButton(Icons.Filled.MoreVert, "More", { menu = true }, plain = true)
                    }
                },
            )
            when {
                !state.loaded -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(color = p.accent)
                }
                profile == null -> Text(
                    "This account doesn't exist any more.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp)
                        .padding(top = 16.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val photo = profile.photoUrl
                    Avatar(
                        profile.displayName.ifEmpty { "?" },
                        size = 120.dp,
                        photoUrl = photo,
                        online = state.friend?.presence?.online,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(enabled = photo != null, role = Role.Image, onClickLabel = "View profile picture") { viewing = true },
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(profile.displayName, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary, textAlign = TextAlign.Center)
                        Text("@${profile.username}", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                    }
                    profile.bio?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = p.textPrimary, textAlign = TextAlign.Center)
                    }
                    state.friend?.let { f ->
                        Text(
                            "${viewModel.statusLine(f)} · Friends since ${since(f.since)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textTertiary,
                            textAlign = TextAlign.Center,
                        )
                        f.presence?.activity?.let { activity ->
                            GlassPanel(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ActivityThumb(activity, size = 40.dp)
                                    Text(ActivityText.activityLine(activity), style = MaterialTheme.typography.bodyMedium, color = p.textPrimary)
                                }
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (relation) {
                            UserProfileViewModel.Relation.ME -> Text("This is you.", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                            UserProfileViewModel.Relation.FRIEND ->
                                GlassButton("Message", onClick = { onMessage(profile.uid) }, modifier = Modifier.fillMaxWidth())
                            UserProfileViewModel.Relation.INCOMING ->
                                GlassButton("Accept friend request", onClick = viewModel::accept, loading = busy, modifier = Modifier.fillMaxWidth())
                            UserProfileViewModel.Relation.REQUESTED ->
                                GlassButton("Cancel request", onClick = viewModel::cancel, style = GlassButtonStyle.Secondary, loading = busy, modifier = Modifier.fillMaxWidth())
                            UserProfileViewModel.Relation.BLOCKED ->
                                GlassButton("Unblock", onClick = viewModel::unblock, style = GlassButtonStyle.Secondary, loading = busy, modifier = Modifier.fillMaxWidth())
                            UserProfileViewModel.Relation.NONE ->
                                GlassButton("Add friend", onClick = viewModel::add, loading = busy, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        NoticeBanner(notice, viewModel::dismissNotice, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())

        val first = profile?.displayName?.substringBefore(' ').orEmpty().ifEmpty { "them" }
        GlassBottomSheet(visible = menu, onDismiss = { menu = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (relation == UserProfileViewModel.Relation.FRIEND) {
                    GlassRow("Remove friend", icon = ImageVector.vectorResource(R.drawable.ic_exit), onClick = {
                        menu = false
                        confirm = "unfriend"
                    })
                }
                if (relation != UserProfileViewModel.Relation.BLOCKED) {
                    GlassRow("Block $first", icon = ImageVector.vectorResource(R.drawable.ic_block), onClick = {
                        menu = false
                        confirm = "block"
                    })
                }
                GlassRow("Report $first", icon = ImageVector.vectorResource(R.drawable.ic_flag), onClick = {
                    menu = false
                    reporting = true
                })
            }
        }
        ReportSheet(
            visible = reporting,
            title = "Report ${profile?.displayName.orEmpty()}",
            evidenceNote = if (relation == UserProfileViewModel.Relation.FRIEND) {
                "$first's last ${Safety.MAX_EVIDENCE} messages to you are sent with your report so it can be reviewed. $first isn't told."
            } else {
                "$first isn't told who reported them."
            },
            blockLabel = "Also block $first",
            busy = busy,
            onDismiss = { reporting = false },
            onSubmit = { reason, details, block ->
                reporting = false
                viewModel.report(reason, details, block)
            },
        )
    }
    if (viewing && profile?.photoUrl != null) PhotoViewer(profile.photoUrl!!, profile.displayName) { viewing = false }
    confirm?.let { action ->
        val name = profile?.displayName.orEmpty()
        BlockDialog(
            title = if (action == "block") "Block $name?" else "Remove $name?",
            message = if (action == "block") {
                "You won't be friends any more. They can't message you, send you requests or see your activity. You can unblock them from Friends."
            } else {
                "You won't be friends any more, and your chat closes until you add each other again."
            },
            confirm = if (action == "block") "Block" else "Remove",
            onConfirm = {
                confirm = null
                if (action == "block") viewModel.block() else viewModel.unfriend()
            },
            onDismiss = { confirm = null },
        )
    }
}

private fun since(ms: Long): String =
    if (ms <= 0L) "a while" else DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
