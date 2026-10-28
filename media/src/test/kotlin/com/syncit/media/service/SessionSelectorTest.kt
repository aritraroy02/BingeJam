package com.syncit.media.service

import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaSessionInfo
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

    private fun room(title: StreamingService? = null) =
        Room("ABC123", "host", 0L, title?.let { RoomTitle(it, "https://example.com", "host") }, null, emptyList(), null)

    private fun preview(service: StreamingService) = RoomPreview("ABC123", "Host", service, "Title", null, null, 0L)

    private fun hostPick(vararg sessions: MediaSessionInfo, preview: RoomPreview? = null, title: StreamingService? = null) =
        selector.pick(sessions.toList(), room(title), preview, isHost = true)?.packageName

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
        assertNull(hostPick(session(StreamingService.SPOTIFY, LocalPlaybackState.PAUSED)))
        assertNull(selector.roomIsJam(room(), null))
    }

    @Test fun emptyRoom_firstPlayedAppDecidesTheKind() {
        assertEquals(
            StreamingService.NETFLIX.packageName,
            hostPick(session(StreamingService.NETFLIX), session(StreamingService.SPOTIFY, LocalPlaybackState.PAUSED)),
        )
        assertEquals(false, selector.roomIsJam(room(), null))
        // Netflix paused, Spotify now playing and first in order: still a watch party.
        assertEquals(
            StreamingService.NETFLIX.packageName,
            hostPick(session(StreamingService.SPOTIFY), session(StreamingService.NETFLIX, LocalPlaybackState.PAUSED)),
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
