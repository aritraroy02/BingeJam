package com.syncit.app.social

import android.util.Log
import com.syncit.core.social.GroupChannels
import com.syncit.core.voice.VoiceController
import com.syncit.core.voice.VoiceStatus
import com.syncit.core.voice.parseGroupVoiceRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * While this phone talks in a group's voice channel, lists us there (groupVoice/{gid}/{cid}/{uid},
 * onDisconnect removes it) so the group sees who's in, with our mute and deafen; takes us off when
 * we leave or switch channels. Same entries the PC writes (desktop live/useVoice.ts).
 */
class GroupVoicePresence(
    private val voice: VoiceController,
    private val channels: GroupChannels,
    private val myName: () -> String,
    scope: CoroutineScope,
) {
    private data class Here(val gid: String, val cid: String, val muted: Boolean, val deafened: Boolean)

    private var listed: Pair<String, String>? = null

    init {
        scope.launch {
            voice.state
                .map { s ->
                    val target = parseGroupVoiceRoom(s.roomCode)
                    val on = s.status == VoiceStatus.CONNECTING || s.status == VoiceStatus.CONNECTED || s.status == VoiceStatus.RECONNECTING
                    if (target != null && on) Here(target.first, target.second, s.muted, s.deafened) else null
                }
                .distinctUntilChanged()
                .collect { here -> sync(here) }
        }
    }

    private suspend fun sync(here: Here?) {
        val was = listed
        val now = here?.let { it.gid to it.cid }
        try {
            if (was != null && was != now) {
                listed = null
                channels.leaveVoice(was.first, was.second)
            }
            if (here == null) return
            if (listed == null) {
                channels.joinVoice(here.gid, here.cid, myName(), here.muted || here.deafened, here.deafened)
                listed = now
            } else {
                channels.updateVoice(here.gid, here.cid, here.muted || here.deafened, here.deafened)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("SyncItVoice", "Voice channel presence not written", e)
        }
    }
}
