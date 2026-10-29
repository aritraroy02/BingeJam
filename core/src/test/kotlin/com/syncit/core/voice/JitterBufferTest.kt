package com.syncit.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JitterBufferTest {
    private val frame = 4

    private fun samples(from: Int, n: Int) = ShortArray(n) { (from + it).toShort() }

    @Test
    fun waitsForTargetThenPlaysInOrder() {
        val jb = JitterBuffer(targetSamples = 8, maxSamples = 32)
        jb.write(samples(1, 4))
        val mix = IntArray(frame)
        assertFalse(jb.mixInto(mix))
        jb.write(samples(5, 4))
        assertTrue(jb.mixInto(mix))
        assertEquals(listOf(1, 2, 3, 4), mix.toList())
        mix.fill(0)
        assertTrue(jb.mixInto(mix))
        assertEquals(listOf(5, 6, 7, 8), mix.toList())
    }

    @Test
    fun underrunBuffersAgain() {
        val jb = JitterBuffer(targetSamples = 8, maxSamples = 32)
        jb.write(samples(1, 10))
        val mix = IntArray(frame)
        assertTrue(jb.mixInto(mix))
        assertTrue(jb.mixInto(mix))
        assertFalse(jb.mixInto(mix))
        jb.write(samples(100, 4))
        assertFalse(jb.mixInto(mix))
        jb.write(samples(104, 4))
        assertTrue(jb.mixInto(mix))
    }

    @Test
    fun backlogIsCutToTarget() {
        val jb = JitterBuffer(targetSamples = 8, maxSamples = 32)
        jb.write(samples(0, 40))
        assertEquals(8, jb.available)
        val mix = IntArray(frame)
        assertTrue(jb.mixInto(mix))
        assertEquals(listOf(32, 33, 34, 35), mix.toList())
    }

    @Test
    fun mixesSpeakersBySumming() {
        val a = JitterBuffer(4, 16).apply { write(ShortArray(4) { 100 }) }
        val b = JitterBuffer(4, 16).apply { write(ShortArray(4) { 23 }) }
        val mix = IntArray(frame)
        a.mixInto(mix)
        b.mixInto(mix)
        assertEquals(List(4) { 123 }, mix.toList())
    }

    @Test
    fun gainScalesOneSpeaker() {
        val jb = JitterBuffer(4, 16).apply { write(ShortArray(4) { 1_000 }) }
        val mix = IntArray(frame)
        jb.mixInto(mix, gain = 0.5f)
        assertEquals(List(4) { 500 }, mix.toList())
    }
}
