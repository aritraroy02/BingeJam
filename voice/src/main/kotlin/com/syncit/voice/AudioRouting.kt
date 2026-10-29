package com.syncit.voice

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Call-style audio while in voice: MODE_IN_COMMUNICATION turns on the phone's echo canceller
 * (so friends don't hear themselves, or the movie), and voices go to a headset when one is
 * plugged in, otherwise the loudspeaker (not the earpiece). Re-routes when a headset comes or goes.
 */
internal class AudioRouting(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var previousMode = AudioManager.MODE_NORMAL
    private var active = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = route()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = route()
    }

    fun enter() {
        if (active) return
        active = true
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        route()
        audio.registerAudioDeviceCallback(devices, handler)
    }

    fun exit() {
        if (!active) return
        active = false
        audio.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = false
        }
        audio.mode = if (previousMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else previousMode
    }

    private fun route() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val available = audio.availableCommunicationDevices
            val target = available.firstOrNull { it.type in HEADSETS }
                ?: available.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (target != null && audio.communicationDevice?.id != target.id) audio.setCommunicationDevice(target)
        } else {
            val headset = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSETS }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !headset
        }
    }

    private companion object {
        val HEADSETS = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
