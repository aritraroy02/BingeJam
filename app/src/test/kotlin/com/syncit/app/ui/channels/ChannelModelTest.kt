package com.syncit.app.ui.channels

import com.syncit.core.social.ChannelKind
import com.syncit.core.social.GroupChannel
import com.syncit.core.social.VoiceOccupant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelModelTest {
    private fun ch(id: String, kind: ChannelKind, order: Double, at: Long = 0) = GroupChannel(id, id, kind, order, at)

    @Test fun builtInFirstThenOrderThenOldest() {
        val (text, voice) = ChannelModel.list(
            listOf(ch("b", ChannelKind.TEXT, 1.0), ch("a", ChannelKind.TEXT, 0.0, 5), ch("z", ChannelKind.TEXT, 0.0, 1), ch("g", ChannelKind.VOICE, 0.0)),
        )
        assertEquals(listOf("general", "z", "a", "b"), text.map { it.id })
        assertEquals(listOf("lounge", "g"), voice.map { it.id })
        assertTrue(text.first().builtIn)
    }

    @Test fun unreadOnlyForOthersNewerMessages() {
        assertTrue(ChannelModel.unread("riya", 10, 5, "me"))
        assertFalse(ChannelModel.unread("me", 10, 5, "me"))
        assertFalse(ChannelModel.unread("riya", 5, 5, "me"))
        assertFalse(ChannelModel.unread(null, 0, 0, "me"))
    }

    @Test fun callTimerFromTheFirstOneStillIn() {
        val people = listOf(VoiceOccupant("a", "A", 10_000, false, false, false), VoiceOccupant("b", "B", 4_000, false, false, false))
        assertEquals("4:07", ChannelModel.callElapsed(people, 4_000 + 247_000))
        assertEquals("1:15:05", ChannelModel.callElapsed(people, 4_000 + 4_505_000))
        assertNull(ChannelModel.callElapsed(emptyList(), 0))
    }

    @Test fun voiceServerLineLikeThePc() {
        assertEquals("Singapore · 42 ms", ChannelModel.serverLine("SIN", 42))
        assertEquals("XYZ", ChannelModel.serverLine("XYZ", null))
        assertEquals("Finding a voice server", ChannelModel.serverLine(null, null))
    }
}
