package com.syncit.app.ui.channels

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.syncit.app.di.AppContainer
import com.syncit.app.ui.glass.Avatar
import com.syncit.app.ui.glass.GlassIconButton
import com.syncit.app.ui.glass.GlassScreen
import com.syncit.app.ui.glass.GlassTextField
import com.syncit.app.ui.glass.NoticeBanner
import com.syncit.app.ui.theme.Glass
import com.syncit.core.social.GroupChannels
import com.syncit.core.social.VoiceOccupant
import com.syncit.core.voice.VoiceState
import com.syncit.core.voice.groupVoiceRoom
import com.syncit.voice.R
import com.syncit.voice.ScreenShareViewer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A group's voice channel on the phone (2026-11-25, like the PC's voice stage): who's in it (speaking
 * rings, mute / deafen, LIVE), the call timer and status, Join voice / Mute / Deafen / Leave, and a
 * PC's Go Live watched right here (WebRTC, [ScreenShareViewer]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceChannelViewModel(val groupId: String, val channelId: String, private val container: AppContainer) : ViewModel() {
    private val social = container.social
    private val channels = social.channels
    val room: String = groupVoiceRoom(groupId, channelId)
    val myUid: String? get() = social.auth.currentUid

    data class Ui(val groupName: String, val channelName: String, val people: List<VoiceOccupant>, val status: String?)

    val ui: StateFlow<Ui?> = combine(
        social.groups.observeGroup(groupId).catch { emit(null) },
        channels.observeChannels(groupId).catch { emit(emptyList()) },
        channels.observeVoice(groupId).catch { emit(emptyMap()) },
        channels.observeVoiceStatuses(groupId).catch { emit(emptyMap()) },
    ) { group, stored, voice, statuses ->
        group ?: return@combine null
        val channel = ChannelModel.list(stored).second.firstOrNull { it.id == channelId } ?: return@combine null
        val people = voice[channelId].orEmpty()
        Ui(group.name, channel.name, people, if (people.isNotEmpty()) statuses[channelId] else null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Profile pictures of the people in the channel. */
    val photos: StateFlow<Map<String, String?>> = ui
        .map { it?.people?.map { p -> p.uid }.orEmpty().sorted() }
        .distinctUntilChanged()
        .flatMapLatest { uids ->
            if (uids.isEmpty()) flowOf(emptyMap())
            else combine(uids.map { uid -> social.profiles.observeProfile(uid).catch { emit(null) }.map { uid to it?.photoUrl } }) { it.toMap() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val voice: StateFlow<VoiceState> = container.voice.state
    val watch: StateFlow<ScreenShareViewer.State> = container.goLive.state
    val viewer: ScreenShareViewer get() = container.goLive
    val voiceSupported: Boolean get() = container.voice.supported

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun dismissNotice() {
        _notice.value = null
    }

    fun serverNow(): Long = container.transport.serverClock.serverNowMs()

    /** Call once RECORD_AUDIO is granted. */
    fun join() = container.voice.join(room, container.voice.preferredQuality())

    fun leave() {
        container.goLive.stop()
        if (container.voice.state.value.roomCode == room) container.voice.leave()
    }

    fun setMuted(muted: Boolean) = container.voice.setMuted(muted)

    fun setDeafened(deafened: Boolean) = container.voice.setDeafened(deafened)

    /** Watch [uid]'s Go Live (we must be in this voice channel: the set-up goes through its relay). */
    fun watch(uid: String) {
        val slot = container.voice.state.value.peers.firstOrNull { it.uid == uid && it.live }?.slot
        if (slot == null) {
            _notice.value = "Join voice to watch"
            return
        }
        container.goLive.watch(slot)
    }

    fun stopWatching() = container.goLive.stop()

    fun setStatus(text: String) {
        viewModelScope.launch {
            runCatching { channels.setStatus(groupId, channelId, text) }.onFailure { _notice.value = "Couldn't set the status" }
        }
    }

    /** Name of whoever is on [slot] (the sharer). */
    fun nameOfSlot(slot: Int): String = container.voice.state.value.peers.firstOrNull { it.slot == slot }?.name ?: "Friend"

    init {
        // Leaving this voice chat (here, from the bar, or a drop) ends any watching.
        viewModelScope.launch {
            container.voice.state.map { it.roomCode == room && it.active }.distinctUntilChanged().collect { inHere ->
                if (!inHere && container.goLive.state.value !is ScreenShareViewer.State.Idle) container.goLive.stop()
            }
        }
    }
}

@Composable
fun VoiceChannelScreen(viewModel: VoiceChannelViewModel, onBack: () -> Unit) {
    val p = Glass.palette
    val context = LocalContext.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val voice by viewModel.voice.collectAsStateWithLifecycle()
    val watch by viewModel.watch.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val inHere = voice.active && voice.roomCode == viewModel.room
    var editingStatus by rememberSaveable { mutableStateOf(false) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.join() else Toast.makeText(context, "Allow the microphone to talk in voice", Toast.LENGTH_LONG).show()
    }
    val join: () -> Unit = {
        when {
            !viewModel.voiceSupported -> Toast.makeText(context, "Voice chat needs Android 10 or newer", Toast.LENGTH_LONG).show()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> viewModel.join()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(watch) {
        val w = watch
        if (w is ScreenShareViewer.State.Ended) {
            Toast.makeText(
                context,
                when (w.reason) {
                    "full" -> "Too many people are watching this stream"
                    "failed" -> "Couldn't connect to the stream. One of your networks may block direct connections."
                    else -> "The stream ended"
                },
                Toast.LENGTH_LONG,
            ).show()
            viewModel.stopWatching()
        }
    }
    // The call timer counts seconds while anyone's in.
    var now by remember { mutableLongStateOf(viewModel.serverNow()) }
    val people = ui?.people.orEmpty()
    LaunchedEffect(people.isNotEmpty()) {
        while (people.isNotEmpty()) {
            now = viewModel.serverNow()
            delay(1_000)
        }
    }
    val speaking = voice.peers.filter { it.speaking }.map { it.uid }.toSet() + listOfNotNull(viewModel.myUid.takeIf { inHere && voice.speaking })
    val watchingSlot = when (val w = watch) {
        is ScreenShareViewer.State.Connecting -> w.slot
        is ScreenShareViewer.State.Playing -> w.slot
        else -> null
    }

    GlassScreen {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ui?.channelName ?: "Voice", style = MaterialTheme.typography.titleMedium, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val timer = ChannelModel.callElapsed(people, now)
                    Text(
                        listOfNotNull(ui?.groupName, timer).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = p.textSecondary,
                        maxLines = 1,
                    )
                }
            }

            // "Set a channel status" (anyone in the channel), like Discord.
            val status = ui?.status
            if (status != null || inHere) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (inHere) Modifier.clickable { editingStatus = true } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        status ?: "Set a channel status",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (status != null) p.textPrimary else p.textTertiary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (inHere) Icon(Icons.Filled.Edit, "Edit status", tint = p.textTertiary, modifier = Modifier.size(16.dp))
                }
            }

            if (watchingSlot != null && !fullScreen) {
                StreamBox(
                    viewModel = viewModel,
                    playing = watch is ScreenShareViewer.State.Playing,
                    who = viewModel.nameOfSlot(watchingSlot),
                    onFullScreen = { fullScreen = true },
                    onStop = viewModel::stopWatching,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            if (people.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "No one's here yet.\nJoin and your friends will see you're in.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = p.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(people, key = { it.uid }) { person ->
                        PersonTile(
                            person = person,
                            isMe = person.uid == viewModel.myUid,
                            photoUrl = photos[person.uid],
                            speaking = person.uid in speaking,
                            watching = watchingSlot != null && voice.peers.firstOrNull { it.slot == watchingSlot }?.uid == person.uid,
                            onWatch = { if (inHere) viewModel.watch(person.uid) else join() },
                        )
                    }
                }
            }

            // Bottom: Join voice, or Mute / Deafen / Leave with the voice server and ping.
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (inHere) {
                    Text(
                        "Voice connected · ${ChannelModel.serverLine(voice.server, voice.pingMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = p.success,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        RoundControl(if (voice.muted) R.drawable.ic_voice_mic_off else R.drawable.ic_voice_mic, if (voice.muted) "Unmute" else "Mute", active = voice.muted) {
                            viewModel.setMuted(!voice.muted)
                        }
                        RoundControl(if (voice.deafened) R.drawable.ic_voice_headset_off else R.drawable.ic_voice_headset, if (voice.deafened) "Undeafen" else "Deafen", active = voice.deafened) {
                            viewModel.setDeafened(!voice.deafened)
                        }
                        RoundControl(R.drawable.ic_voice_leave, "Leave voice", danger = true, onClick = viewModel::leave)
                    }
                } else {
                    val connecting = voice.roomCode == viewModel.room && !voice.active && voice.error == null
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(24.dp))
                            .background(p.success)
                            .clickable(enabled = !connecting, onClick = join)
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (connecting) "Connecting…" else "Join voice", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    }
                    if (voice.active && voice.roomCode != viewModel.room) {
                        Text(
                            "Joining moves you out of the voice chat you're in now.",
                            style = MaterialTheme.typography.bodySmall,
                            color = p.textTertiary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    voice.error?.takeIf { voice.roomCode == viewModel.room }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = p.danger, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        NoticeBanner(notice, onDismiss = viewModel::dismissNotice, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }

    if (fullScreen && watchingSlot != null) {
        Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                StreamSurface(viewModel, Modifier.fillMaxSize())
                GlassIconButton(Icons.Filled.Close, "Exit full screen", { fullScreen = false }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp))
            }
        }
    }

    if (editingStatus) {
        var text by remember { mutableStateOf(ui?.status.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editingStatus = false },
            title = { Text("Channel status") },
            text = {
                GlassTextField(value = text, onValueChange = { text = it.take(GroupChannels.STATUS_MAX) }, placeholder = "What's happening here?", singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    editingStatus = false
                    viewModel.setStatus(text)
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editingStatus = false
                    viewModel.setStatus("")
                }) { Text("Clear") }
            },
        )
    }
}

