package com.syncit.core.voice

/**
 * One speaker's decoded audio, waiting to be played. Audio arrives in bursts (40 ms per
 * message, over TCP), so playback starts only once [targetSamples] are queued, and a backlog
 * beyond [maxSamples] (a network hiccup flushing) is cut back to [targetSamples] so we never
 * drift behind the movie. Not thread-safe; the engine guards it.
 */
class JitterBuffer(
    private val targetSamples: Int,
    private val maxSamples: Int,
    capacity: Int = maxSamples * 2,
) {
    private val ring = ShortArray(capacity)
    private var head = 0
    private var size = 0
    private var playing = false

    val available: Int get() = size

    fun write(pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset) {
        var n = length
        var src = offset
        if (n > ring.size) {
            src += n - ring.size
            n = ring.size
        }
        val overflow = size + n - ring.size
        if (overflow > 0) skip(overflow)
        var tail = (head + size) % ring.size
        repeat(n) {
            ring[tail] = pcm[src++]
            tail = if (tail + 1 == ring.size) 0 else tail + 1
        }
        size += n
        if (size > maxSamples) skip(size - targetSamples)
    }

    /**
     * Adds [frameSize] samples, times [gain], into [mix] (summing, for several speakers). Returns false and
     * adds nothing while buffering, or when the speaker went quiet (then buffers again).
     */
    fun mixInto(mix: IntArray, frameSize: Int = mix.size, gain: Float = 1f): Boolean {
        if (!playing) {
            if (size < targetSamples) return false
            playing = true
        }
        if (size < frameSize) {
            playing = false
            return false
        }
        var i = head
        if (gain == 1f) {
            for (k in 0 until frameSize) {
                mix[k] += ring[i]
                i = if (i + 1 == ring.size) 0 else i + 1
            }
        } else {
            for (k in 0 until frameSize) {
                mix[k] += (ring[i] * gain).toInt()
                i = if (i + 1 == ring.size) 0 else i + 1
            }
        }
        head = i
        size -= frameSize
        return true
    }

    fun clear() {
        head = 0
        size = 0
        playing = false
    }

    private fun skip(n: Int) {
        val k = n.coerceAtMost(size)
        head = (head + k) % ring.size
        size -= k
    }
}
