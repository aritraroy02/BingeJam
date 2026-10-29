package com.syncit.voice

import android.content.Context
import android.net.ConnectivityManager
import com.syncit.core.voice.VOICE_MAX_VOLUME
import com.syncit.core.voice.VoiceAuth
import com.syncit.core.voice.VoiceController
import com.syncit.core.voice.VoiceQuality
import com.syncit.core.voice.VoiceState
import com.syncit.core.voice.VoiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The app's one voice session. [auth] is null with the debug fakes (no relay), which makes
 * voice unsupported. Mic volume, per-person volumes and the HD/SD pick are remembered on this
 * phone. Call from the main thread.
 */
class AndroidVoiceController(context: Context, private val auth: VoiceAuth?) : VoiceController {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val routing = AudioRouting(app)
    private val _state = MutableStateFlow(VoiceState(micVolume = prefs.getFloat(KEY_MIC, 1f)))
    @Volatile private var engine: VoiceEngine? = null
    private var volumes: Map<String, Float> = loadVolumes()

    /** Mute from before deafening, restored when undeafening (Discord does the same). */
    private var mutedBeforeDeafen = false

    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    override val supported: Boolean get() = auth != null && Opus.encoderAvailable

    init {
        instance = this
    }

    override fun join(roomCode: String, quality: VoiceQuality) {
        val current = _state.value
        if (engine != null && current.roomCode == roomCode && current.active) return
        teardown()
        val micVolume = prefs.getFloat(KEY_MIC, 1f)
        if (!supported || auth == null) {
            _state.value = VoiceState(status = VoiceStatus.FAILED, micVolume = micVolume, error = "Voice chat needs Android 10 or newer")
            return
        }
        _state.value = VoiceState(status = VoiceStatus.CONNECTING, roomCode = roomCode, quality = quality, micVolume = micVolume)
        mutedBeforeDeafen = false
        routing.enter()
        VoiceService.start(app)
        lateinit var created: VoiceEngine
        created = VoiceEngine(roomCode, quality, micVolume, volumes, auth, scope) { change ->
            if (engine !== created) return@VoiceEngine
            _state.update(change)
            if (_state.value.status == VoiceStatus.FAILED) scope.launch { if (engine === created) failed() }
        }
        engine = created
        created.start()
    }

    override fun leave() {
        teardown()
        _state.value = VoiceState(micVolume = prefs.getFloat(KEY_MIC, 1f))
    }

    override fun setMuted(muted: Boolean) {
        val e = engine ?: return
        // Unmuting while deafened undeafens too, as in Discord.
        if (!muted && _state.value.deafened) {
            e.setDeafened(false)
            _state.update { it.copy(deafened = false) }
        }
        e.setMuted(muted)
        _state.update { it.copy(muted = muted, speaking = if (muted) false else it.speaking) }
    }

    override fun setDeafened(deafened: Boolean) {
        val e = engine ?: return
        val s = _state.value
        if (s.deafened == deafened) return
        val muted = if (deafened) {
            mutedBeforeDeafen = s.muted
            true
        } else {
            mutedBeforeDeafen
        }
        e.setDeafened(deafened)
        e.setMuted(muted)
        _state.update { it.copy(deafened = deafened, muted = muted, speaking = if (muted) false else it.speaking) }
    }

    override fun setMicVolume(volume: Float) {
        val v = volume.coerceIn(0f, VOICE_MAX_VOLUME)
        prefs.edit().putFloat(KEY_MIC, v).apply()
        engine?.setMicVolume(v)
        _state.update { it.copy(micVolume = v) }
    }

    override fun setPeerVolume(uid: String, volume: Float) {
        val v = volume.coerceIn(0f, VOICE_MAX_VOLUME)
        volumes = if (v == 1f) volumes - uid else volumes + (uid to v)
        prefs.edit().putString(KEY_VOLUMES, JSONObject(volumes.mapValues { it.value.toDouble() }).toString()).apply()
        engine?.setVolumes(volumes)
        _state.update { s -> s.copy(peers = s.peers.map { if (it.uid == uid) it.copy(volume = v) else it }) }
    }

    override fun setQuality(quality: VoiceQuality) {
        prefs.edit().putString(KEY_QUALITY, quality.wire).apply()
        val e = engine ?: return
        e.setQuality(quality)
        _state.update { it.copy(quality = quality) }
    }

    override fun preferredQuality(): VoiceQuality {
        prefs.getString(KEY_QUALITY, null)?.let { return VoiceQuality.fromWire(it) }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        return if (cm?.isActiveNetworkMetered == true) VoiceQuality.SD else VoiceQuality.HD
    }

    private fun loadVolumes(): Map<String, Float> = runCatching {
        val o = JSONObject(prefs.getString(KEY_VOLUMES, null) ?: return emptyMap())
        o.keys().asSequence().associateWith { o.getDouble(it).toFloat().coerceIn(0f, VOICE_MAX_VOLUME) }
    }.getOrDefault(emptyMap())

    private fun failed() {
        engine?.stop()
        engine = null
        routing.exit()
        VoiceService.stop(app)
    }

    private fun teardown() {
        val e = engine ?: return
        engine = null
        e.stop()
        routing.exit()
        VoiceService.stop(app)
    }

    internal companion object {
        private const val KEY_MIC = "mic_volume"
        private const val KEY_VOLUMES = "peer_volumes"
        private const val KEY_QUALITY = "quality"

        @Volatile var instance: AndroidVoiceController? = null
    }
}
