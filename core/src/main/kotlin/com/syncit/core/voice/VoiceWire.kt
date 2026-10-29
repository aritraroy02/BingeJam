package com.syncit.core.voice

/**
 * Binary audio messages (see push-relay/src/voice.js). Phone → relay:
 * `[1][seq u16][flags u8][count u8]` then count × `[len u16][opus packet]`. The relay inserts the
 * sender's slot after the first byte before passing it on. Big-endian; flags bit 0 = HD.
 */
object VoiceWire {
    const val AUDIO: Int = 1
    const val FLAG_HD: Int = 1
    private const val MAX_PACKET = 1_500

    class Incoming(val slot: Int, val seq: Int, val hd: Boolean, val packets: List<ByteArray>)

    fun encode(seq: Int, hd: Boolean, packets: List<ByteArray>): ByteArray {
        require(packets.size in 1..255)
        val size = 5 + packets.sumOf { 2 + it.size }
        val out = ByteArray(size)
        out[0] = AUDIO.toByte()
        out[1] = (seq shr 8).toByte()
        out[2] = seq.toByte()
        out[3] = (if (hd) FLAG_HD else 0).toByte()
        out[4] = packets.size.toByte()
        var i = 5
        for (p in packets) {
            require(p.size <= MAX_PACKET)
            out[i++] = (p.size shr 8).toByte()
            out[i++] = p.size.toByte()
            p.copyInto(out, i)
            i += p.size
        }
        return out
    }

    /** A message from the relay (slot included), or null when it's malformed. */
    fun decode(data: ByteArray): Incoming? {
        if (data.size < 6 || u8(data, 0) != AUDIO) return null
        val slot = u8(data, 1)
        val seq = (u8(data, 2) shl 8) or u8(data, 3)
        val hd = u8(data, 4) and FLAG_HD != 0
        val count = u8(data, 5)
        val packets = ArrayList<ByteArray>(count)
        var i = 6
        repeat(count) {
            if (i + 2 > data.size) return null
            val len = (u8(data, i) shl 8) or u8(data, i + 1)
            i += 2
            if (len == 0 || len > MAX_PACKET || i + len > data.size) return null
            packets += data.copyOfRange(i, i + len)
            i += len
        }
        return if (i == data.size) Incoming(slot, seq, hd, packets) else null
    }

    private fun u8(data: ByteArray, i: Int): Int = data[i].toInt() and 0xFF
}
