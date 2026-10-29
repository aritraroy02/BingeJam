package com.syncit.voice

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.syncit.core.voice.VoiceDevice
import com.syncit.core.voice.VoiceDevices
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Call-style audio while in voice: MODE_IN_COMMUNICATION turns on the phone's echo canceller
 * (so friends don't hear themselves, or the movie). Voices go to the speaker the user picked in
 * Settings, else a headset when one is connected, else the loudspeaker (not the earpiece). Also
 * keeps the list of mics/speakers for Settings current as devices come and go.
 */
internal class AudioRouting(context: Context, private val prefs: SharedPreferences) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var previousMode = AudioManager.MODE_NORMAL
    private var active = false
    private var selectedInput: String? = prefs.getString(KEY_INPUT, null)
    private var selectedOutput: String? = prefs.getString(KEY_OUTPUT, null)
    private val _devices = MutableStateFlow(VoiceDevices())

    val devices: StateFlow<VoiceDevices> = _devices.asStateFlow()

    init {
        audio.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
            },
            handler,
        )
        refresh()
    }

    fun enter() {
        if (active) return
        active = true
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
    }

    fun exit() {
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = if (previousMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else previousMode
    }

    fun setInput(id: String?) {
        selectedInput = id
        prefs.edit().putString(KEY_INPUT, id).apply()
        refresh()
    }

    fun setOutput(id: String?) {
        selectedOutput = id
        prefs.edit().putString(KEY_OUTPUT, id).apply()
        refresh()
    }

    /** The picked mic, if it's connected; the capture asks AudioRecord to prefer it. */
    fun preferredInput(): AudioDeviceInfo? {
        val id = selectedInput ?: return null
        return audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { inputKey(it) == id }
    }

    private fun refresh() {
        val inputs = audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { d -> inputKey(d)?.let { VoiceDevice(it, inputLabel(d)) } }
            .distinctBy { it.id }
        val outputs = outputCandidates()
            .mapNotNull { d -> outputKey(d)?.let { VoiceDevice(it, outputLabel(d)) } }
            .distinctBy { it.id }
        _devices.value = VoiceDevices(inputs, outputs, selectedInput, selectedOutput)
        route()
    }

    private fun outputCandidates(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices
        } else {
            // Before Android 12 only loudspeaker vs earpiece can be chosen; headsets take over by themselves.
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }
        }

    private fun route() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = audio.availableCommunicationDevices
            // A Bluetooth mic only works with that headset as the call device.
            val bluetoothMic = preferredInput()?.takeIf { it.type in BLUETOOTH }
            val target = selectedOutput?.let { id -> available.firstOrNull { outputKey(it) == id } }
                ?: bluetoothMic?.let { mic -> available.firstOrNull { it.type in BLUETOOTH && it.productName == mic.productName } }
                ?: available.firstOrNull { it.type in HEADSETS }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (target != null && audio.communicationDevice?.id != target.id) audio.setCommunicationDevice(target)
        } else {
            val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }
            val speaker = when (selectedOutput) {
                SPEAKER -> true
                EARPIECE -> false
                else -> !headset
            }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = speaker
        }
    }

    private companion object {
        const val KEY_INPUT = "input_device"
        const val KEY_OUTPUT = "output_device"
        const val SPEAKER = "speaker"
        const val EARPIECE = "earpiece"

        val BLUETOOTH = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        val USB = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val HEADSETS = WIRED + USB + BLUETOOTH

        fun name(d: AudioDeviceInfo): String = d.productName?.toString()?.trim().orEmpty()

        fun outputKey(d: AudioDeviceInfo): String? = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> EARPIECE
            in WIRED -> "wired"
            in USB -> "usb:${name(d)}"
            in BLUETOOTH -> "bt:${name(d)}"
            else -> null
        }

        fun outputLabel(d: AudioDeviceInfo): String = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loudspeaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Phone earpiece"
            in WIRED -> "Wired headphones"
            in USB -> name(d).ifEmpty { "USB headset" }
            else -> name(d).ifEmpty { "Bluetooth headset" } + " (Bluetooth)"
        }

        fun inputKey(d: AudioDeviceInfo): String? = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "phone"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired"
            in USB -> "usb:${name(d)}"
            in BLUETOOTH -> "bt:${name(d)}"
            else -> null
        }

        fun inputLabel(d: AudioDeviceInfo): String = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset mic"
            in USB -> name(d).ifEmpty { "USB mic" }
            else -> name(d).ifEmpty { "Bluetooth headset" } + " mic (Bluetooth)"
        }
    }
}
