package com.syncit.voice

import android.content.Context
import android.util.Log
import android.view.View
import com.syncit.core.voice.VoiceAuth
import com.syncit.core.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.TimeUnit

/**
 * Watching a PC's Go Live from the phone (2026-11-25). Same set-up as the PC's StreamViewer
 * (desktop/src/renderer/src/voice/stream.ts): we send {"kind":"watch"} to the sharer's slot through
 * the voice relay, their PC sends an offer, we answer, ICE goes both ways, and the screen (and its
 * sound) comes straight from their PC over WebRTC. Receive only: nothing of ours is sent. One at a time.
 */
class ScreenShareViewer(context: Context, private val voice: VoiceController, private val auth: VoiceAuth?) {
    sealed interface State {
        data object Idle : State
        data class Connecting(val slot: Int) : State
        data class Playing(val slot: Int) : State
        /** [reason]: "ended" (they stopped), "full" (too many watching), "failed" (no connection). */
        data class Ended(val slot: Int, val reason: String) : State
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val egl: EglBase by lazy { EglBase.create() }
    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    private var hostSlot = -1
    private var pc: PeerConnection? = null
    private var track: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private val pending = ArrayList<IceCandidate>()
    private var signalJob: Job? = null
    private var stateJob: Job? = null
    private var iceServers: List<PeerConnection.IceServer>? = null

    /** Start watching whoever is live on [slot] in the voice chat we're in. */
    fun watch(slot: Int) {
        if (slot == hostSlot && (_state.value is State.Connecting || _state.value is State.Playing)) return
        stop()
        hostSlot = slot
        _state.value = State.Connecting(slot)
        signalJob = scope.launch { voice.signals.collect { if (it.fromSlot == hostSlot) onSignal(it.data) } }
        // They left the voice chat or stopped sharing.
        stateJob = scope.launch {
            voice.state.collect { s ->
                val host = s.peers.firstOrNull { it.slot == hostSlot }
                if (hostSlot >= 0 && (!s.active || host == null || !host.live)) end("ended")
            }
        }
        scope.launch {
            iceServers = iceServers ?: fetchIceServers()
            send(JSONObject().put("kind", "watch"))
        }
    }

    /** We stop watching (or leave the voice chat). */
    fun stop() {
        if (hostSlot >= 0 && (_state.value is State.Connecting || _state.value is State.Playing)) send(JSONObject().put("kind", "unwatch"))
        close()
        _state.value = State.Idle
    }

    /** The video surface; show it while [state] is Playing and [releaseView] when it leaves the screen. */
    fun createView(context: Context): View {
        val view = SurfaceViewRenderer(context)
        view.init(egl.eglBaseContext, null)
        view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        view.setEnableHardwareScaler(true)
        renderer = view
        track?.addSink(view)
        return view
    }

    fun releaseView(view: View) {
        val r = view as? SurfaceViewRenderer ?: return
        track?.removeSink(r)
        if (renderer === r) renderer = null
        r.release()
    }

    private fun end(reason: String) {
        val slot = hostSlot
        if (slot < 0) return
        close()
        _state.value = State.Ended(slot, reason)
    }

    private fun close() {
        signalJob?.cancel()
        stateJob?.cancel()
        signalJob = null
        stateJob = null
        renderer?.let { r -> track?.removeSink(r) }
        track = null
        pc?.dispose()
        pc = null
        pending.clear()
        hostSlot = -1
    }

    private fun send(data: JSONObject) {
        if (hostSlot >= 0) voice.sendSignal(hostSlot, data.toString())
    }

    // ---- Set-up messages from the sharer ----

    private fun onSignal(data: String) {
        val m = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (m.optString("kind")) {
            "offer" -> m.optString("sdp").takeIf { it.isNotEmpty() }?.let(::answer)
            "ice" -> m.optJSONObject("candidate")?.let { c ->
                val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
                val conn = pc
                if (conn?.remoteDescription != null) conn.addIceCandidate(candidate) else pending += candidate
            }
            "full" -> end("full")
            "ended" -> end("ended")
        }
    }

    private fun answer(sdp: String) {
        pc?.dispose()
        val config = PeerConnection.RTCConfiguration(iceServers ?: DEFAULT_ICE).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val conn = factory.createPeerConnection(config, Observer()) ?: return end("failed")
        pc = conn
        conn.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() = onMain {
                if (pc !== conn) return@onMain
                pending.forEach(conn::addIceCandidate)
                pending.clear()
                conn.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(answer: SessionDescription) = onMain {
                        if (pc !== conn) return@onMain
                        conn.setLocalDescription(object : Sdp() {
                            override fun onSetSuccess() = onMain {
                                if (pc === conn) send(JSONObject().put("kind", "answer").put("sdp", answer.description))
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = onMain {
            send(
                JSONObject().put("kind", "ice").put(
                    "candidate",
                    JSONObject().put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex),
                ),
            )
        }

        override fun onTrack(transceiver: RtpTransceiver) = onMain {
            val video = transceiver.receiver.track() as? VideoTrack ?: return@onMain
            track = video
            renderer?.let(video::addSink)
            if (hostSlot >= 0) _state.value = State.Playing(hostSlot)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = onMain {
            if (newState == PeerConnection.PeerConnectionState.FAILED) end("failed")
        }

        override fun onSignalingChange(p0: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(p0: Boolean) = Unit
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) = Unit
        override fun onAddStream(p0: MediaStream?) = Unit
        override fun onRemoveStream(p0: MediaStream?) = Unit
        override fun onDataChannel(p0: org.webrtc.DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) = Unit
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(p0: String?) = Log.w(TAG, "Go Live answer failed: $p0").let { }
        override fun onSetFailure(p0: String?) = Log.w(TAG, "Go Live description refused: $p0").let { }
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    /** STUN (and TURN once the relay has it) from the relay's GET /ice; public STUN if that fails. */
    private suspend fun fetchIceServers(): List<PeerConnection.IceServer> = withContext(Dispatchers.IO) {
        val a = auth ?: return@withContext DEFAULT_ICE
        runCatching {
            val token = a.idToken(false) ?: return@runCatching DEFAULT_ICE
            val base = a.voiceUrl.trimEnd('/').removeSuffix("/voice").replaceFirst("wss://", "https://")
            val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url("$base/ice").header("Authorization", "Bearer $token").build()).execute().use { res ->
                if (!res.isSuccessful) return@runCatching DEFAULT_ICE
                val list = JSONObject(res.body?.string().orEmpty()).optJSONArray("iceServers") ?: return@runCatching DEFAULT_ICE
                (0 until list.length()).mapNotNull { i ->
                    val o = list.optJSONObject(i) ?: return@mapNotNull null
                    val urls = o.opt("urls")
                    val all = when (urls) {
                        is String -> listOf(urls)
                        is org.json.JSONArray -> (0 until urls.length()).map { urls.optString(it) }
                        else -> emptyList()
                    }.filter { it.isNotBlank() }
                    if (all.isEmpty()) return@mapNotNull null
                    PeerConnection.IceServer.builder(all)
                        .setUsername(o.optString("username"))
                        .setPassword(o.optString("credential"))
                        .createIceServer()
                }.ifEmpty { DEFAULT_ICE }
            }
        }.getOrElse { DEFAULT_ICE }
    }

    private companion object {
        const val TAG = "SyncItGoLive"
        val DEFAULT_ICE: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        )
    }
}
