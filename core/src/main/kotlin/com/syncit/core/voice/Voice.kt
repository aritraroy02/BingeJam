package com.syncit.core.voice

import kotlinx.coroutines.flow.StateFlow

/**
 * How we send our own voice. Each person picks theirs; receivers decode whatever arrives (Opus
 * handles both). HD = fullband 48 kHz, SD = wideband 16 kHz for weak mobile data.
 */
enum class VoiceQuality(val sampleRate: Int, val bitrate: Int, val wire: String) {
    HD(48_000, 32_000, "hd"),
    SD(16_000, 12_000, "sd");

    companion object {
        fun fromWire(value: String?): VoiceQuality = if (value == SD.wire) SD else HD
    }
}

enum class VoiceStatus { OFF, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** Someone else in the room's voice chat. [slot] is the relay's short id for them. */
data class VoicePeer(
    val slot: Int,
    val uid: String,
    val name: String,
    val muted: Boolean,
    val quality: VoiceQuality,
    val speaking: Boolean = false,
    /** They turned off everyone's sound (and with it their mic). */
    val deafened: Boolean = false,
    /** How loud we play them, 0–2 (1 = as sent). Ours only; they never know. */
    val volume: Float = 1f,
)

data class VoiceState(
    val status: VoiceStatus = VoiceStatus.OFF,
    /** Room we're talking in; null when OFF. "echo" for the hear-yourself test. */
    val roomCode: String? = null,
    val muted: Boolean = false,
    /** We hear nobody; also mutes us (like Discord). Undeafening restores the mute from before. */
    val deafened: Boolean = false,
    /** Our mic's loudness before sending, 0–2 (1 = as recorded). */
    val micVolume: Float = 1f,
    val quality: VoiceQuality = VoiceQuality.HD,
    /** We're being heard right now (mic on and voice detected). */
    val speaking: Boolean = false,
    val peers: List<VoicePeer> = emptyList(),
    /** Why [VoiceStatus.FAILED], worded for the user. */
    val error: String? = null,
) {
    val active: Boolean get() = status != VoiceStatus.OFF && status != VoiceStatus.FAILED
}

/**
 * Voice chat in a room, through our own relay (push-relay/src/voice.js). Implemented in :voice.
 * [join] must be called while the app is in front (Android only lets a foreground app start
 * using the mic) and after RECORD_AUDIO is granted.
 */
interface VoiceController {
    val state: StateFlow<VoiceState>

    /** False on phones without an Opus encoder (Android 9 and older). */
    val supported: Boolean

    fun join(roomCode: String, quality: VoiceQuality)

    fun leave()

    fun setMuted(muted: Boolean)

    fun setDeafened(deafened: Boolean)

    /** 0–2; remembered on this phone. */
    fun setMicVolume(volume: Float)

    /** How loud [uid] plays for us, 0–2; remembered on this phone. */
    fun setPeerVolume(uid: String, volume: Float)

    fun setQuality(quality: VoiceQuality)

    /** The quality to join with: the last one picked, else HD on Wi-Fi and SD on mobile data. */
    fun preferredQuality(): VoiceQuality

    /** Mics and speakers connected right now, and which ones the user picked (Settings). */
    val devices: StateFlow<VoiceDevices>

    /** Null = automatic. Remembered on this phone; applies at once if in voice. */
    fun setInputDevice(id: String?)

    fun setOutputDevice(id: String?)
}

/**
 * A mic or speaker. [id] stays the same across reconnects (kind + product name), so a
 * Bluetooth headset picked once is used again whenever it's connected.
 */
data class VoiceDevice(val id: String, val label: String)

/**
 * [selectedInput]/[selectedOutput]: what the user picked, null for automatic (headset when one is
 * connected, else the phone's mic and loudspeaker). A pick that isn't connected right now falls
 * back to automatic until it is.
 */
data class VoiceDevices(
    val inputs: List<VoiceDevice> = emptyList(),
    val outputs: List<VoiceDevice> = emptyList(),
    val selectedInput: String? = null,
    val selectedOutput: String? = null,
)

/** Volume sliders go from silent to twice as loud. */
const val VOICE_MAX_VOLUME = 2f

/** Who we are to the relay. Implemented in :data-firebase (Firebase ID token). */
interface VoiceAuth {
    /** wss:// base of the relay, e.g. wss://relay.example/voice */
    val voiceUrl: String

    suspend fun idToken(forceRefresh: Boolean): String?
}

/** Room codes are 6 characters; this one asks the relay to send our own audio back. */
const val VOICE_ECHO_ROOM = "echo"
