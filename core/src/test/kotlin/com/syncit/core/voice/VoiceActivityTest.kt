package com.syncit.core.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceActivityTest {
    private fun tone(amplitude: Int) = ShortArray(320) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }

    @Test
    fun quietIsNotSentLoudIsThenHangsOver() {
        val vad = VoiceActivity(hangoverFrames = 3)
        repeat(50) { assertFalse(vad.isVoiced(tone(50))) }
        assertTrue(vad.isVoiced(tone(4_000)))
        repeat(3) { assertTrue(vad.isVoiced(tone(50))) }
        assertFalse(vad.isVoiced(tone(50)))
    }

    @Test
    fun friendsEchoDoesNotCount() {
        val vad = VoiceActivity(hangoverFrames = 0)
        assertFalse(vad.isVoiced(tone(1_500), playbackLevel = 6_000.0))
        assertTrue(vad.isVoiced(tone(6_000), playbackLevel = 6_000.0))
    }
}