@Composable
private fun StreamBox(viewModel: VoiceChannelViewModel, playing: Boolean, who: String, onFullScreen: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val p = Glass.palette
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color.Black).clickable(onClick = onFullScreen)) {
            StreamSurface(viewModel, Modifier.fillMaxSize())
            if (!playing) {
                Text("Connecting to $who's stream…", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.align(Alignment.Center))
            }
            Row(Modifier.align(Alignment.TopStart).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LiveBadge()
                Spacer(Modifier.width(6.dp))
                Text(who, color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onFullScreen) { Text("Full screen", color = p.textPrimary) }
            TextButton(onClick = onStop) { Text("Stop watching", color = p.danger) }
        }
    }
}

/** The WebRTC video; released when it leaves the screen. */
@Composable
private fun StreamSurface(viewModel: VoiceChannelViewModel, modifier: Modifier) {
    val viewer = viewModel.viewer
    AndroidView(factory = { ctx -> viewer.createView(ctx) }, modifier = modifier, onRelease = { viewer.releaseView(it) })
}

@Composable
private fun PersonTile(person: VoiceOccupant, isMe: Boolean, photoUrl: String?, speaking: Boolean, watching: Boolean, onWatch: () -> Unit) {
    val p = Glass.palette
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(p.glassFallback)
            .then(if (person.live && !isMe) Modifier.clickable(onClick = onWatch) else Modifier)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(64.dp)
                .then(if (speaking) Modifier.border(3.dp, p.success, CircleShape) else Modifier)
                .padding(4.dp),
        ) {
            Avatar(person.name, size = 56.dp, photoUrl = photoUrl)
        }
        Spacer(Modifier.size(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isMe) "You" else person.name.substringBefore(' '),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = p.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (person.deafened || person.muted) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    ImageVector.vectorResource(if (person.deafened) R.drawable.ic_voice_headset_off else R.drawable.ic_voice_mic_off),
                    if (person.deafened) "Deafened" else "Muted",
                    tint = p.textTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (person.live) {
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveBadge()
                if (!isMe) {
                    Spacer(Modifier.width(6.dp))
                    Text(if (watching) "Watching" else "Watch", style = MaterialTheme.typography.labelMedium, color = p.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun RoundControl(icon: Int, label: String, active: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val p = Glass.palette
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(if (danger) p.danger else if (active) p.textPrimary else p.glassFallback)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ImageVector.vectorResource(icon), label, tint = if (danger) Color.White else if (active) p.base else p.textPrimary, modifier = Modifier.size(24.dp))
    }
}
