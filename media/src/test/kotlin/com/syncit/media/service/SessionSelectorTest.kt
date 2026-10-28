package com.syncit.media.service

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.Member
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.RoomTitle
import com.syncit.core.model.StreamingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionSelectorTest {

    private val selector = SessionSelector()

    private fun session(service: StreamingService, state: LocalPlaybackState = LocalPlaybackState.PLAYING) = MediaSessionInfo(
        sessionKey = "${service.packageName}#1",
        packageName = service.packageName,
        snapshot = PlaybackSnapshot(state, 0, 0L, 1L, 1f, emptySet(), 0L),
        metadata = NowPlayingMetadata("Title", null, null),
    )

    /** [joined] = another member has joined (locks the room's kind). */
    private fun room(title: StreamingService? = null, joined: Boolean = false) = Room(
        "ABC123", "host", 0L, title?.let { RoomTitle(it, "https://example.com", "host") }, null,
        listOfNotNull(Member("host", "Host", online = true), Member("friend", "Friend", online = true).takeIf { joined }),
        null,
    )

    private fun preview(service: StreamingService) = RoomPreview("ABC123", "Host", service, "Title", null, null, 0L)

    private fun hostPick(
        vararg sessions: MediaSessionInfo,
        preview: RoomPreview? = null,
        title: StreamingService? = null,
        joined: Boolean = true,
    ) = selector.pick(sessions.toList(), room(title, joined), preview, isHost = true)?.packageName

    @Test fun watchPartyHostIgnoresMusicAndYouTube() {
        val netflix = StreamingService.NETFLIX.packageName
        // Host paused Netflix and is now playing YouTube (most recently active first).
        val picked = hostPick(
            session(StreamingService.YOUTUBE),
            session(StreamingService.NETFLIX, LocalPlaybackState.PAUSED),
            preview = preview(StreamingService.NETFLIX),
        )
        assertEquals(netflix, picked)
        assertNull(hostPick(session(StreamingService.SPOTIFY), preview = preview(StreamingService.NETFLIX)))
    }

    @Test fun jamHostIgnoresVideoAppsButCanSwitchMusicApps() {
        val picked = hostPick(
            session(StreamingService.NETFLIX),
            session(StreamingService.YOUTUBE_MUSIC),
            preview = preview(StreamingService.SPOTIFY),
        )
        assertEquals(StreamingService.YOUTUBE_MUSIC.packageName, picked)
    }

    @Test fun titleDecidesAndCanChangeTheKind() {
        val picked = hostPick(
            session(StreamingService.NETFLIX),
            session(StreamingService.SPOTIFY),
            preview = preview(StreamingService.NETFLIX),
            title = StreamingService.SPOTIFY,
        )
        assertEquals(StreamingService.SPOTIFY.packageName, picked)
    }

    @Test fun emptyRoom_pausedBackgroundAppCantDecideTheKind() {
        assertNull(hostPick(session(StreamingService.SPOTIFY, LocalPlaybackState.PAUSED), joined = false))
        assertNull(selector.roomIsJam(room(), null))
    }

    @Test fun aloneHost_kindFollowsTheAppMostRecentlyPlayed() {
        // Spotify already playing when the room starts: a jam for now...
        assertEquals(StreamingService.SPOTIFY.packageName, hostPick(session(StreamingService.SPOTIFY), joined = false))
        assertEquals(true, selector.roomIsJam(room(), preview(StreamingService.SPOTIFY)))
        // ...the host pauses it and plays Netflix: a watch party, even though the preview still says Spotify.
        assertEquals(
            StreamingService.NETFLIX.packageName,
            hostPick(
                session(StreamingService.NETFLIX),
                session(StreamingService.SPOTIFY, LocalPlaybackState.PAUSED),
                preview = preview(StreamingService.SPOTIFY),
                joined = false,
            ),
        )
        assertEquals(false, selector.roomIsJam(room(), preview(StreamingService.SPOTIFY)))
        // Nothing playing: the last kind holds.
        assertEquals(
            StreamingService.NETFLIX.packageName,
            hostPick(
                session(StreamingService.SPOTIFY, LocalPlaybackState.PAUSED),
                session(StreamingService.NETFLIX, LocalPlaybackState.PAUSED),
                joined = false,
            ),
        )
    }

    @Test fun kindLocksOnceSomeoneJoinedAndStaysLocked() {
        assertEquals(StreamingService.NETFLIX.packageName, hostPick(session(StreamingService.NETFLIX), joined = false))
        assertEquals(
            StreamingService.NETFLIX.packageName,
            hostPick(session(StreamingService.SPOTIFY), session(StreamingService.NETFLIX, LocalPlaybackState.PAUSED), joined = true),
        )
        // The friend left: still locked (they joined a watch party).
        assertEquals(
            StreamingService.NETFLIX.packageName,
            hostPick(session(StreamingService.SPOTIFY), session(StreamingService.NETFLIX, LocalPlaybackState.PAUSED), joined = false),
        )
    }

    @Test fun joinedBeforeHostPlayed_firstPlayedAppDecides() {
        assertNull(hostPick(session(StreamingService.SPOTIFY, LocalPlaybackState.PAUSED), joined = true))
        assertEquals(StreamingService.SPOTIFY.packageName, hostPick(session(StreamingService.SPOTIFY), joined = true))
        assertEquals(
            "now locked as a jam",
            StreamingService.SPOTIFY.packageName,
            hostPick(session(StreamingService.NETFLIX), session(StreamingService.SPOTIFY, LocalPlaybackState.PAUSED), joined = true),
        )
    }

    @Test fun membersFollowThePreviewsApp() {
        val picked = selector.pick(
            listOf(session(StreamingService.SPOTIFY), session(StreamingService.NETFLIX)),
            room(),
            preview(StreamingService.NETFLIX),
            isHost = false,
        )
        assertEquals(StreamingService.NETFLIX.packageName, picked?.packageName)
    }

    @Test fun membersIgnoreMusicUntilTheHostsAppIsKnown() {
        assertNull(selector.pick(listOf(session(StreamingService.SPOTIFY)), room(), null, isHost = false))
        assertEquals(
            StreamingService.PRIME_VIDEO.packageName,
            selector.pick(listOf(session(StreamingService.SPOTIFY), session(StreamingService.PRIME_VIDEO)), room(), null, isHost = false)?.packageName,
        )
    }
}
