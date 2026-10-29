package com.syncit.core.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceWireTest {

    /** What the relay does: insert the sender's slot after the type byte. */
    private fun relayed(sent: ByteArray, slot: Int): ByteArray =
        byteArrayOf(sent[0], slot.toByte()) + sent.copyOfRange(1, sent.size)

    @Test
    fun roundTripsThroughTheRelay() {
        val a = ByteArray(80) { it.toByte() }
        val b = ByteArray(300) { (255 - it).toByte() }
        val got = VoiceWire.decode(relayed(VoiceWire.encode(seq = 65_535, hd = true, packets = listOf(a, b)), slot = 7))!!
        assertEquals(7, got.slot)
        assertEquals(65_535, got.seq)
        assertTrue(got.hd)
        assertEquals(2, got.packets.size)
        assertArrayEquals(a, got.packets[0])
        assertArrayEquals(b, got.packets[1])
    }

    @Test
    fun sdFlagAndSeqWrap() {
        val got = VoiceWire.decode(relayed(VoiceWire.encode(seq = 70_000, hd = false, packets = listOf(byteArrayOf(1))), 0))!!
        assertFalse(got.hd)
        assertEquals(70_000 and 0xFFFF, got.seq)
    }

    @Test
    fun rejectsMalformed() {
        val good = relayed(VoiceWire.encode(1, true, listOf(byteArrayOf(1, 2, 3))), 1)
        assertNull(VoiceWire.decode(good.copyOf(good.size - 1)))
        assertNull(VoiceWire.decode(good + byteArrayOf(9)))
        assertNull(VoiceWire.decode(byteArrayOf(2) + good.copyOfRange(1, good.size)))
        assertNull(VoiceWire.decode(ByteArray(3)))
    }
}
