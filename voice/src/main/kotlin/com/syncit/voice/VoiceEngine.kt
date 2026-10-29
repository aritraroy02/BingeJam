package com.syncit.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Log
import com.syncit.core.voice.JitterBuffer
import com.syncit.core.voice.VOICE_ECHO_ROOM
import com.syncit.core.voice.VoiceActivity
import com.syncit.core.voice.VoiceAuth
import com.syncit.core.voice.VoicePeer
import com.syncit.core.voice.VoiceQuality
import com.syncit.core.voice.VoiceState
import com.syncit.core.voice.VoiceStatus
import com.syncit.core.voice.VoiceWire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * One voice session in one room. Threads: capture (mic → Opus → relay), decode (relay → PCM per
 * speaker), playback (mix → speaker). [update] publishes state; ignored once [stop]ped.
 */
internal class VoiceEngine(
    private val room: String,
    initialQuality: VoiceQuality,
    micVolume: Float,
    volumes: Map<String, Float>,
    private val auth: VoiceAuth,
    private val preferredInput: () -> AudioDeviceInfo?,
    private val scope: CoroutineScope,
    private val update: ((VoiceState) -> VoiceState) -> Unit,
) {
    @Volatile private var stopped = false
    @Volatile private var muted = false
    @Volatile private var deafened = false
    @Volatile private var micGain = micVolume
    @Volatile private var volumes = volumes

    /** slot → playback gain, from [volumes] and who has which slot. */
    @Volatile private var slotGains: Map<Int, Float> = emptyMap()
    @Volatile private var quality = initialQuality
    @Volatile private var connected = false
    @Volatile private var socket: WebSocket? = null
    @Volatile private var mySlot = -1
    @Volatile private var playbackLevel = 0.0
    @Volatile private var lastVoicedAt = 0L
    private var generation = 0
    private var attempts = 0
    private var reconnectJob: Job? = null
    private var tickerJob: Job? = null

    private val echo = room == VOICE_ECHO_ROOM
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val decodeThread = Executors.newSingleThreadExecutor { Thread(it, "voice-decode") }

    /** slot → decoded audio waiting to play. Guarded by itself. */
    private val buffers = HashMap<Int, JitterBuffer>()
    private val decoders = HashMap<Int, OpusDecoder>() // decode thread only
    private val lastHeard = HashMap<Int, Long>() // guarded by [buffers]
    private val peers = LinkedHashMap<Int, VoicePeer>() // guarded by itself

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    fun start() {
        connect(forceToken = false)
        playbackThread = Thread(::playbackLoop, "voice-playback").apply { start() }
        restartCapture()
        tickerJob = scope.launch {
            while (isActive) {
                publishSpeaking()
                delay(200)
            }
        }
    }

    fun stop() {
        if (stopped) return
        stopped = true
        reconnectJob?.cancel()
        tickerJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        captureThread?.interrupt()
        onDecodeThread {
            decoders.values.forEach { it.release() }
            decoders.clear()
        }
        decodeThread.shutdown()
        client.dispatcher.executorService.shutdown()
    }

    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        sendState()
        restartCapture()
    }

    fun setDeafened(value: Boolean) {
        if (deafened == value) return
        deafened = value
        sendState()
    }

    fun setMicVolume(value: Float) {
        micGain = value
    }

    fun setVolumes(value: Map<String, Float>) {
        volumes = value
        publishPeers()
    }

    /** The mic picked in Settings changed: reopen the mic with it. */
    fun inputChanged() = restartCapture()

    fun setQuality(value: VoiceQuality) {
        if (quality == value) return
        quality = value
        sendState()
        restartCapture()
    }

    // ---- Relay connection ----

    private fun connect(forceToken: Boolean) {
        if (stopped) return
        scope.launch(Dispatchers.IO) {
            val token = runCatching { auth.idToken(forceToken) }.getOrNull()
            if (stopped) return@launch
            if (token == null) {
                fail("Sign in again to use voice")
                return@launch
            }
            val request = Request.Builder()
                .url("${auth.voiceUrl.trimEnd('/')}/$room")
                .header("Authorization", "Bearer $token")
                .build()
            val gen = synchronized(this@VoiceEngine) { ++generation }
            socket = client.newWebSocket(request, Listener(gen, forceToken))
        }
    }

    private inner class Listener(private val gen: Int, private val freshToken: Boolean) : WebSocketListener() {
        private fun current() = !stopped && synchronized(this@VoiceEngine) { gen == generation }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!current()) return
            attempts = 0
            connected = true
            sendState()
            update { it.copy(status = VoiceStatus.CONNECTED, error = null) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (current()) onControl(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (current()) onAudio(bytes.toByteArray())
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (current()) dropped()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!current()) return
            when (response?.code) {
                401 -> if (freshToken) fail("Sign in again to use voice") else reconnect(forceToken = true, immediately = true)
                403 -> fail("You're not in this room any more")
                429 -> fail("Voice chat is full (8 people max)")
                else -> {
                    Log.w(TAG, "Voice connection lost: ${t.message}")
                    dropped()
                }
            }
        }
    }

    private fun dropped() {
        connected = false
        reconnect(forceToken = false, immediately = false)
    }

    private fun reconnect(forceToken: Boolean, immediately: Boolean) {
        if (stopped) return
        connected = false
        update { it.copy(status = VoiceStatus.RECONNECTING) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (!immediately) delay(min(10_000L, 1_000L shl attempts.coerceAtMost(4)))
            attempts++
            connect(forceToken)
        }
    }

    private fun fail(message: String) {
        if (stopped) return
        connected = false
        update { it.copy(status = VoiceStatus.FAILED, error = message) }
        stop()
    }

    private fun sendState() {
        if (!connected) return
        socket?.send(
            JSONObject().put("t", "state").put("muted", muted).put("deafened", deafened).put("quality", quality.wire).toString(),
        )
    }

    private fun onControl(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (m.optString("t")) {
            "hello" -> {
                mySlot = m.optInt("slot", -1)
                val list = m.optJSONArray("peers")
                synchronized(peers) {
                    peers.clear()
                    for (i in 0 until (list?.length() ?: 0)) peer(list!!.getJSONObject(i))?.let { peers[it.slot] = it }
                }
                forgetMissingSlots()
            }
            "join" -> m.optJSONObject("peer")?.let(::peer)?.let { p ->
                synchronized(peers) {
                    peers.values.removeAll { it.uid == p.uid }
                    peers[p.slot] = p
                }
            }
            "leave" -> {
                val slot = m.optInt("slot", -1)
                synchronized(peers) { peers.remove(slot) }
                forget(slot)
            }
            "state" -> {
                val slot = m.optInt("slot", -1)
                synchronized(peers) {
                    peers[slot]?.let {
                        peers[slot] = it.copy(
                            muted = m.optBoolean("muted"),
                            deafened = m.optBoolean("deafened"),
                            quality = VoiceQuality.fromWire(m.optString("quality")),
                        )
                    }
                }
            }
        }
        publishPeers()
    }

    private fun peer(o: JSONObject): VoicePeer? {
        val slot = o.optInt("slot", -1).takeIf { it >= 0 } ?: return null
        return VoicePeer(
            slot = slot,
            uid = o.optString("uid"),
            name = o.optString("name").ifBlank { "Friend" },
            muted = o.optBoolean("muted"),
            deafened = o.optBoolean("deafened"),
            quality = VoiceQuality.fromWire(o.optString("quality")),
        )
    }

    // ---- Receiving ----

    private fun onAudio(data: ByteArray) {
        val message = VoiceWire.decode(data) ?: return
        if (message.slot == mySlot && !echo) return
        val slot = message.slot
        onDecodeThread {
            if (stopped) return@onDecodeThread
            val decoder = decoders.getOrPut(slot) { OpusDecoder() }
            for (packet in message.packets) {
                runCatching {
                    decoder.decode(packet) { pcm, n ->
                        val audio = if (decoder.outputRate == PLAY_RATE) pcm else resample(pcm, n, decoder.outputRate)
                        val length = if (audio === pcm) n else audio.size
                        synchronized(buffers) {
                            buffers.getOrPut(slot) { JitterBuffer(TARGET_SAMPLES, MAX_SAMPLES) }.write(audio, 0, length)
                            lastHeard[slot] = SystemClock.elapsedRealtime()
                        }
                    }
                }.onFailure { Log.w(TAG, "Couldn't decode voice from slot $slot", it) }
            }
        }
    }

    private fun onDecodeThread(block: () -> Unit) {
        try {
            decodeThread.execute(block)
        } catch (_: RejectedExecutionException) {
            // Stopped meanwhile.
        }
    }

    private fun forget(slot: Int) {
        synchronized(buffers) {
            buffers.remove(slot)
            lastHeard.remove(slot)
        }
        onDecodeThread { decoders.remove(slot)?.release() }
    }

    private fun forgetMissingSlots() {
        val keep = synchronized(peers) { peers.keys.toSet() } + (if (echo) setOf(mySlot) else emptySet())
        val gone = synchronized(buffers) { buffers.keys.filter { it !in keep } }
        gone.forEach(::forget)
    }

    private fun playbackLoop() {
        val minBuffer = AudioTrack.getMinBufferSize(PLAY_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(PLAY_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer, PLAY_FRAME * 2 * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No audio output", e)
            return
        }
        val mix = IntArray(PLAY_FRAME)
        val out = ShortArray(PLAY_FRAME)
        try {
            track.play()
            while (!stopped) {
                mix.fill(0)
                val gains = slotGains
                synchronized(buffers) {
                    if (deafened) {
                        buffers.values.forEach { it.clear() }
                    } else {
                        for ((slot, buffer) in buffers) buffer.mixInto(mix, PLAY_FRAME, gains[slot] ?: 1f)
                    }
                }
                playbackLevel = VoiceActivity.rms(mix)
                for (i in 0 until PLAY_FRAME) out[i] = mix[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                if (track.write(out, 0, PLAY_FRAME) < 0) break
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    // ---- Sending ----

    private fun restartCapture() {
        captureThread?.interrupt()
        captureThread = null
        if (stopped || muted) return
        val q = quality
        captureThread = Thread({ captureLoop(q) }, "voice-capture").apply { start() }
    }

    @SuppressLint("MissingPermission") // The UI asks for RECORD_AUDIO before joining.
    private fun captureLoop(q: VoiceQuality) {
        val me = Thread.currentThread()
        val frame = q.sampleRate * Opus.FRAME_MS / 1000
        val minBuffer = AudioRecord.getMinBufferSize(q.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                q.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, frame * 2 * 4),
            )
        } catch (e: SecurityException) {
            fail("Allow the microphone to talk in voice")
            return
        }
        preferredInput()?.let { record.preferredDevice = it }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail("Couldn't open the microphone")
            return
        }
        val effects = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null,
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId) else null,
        )
        effects.forEach { runCatching { it.enabled = true } }
        val encoder = try {
            OpusEncoder(q.sampleRate, q.bitrate)
        } catch (e: Exception) {
            effects.forEach { it.release() }
            record.release()
            fail("This phone can't do voice chat")
            return
        }
        val vad = VoiceActivity(hangoverFrames = HANGOVER_FRAMES)
        val pcm = ShortArray(frame)
        val pending = ArrayList<ByteArray>(PACKETS_PER_MESSAGE)
        var seq = 0
        try {
            record.startRecording()
            while (!stopped && captureThread === me && !me.isInterrupted) {
                val n = record.read(pcm, 0, frame)
                if (n <= 0) continue
                val gain = micGain
                if (gain != 1f) {
                    for (i in 0 until n) pcm[i] = (pcm[i] * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                val voiced = vad.isVoiced(pcm, n, playbackLevel)
                val packets = encoder.encode(pcm, n)
                if (voiced) {
                    lastVoicedAt = SystemClock.elapsedRealtime()
                    pending += packets
                }
                if (pending.size >= PACKETS_PER_MESSAGE || (!voiced && pending.isNotEmpty())) {
                    if (connected) socket?.send(VoiceWire.encode(seq, q == VoiceQuality.HD, pending).toByteString())
                    seq = (seq + 1) and 0xFFFF
                    pending.clear()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice capture stopped", e)
        } finally {
            runCatching { record.stop() }
            record.release()
            effects.forEach { it.release() }
            encoder.release()
        }
    }

    // ---- State ----

    private fun publishPeers() {
        val list = synchronized(peers) { peers.values.toList() }
        val v = volumes
        slotGains = list.associate { it.slot to (v[it.uid] ?: 1f) }
        update { it.copy(peers = withSpeaking(list)) }
    }

    private fun publishSpeaking() {
        val now = SystemClock.elapsedRealtime()
        val meSpeaking = !muted && connected && now - lastVoicedAt < SPEAKING_MS
        val list = withSpeaking(synchronized(peers) { peers.values.toList() })
        update { s -> if (s.speaking == meSpeaking && s.peers == list) s else s.copy(speaking = meSpeaking, peers = list) }
    }

    private fun withSpeaking(list: List<VoicePeer>): List<VoicePeer> {
        val now = SystemClock.elapsedRealtime()
        val heard = synchronized(buffers) { HashMap(lastHeard) }
        val v = volumes
        return list.map { it.copy(speaking = (heard[it.slot] ?: 0L) > now - SPEAKING_MS, volume = v[it.uid] ?: 1f) }
    }

    private companion object {
        const val TAG = "SyncItVoice"
        const val PLAY_RATE = Opus.DECODED_RATE
        const val PLAY_FRAME = PLAY_RATE * Opus.FRAME_MS / 1000

        /** Start playing a speaker after 80 ms queued; never fall more than 400 ms behind. */
        const val TARGET_SAMPLES = PLAY_RATE * 80 / 1000
        const val MAX_SAMPLES = PLAY_RATE * 400 / 1000

        /** 40 ms of audio per message (fewer requests on the relay's free plan). */
        const val PACKETS_PER_MESSAGE = 2
        const val HANGOVER_FRAMES = 20
        const val SPEAKING_MS = 350L

        fun resample(pcm: ShortArray, n: Int, fromRate: Int): ShortArray {
            val outLength = (n.toLong() * PLAY_RATE / fromRate).toInt()
            val out = ShortArray(outLength)
            for (i in 0 until outLength) {
                val pos = i.toDouble() * fromRate / PLAY_RATE
                val a = pos.toInt().coerceAtMost(n - 1)
                val b = (a + 1).coerceAtMost(n - 1)
                val t = pos - a
                out[i] = (pcm[a] * (1 - t) + pcm[b] * t).toInt().toShort()
            }
            return out
        }
    }
}
