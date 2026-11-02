package com.syncit.app.ui.room

import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.JamFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberPromptTest {
    private fun member(service: StreamingService?, status: SyncStatus, follow: JamFollow = JamFollow.IDLE) =
        MemberPrompts.of(isHost = false, service = service, status = status, jamFollow = follow)

    @Test fun hostAndUnknownServiceNeverPrompt() {
        assertEquals(MemberPrompt.NONE, MemberPrompts.of(true, StreamingService.SPOTIFY, SyncStatus.APP_NOT_OPEN, JamFollow.NEEDS_APP))
        assertEquals(MemberPrompt.NONE, member(null, SyncStatus.APP_NOT_OPEN))
    }

    @Test fun watchPartyKeepsThePlayButton() {
        assertEquals(MemberPrompt.OPEN_APP, member(StreamingService.NETFLIX, SyncStatus.APP_NOT_OPEN))
        assertEquals(MemberPrompt.OPEN_APP, member(StreamingService.NETFLIX, SyncStatus.SESSION_LOST))
        assertEquals(MemberPrompt.OPEN_APP, member(StreamingService.PRIME_VIDEO, SyncStatus.OTHER_TITLE))
        assertEquals(MemberPrompt.NONE, member(StreamingService.JIOHOTSTAR, SyncStatus.SYNCING))
        // jamFollow is meaningless outside jams.
        assertEquals(MemberPrompt.NONE, member(StreamingService.NETFLIX, SyncStatus.SYNCING, JamFollow.FAILED))
    }

    @Test fun jamPrompts() {
        val s = StreamingService.SPOTIFY
        assertEquals(MemberPrompt.STARTING, member(s, SyncStatus.OTHER_TITLE, JamFollow.STARTING))
        assertEquals(MemberPrompt.OPEN_APP, member(s, SyncStatus.APP_NOT_OPEN, JamFollow.NEEDS_APP))
        assertEquals(MemberPrompt.OPEN_APP, member(s, SyncStatus.SESSION_LOST))
        assertEquals(MemberPrompt.BACK_TO_JAM, member(s, SyncStatus.SYNCING, JamFollow.FAILED))
        assertEquals(MemberPrompt.BACK_TO_JAM, member(StreamingService.YOUTUBE, SyncStatus.OTHER_TITLE))
        assertEquals(MemberPrompt.NONE, member(s, SyncStatus.SYNCING))
    }

    @Test fun jamStatusTalksAboutTracks() {
        assertEquals("On a different track", MemberPrompts.statusLabel(SyncStatus.OTHER_TITLE, StreamingService.SPOTIFY))
        assertEquals("Not on the same title", MemberPrompts.statusLabel(SyncStatus.OTHER_TITLE, StreamingService.NETFLIX))
        assertEquals("Syncing", MemberPrompts.statusLabel(SyncStatus.SYNCING, StreamingService.SPOTIFY))
    }

    @Test fun autoOpenKeyPrefersRefThenTitle() {
        assertEquals("ABC123|spotify:track:1", JamAutoOpen.key("ABC123", " spotify:track:1 ", "Song"))
        assertEquals("ABC123|Song", JamAutoOpen.key("ABC123", " ", "Song"))
        assertNull(JamAutoOpen.key("ABC123", null, "  "))
    }

    @Test fun autoOpenOncePerKeyOnlyForJamMembersWhoNeedTheApp() {
        val key = JamAutoOpen.key("ABC123", "spotify:track:1", "Song")
        fun open(
            resumed: Boolean = true,
            isHost: Boolean = false,
            service: StreamingService? = StreamingService.SPOTIFY,
            follow: JamFollow = JamFollow.NEEDS_APP,
            k: String? = key,
            done: Set<String> = emptySet(),
        ) = JamAutoOpen.shouldOpen(resumed, isHost, service, follow, k, done)

        assertTrue(open())
        // Already opened for this room + track: never again (no loop, not on coming back).
        assertFalse(open(done = setOf(key!!)))
        // The host moved on to another track: a new key, so once more.
        assertTrue(open(k = JamAutoOpen.key("ABC123", "spotify:track:2", "Next"), done = setOf(key)))
        assertFalse(open(resumed = false))
        assertFalse(open(isHost = true))
        assertFalse(open(service = StreamingService.NETFLIX))
        assertFalse(open(service = null))
        assertFalse(open(follow = JamFollow.IDLE))
        assertFalse(open(follow = JamFollow.STARTING))
        assertFalse(open(follow = JamFollow.FAILED))
        assertFalse(open(k = null))
    }

    @Test fun onlyPublicRefsOnTheServicesOwnHosts() {
        assertEquals("spotify:track:4uLU6hMCjMI75M1A2tKUQC", MemberPrompts.safePlayableRef(StreamingService.SPOTIFY, " spotify:track:4uLU6hMCjMI75M1A2tKUQC "))
        assertEquals(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            MemberPrompts.safePlayableRef(StreamingService.YOUTUBE, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
        )
        assertEquals(
            "https://music.youtube.com/watch?v=abc",
            MemberPrompts.safePlayableRef(StreamingService.YOUTUBE_MUSIC, "https://music.youtube.com/watch?v=abc"),
        )
        // Wrong app, wrong host, non-https, junk, nothing.
        assertNull(MemberPrompts.safePlayableRef(StreamingService.YOUTUBE, "spotify:track:abc"))
        assertNull(MemberPrompts.safePlayableRef(StreamingService.YOUTUBE, "https://evil.example/watch?v=abc"))
        assertNull(MemberPrompts.safePlayableRef(StreamingService.YOUTUBE, "https://notyoutube.com/watch?v=abc"))
        assertNull(MemberPrompts.safePlayableRef(StreamingService.YOUTUBE, "http://www.youtube.com/watch?v=abc"))
        assertNull(MemberPrompts.safePlayableRef(StreamingService.SPOTIFY, "intent://x#Intent;end"))
        assertNull(MemberPrompts.safePlayableRef(StreamingService.SPOTIFY, "not a uri"))
        assertNull(MemberPrompts.safePlayableRef(StreamingService.SPOTIFY, null))
        assertNull(MemberPrompts.safePlayableRef(StreamingService.SPOTIFY, "spotify:track:" + "a".repeat(600)))
    }

    @Test fun hostGetsPlayOnlyForARoomWithALinkBeforePlaying() {
        val netflix = StreamingService.NETFLIX
        assertEquals(MemberPrompt.OPEN_APP, MemberPrompts.of(true, netflix, SyncStatus.APP_NOT_OPEN, JamFollow.IDLE, hostHasLink = true))
        assertEquals(MemberPrompt.NONE, MemberPrompts.of(true, netflix, SyncStatus.APP_NOT_OPEN, JamFollow.IDLE, hostHasLink = false))
        assertEquals(MemberPrompt.NONE, MemberPrompts.of(true, netflix, SyncStatus.SYNCING, JamFollow.IDLE, hostHasLink = true))
        assertEquals(MemberPrompt.NONE, MemberPrompts.of(true, StreamingService.SPOTIFY, SyncStatus.APP_NOT_OPEN, JamFollow.IDLE, hostHasLink = true))
    }
}
