package com.syncit.core.voice

import kotlin.math.sqrt

/**
 * Is the mic picking up someone talking? Only then is audio sent, which keeps the relay within
 * the free plan and stops background hiss. Energy-based: louder than the slowly tracked noise
 * floor, held for [hangoverFrames] so word endings aren't clipped. While friends' voices are
 * playing out loud, the bar rises with them so their echo isn't sent back.
 */
class VoiceActivity(
    private val hangoverFrames: Int = 20,
    private val minLevel: Double = 300.0,
) {
    private var noiseFloor = minLevel
    private var hangover = 0

    /** [playbackLevel]: RMS of what we're playing right now (friends' voices), 0 when quiet. */
    fun isVoiced(frame: ShortArray, length: Int = frame.size, playbackLevel: Double = 0.0): Boolean {
        val level = rms(frame, length)
        // Follow the floor down quickly and up slowly, so speech doesn't become "noise".
        noiseFloor = if (level < noiseFloor) noiseFloor * 0.9 + level * 0.1 else noiseFloor * 0.995 + level * 0.005
        noiseFloor = noiseFloor.coerceAtLeast(minLevel / 3)
        val bar = maxOf(minLevel, noiseFloor * 3.0, playbackLevel * 0.6)
        if (level > bar) {
            hangover = hangoverFrames
            return true
        }
        if (hangover > 0) {
            hangover--
            return true
        }
        return false
    }

    fun reset() {
        noiseFloor = minLevel
        hangover = 0
    }

    companion object {
        fun rms(frame: ShortArray, length: Int = frame.size): Double {
            if (length == 0) return 0.0
            var sum = 0.0
            for (i in 0 until length) {
                val s = frame[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / length)
        }

        fun rms(mix: IntArray, length: Int = mix.size): Double {
            if (length == 0) return 0.0
            var sum = 0.0
            for (i in 0 until length) {
                val s = mix[i].toDouble()
                sum += s * s
            }
            return sqrt(sum / length)
        }
    }
}
