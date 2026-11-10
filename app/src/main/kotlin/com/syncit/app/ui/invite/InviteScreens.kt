package com.syncit.app.ui.invite

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.syncit.app.di.AppContainer
import com.syncit.app.ui.chat.copyToClipboard
import com.syncit.app.ui.chat.systemShowsCopies
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassButton
import com.syncit.app.ui.glass.GlassButtonStyle
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTopBar
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.theme.Glass
import com.syncit.core.config.Brand
import com.syncit.core.config.FriendLinks
import com.syncit.core.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Our personal invite link and its QR code (2026-11-10). */
class InviteViewModel(container: AppContainer) : ViewModel() {
    private val invites = container.social.invites

    val profile: StateFlow<UserProfile?> = container.social.profiles.observeMyProfile()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val token = MutableStateFlow<String?>(null)

    /** Null until both the username and the token are loaded. */
    val link: StateFlow<String?> = combine(profile, token) { p, t -> if (p != null && t != null) FriendLinks.shareLink(p.username, t) else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    init {
        load { invites.myToken() }
    }

    fun reset() = load {
        invites.resetToken().also { _notice.value = "New link ready. Old links now send you a request instead." }
    }

    fun showNotice(text: String) {
        _notice.value = text
    }

    fun dismissNotice() {
        _notice.value = null
    }

    private fun load(get: suspend () -> String) {
        viewModelScope.launch {
            try {
                token.value = get()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = "Couldn't load your invite link. Check your connection."
            }
        }
    }
}

/**
 * Invite friends: QR code, Share and Copy. With [welcome] it's the "Bring a friend" step shown once
 * after setup, with Skip instead of a back arrow.
 */
@Composable
fun InviteScreen(viewModel: InviteViewModel, welcome: Boolean, onDone: () -> Unit) {
    val p = Glass.palette
    val context = LocalContext.current
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val link by viewModel.link.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val share = {
        val current = link
        val me = profile
        if (current != null && me != null) {
            val text = FriendLinks.shareText(me.username, current.substringAfterLast('/'))
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Invite a friend"))
        }
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(
                title = if (welcome) "Bring a friend" else "Invite friends",
                navigation = if (welcome) {
                    null
                } else {
                    { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onDone) }
                },
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    if (welcome) {
                        "${Brand.NAME} needs someone on the other couch. Send your link to a friend: when they open it, you're friends and can start a room together."
                    } else {
                        "Show this code or send your link. Whoever opens it becomes your friend straight away."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // Always dark on white, whatever the theme: that's what phone cameras read best.
                Box(
                    Modifier
                        .fillMaxWidth(0.72f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val current = link
                    if (current == null) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    } else {
                        QrCode(current, Modifier.fillMaxSize().semantics { contentDescription = "QR code for your invite link" })
                    }
                }
                profile?.let { me ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me.displayName, size = 40.dp, photoUrl = me.photoUrl)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(me.displayName, style = MaterialTheme.typography.titleSmall, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("@${me.username}", style = MaterialTheme.typography.bodySmall, color = p.textSecondary)
                        }
                    }
                }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Share link", onClick = share, icon = Icons.Filled.Share, enabled = link != null, modifier = Modifier.fillMaxWidth())
                    GlassButton(
                        "Copy link",
                        onClick = {
                            link?.let { if (copyToClipboard(context, it) && !systemShowsCopies()) viewModel.showNotice("Link copied") }
                        },
                        style = GlassButtonStyle.Secondary,
                        enabled = link != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (welcome) {
                        GlassButton("Skip for now", onClick = onDone, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (!welcome) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { confirmReset = true }, enabled = link != null) {
                        Text("Reset link", color = p.textSecondary)
                    }
                    Text(
                        "Shared it somewhere you shouldn't have? Resetting makes old links send you a friend request instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        "You can find this again under Chats, +.",
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        NoticeBanner(notice, viewModel::dismissNotice, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset your invite link?") },
            text = { Text("Links and QR codes you've shared stop adding friends automatically. People who open them can still send you a request.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.reset()
                }) { Text("Reset", color = p.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

/** The QR code of [text], drawn module by module so it stays sharp at any size. */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { encodeQr(text) }
    Canvas(modifier) {
        val n = matrix.width
        val cell = size.minDimension / n
        val origin = Offset((size.width - cell * n) / 2f, (size.height - cell * n) / 2f)
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix[x, y]) {
                    // A hair of overlap so no seams show between neighbouring modules.
                    drawRect(Color.Black, Offset(origin.x + x * cell, origin.y + y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}

private fun encodeQr(text: String): BitMatrix = QRCodeWriter().encode(
    text,
    BarcodeFormat.QR_CODE,
    0,
    0,
    mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.CHARACTER_SET to "UTF-8"),
)

/** Someone's invite link, opened on this phone (2026-11-10). */
class FriendInviteViewModel(private val invite: FriendLinks.Invite, private val container: AppContainer) : ViewModel() {
    private val social = container.social

    sealed interface State {
        data object Loading : State
        data object NotFound : State
        data class Ready(val profile: UserProfile, val yourself: Boolean, val friends: Boolean, val sent: Boolean) : State
    }

    private val looked = MutableStateFlow<UserProfile?>(null)
    private val lookupDone = MutableStateFlow(false)
    private val sent = MutableStateFlow(false)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val friendUids = social.friends.observeFriends().map { list -> list.map { it.profile.uid }.toSet() }.catch { emit(emptySet()) }

    val state: StateFlow<State> = combine(looked, lookupDone, friendUids, sent) { profile, done, friends, wasSent ->
        when {
            !done -> State.Loading
            profile == null -> State.NotFound
            else -> State.Ready(profile, yourself = profile.uid == social.auth.currentUid, friends = profile.uid in friends, sent = wasSent)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    init {
        viewModelScope.launch {
            looked.value = try {
                social.invites.lookUp(invite.username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            lookupDone.value = true
        }
    }

    fun add() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                social.friends.sendRequest(invite.username, via = invite.token)
                sent.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "Couldn't add them. Try again."
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }
}

@Composable
fun FriendInviteScreen(viewModel: FriendInviteViewModel, onClose: () -> Unit, onMessage: (uid: String) -> Unit) {
    val p = Glass.palette
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GlassTopBar(title = "Invite", navigation = { GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClose) })
            Column(
                Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val s = state) {
                    FriendInviteViewModel.State.Loading -> CircularProgressIndicator(color = p.accent)
                    FriendInviteViewModel.State.NotFound -> {
                        Text("This invite doesn't work any more", style = MaterialTheme.typography.titleMedium, color = p.textPrimary, textAlign = TextAlign.Center)
                        Text(
                            "The username may have changed. Ask your friend for a new link, or search their username in Friends.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = p.textSecondary,
                            textAlign = TextAlign.Center,
                        )
                        GlassButton("Close", onClick = onClose, style = GlassButtonStyle.Secondary)
                    }
                    is FriendInviteViewModel.State.Ready -> {
                        Avatar(s.profile.displayName, size = 88.dp, photoUrl = s.profile.photoUrl)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.profile.displayName, style = MaterialTheme.typography.headlineSmall, color = p.textPrimary, textAlign = TextAlign.Center)
                            Text("@${s.profile.username}", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary)
                        }
                        when {
                            s.yourself -> {
                                Text("This is your own invite link. Send it to a friend.", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, textAlign = TextAlign.Center)
                                GlassButton("Close", onClick = onClose, style = GlassButtonStyle.Secondary)
                            }
                            s.friends -> {
                                Text("You're friends. Say hi or start a room.", style = MaterialTheme.typography.bodyMedium, color = p.textSecondary, textAlign = TextAlign.Center)
                                GlassButton("Message ${s.profile.displayName.substringBefore(' ')}", onClick = { onMessage(s.profile.uid) }, modifier = Modifier.fillMaxWidth())
                            }
                            s.sent -> {
                                Text(
                                    "Request sent. You'll be friends as soon as it's accepted.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = p.textSecondary,
                                    textAlign = TextAlign.Center,
                                )
                                GlassButton("Close", onClick = onClose, style = GlassButtonStyle.Secondary)
                            }
                            else -> {
                                Text(
                                    "${s.profile.displayName.substringBefore(' ')} invited you to be friends on ${Brand.NAME}.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = p.textSecondary,
                                    textAlign = TextAlign.Center,
                                )
                                GlassButton("Add friend", onClick = viewModel::add, loading = busy, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                GlassButton("Not now", onClick = onClose, style = GlassButtonStyle.Secondary, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        NoticeBanner(notice, viewModel::dismissNotice, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
