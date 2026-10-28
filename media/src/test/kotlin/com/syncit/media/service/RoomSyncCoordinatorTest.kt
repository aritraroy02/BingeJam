package com.syncit.media.service

import com.syncit.core.media.MediaControllerGateway
import com.syncit.core.model.ChatMessage
import com.syncit.core.model.EngineOutput
import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.MediaAction
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.Member
import com.syncit.core.model.NowPlayingMetadata
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.Reaction
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.RoomState
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.JamFollow
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncSessionState
import com.syncit.core.sync.ServerClock
import com.syncit.core.sync.SyncEngine
import com.syncit.core.sync.SyncEngineFactory
import com.syncit.core.transport.SyncTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * evaluate()-level tests: which session the engine sees, re-attach on app switch, the jam jump
 * hold and item changes. Runs on Dispatchers.Unconfined so every flow update is handled
 * synchronously; the tick and the hold re-check are driven by hand with a fake clock.
 */
class RoomSyncCoordinatorTest {

    /** Fake SystemClock.elapsedRealtime(). */
    private var now = 1_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val refA = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
    private val refB = "spotify:track:BBBBBBBBBBBBBBBBBBBBBB"
    private val refC = "spotify:track:CCCCCCCCCCCCCCCCCCCCCC"

    // ---- Fakes ----

    private val engine = FakeEngine()
    private val gateway = FakeGateway()
    private val transport = FakeTransport()
    private val published = mutableListOf<SyncSessionState>()

    private fun coordinator(myUid: String): RoomSyncCoordinator {
        transport.uid = myUid
        val deps = object : SyncDependencies {
            override val gateway: MediaControllerGateway = this@RoomSyncCoordinatorTest.gateway
            override val transport: SyncTransport = this@RoomSyncCoordinatorTest.transport
            override val engineFactory = SyncEngineFactory { _, _ -> engine }
        }
        return RoomSyncCoordinator(
            deps = deps,
            scope = scope,
            publish = { published += it },
            elapsedRealtimeMs = { now },
            tickMs = Long.MAX_VALUE,
            recheckDelay = { awaitCancellation() },
        ).also {
            it.start(CODE)
            // Starting feeds "no session" once (nothing picked yet); tests look at what follows.
            engine.events.clear()
        }
    }

    @After fun tearDown() = scope.cancel()

    private fun session(
        service: StreamingService,
        position: Long,
        state: LocalPlaybackState = LocalPlaybackState.PLAYING,
        title: String = "Song A",
        ref: String? = null,
        actions: Set<MediaAction> = setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.SEEK_TO),
    ) = MediaSessionInfo(
        sessionKey = "${service.packageName}#1",
        packageName = service.packageName,
        snapshot = PlaybackSnapshot(state, 0, position, now, 1f, actions, 0L),
        metadata = NowPlayingMetadata(title, null, null, playableRef = ref),
    )

    /** [others] = members besides the host who have joined. */
    private fun room(host: String = HOST, others: List<String> = emptyList()) =
        Room(CODE, host, 0L, null, null, (listOf(host) + others).map { Member(it, it, online = true) }, null)

    private fun preview(service: StreamingService, title: String = "Song A", ref: String? = null) =
        RoomPreview(CODE, "Host", service, title, null, null, 0L, null, ref)

    // ---- Item 1: the room's kind ----

    @Test fun watchPartyHost_youtubeOrSpotifyCantTakeOverOnceSomeoneJoined() {
        transport.room.value = room(others = listOf(MEMBER))
        transport.preview.value = preview(StreamingService.NETFLIX, title = "Movie")
        val c = coordinator(HOST)
        val netflix = session(StreamingService.NETFLIX, 600_000, LocalPlaybackState.PAUSED, title = "Movie")
        gateway.sessions.value = listOf(netflix)
        assertEquals(listOf("snap:600000"), engine.events)

        // Host pauses Netflix and plays YouTube, which is now the most recently active session.
        gateway.sessions.value = listOf(session(StreamingService.YOUTUBE, 5_000, title = "Video"), netflix)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 9_000), netflix)
        assertEquals("the engine never saw YouTube or Spotify", listOf("snap:600000"), engine.events)
        assertEquals(StreamingService.NETFLIX, c.hostNowPlaying.value?.service)
    }

    @Test fun switchingAppsIsAReattachNotAJump() {
        transport.room.value = room()
        transport.preview.value = preview(StreamingService.SPOTIFY)
        coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 200_000))
        gateway.sessions.value = listOf(session(StreamingService.YOUTUBE_MUSIC, 5_000, title = "Song A"))
        // Another app is a new item for the host (its own state becomes the room's), and the
        // engine sees it as a re-attach, never as a jump in the old app.
        assertEquals(listOf("snap:200000", "item:leader", "snap:null", "snap:5000"), engine.events)
    }

    @Test fun aloneHost_spotifyAlreadyPlayingThenNetflix_isAWatchParty() {
        transport.room.value = room()
        val c = coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 90_000))
        assertEquals(StreamingService.SPOTIFY, c.hostNowPlaying.value?.service)

        // Host pauses Spotify and starts Netflix: the room follows, and the preview changes at once.
        val spotifyPaused = session(StreamingService.SPOTIFY, 95_000, LocalPlaybackState.PAUSED)
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 0, title = "Movie"), spotifyPaused)
        assertEquals(listOf("snap:90000", "item:leader", "snap:null", "snap:0"), engine.events)
        assertEquals(StreamingService.NETFLIX, c.hostNowPlaying.value?.service)
        assertEquals("new item: published without the debounce", 1, c.hostNowPlaying.value?.item)

        // Netflix paused, Spotify paused too: still the watch party (Netflix first in order or not).
        gateway.sessions.value = listOf(spotifyPaused, session(StreamingService.NETFLIX, 1_000, LocalPlaybackState.PAUSED, title = "Movie"))
        assertEquals(StreamingService.NETFLIX, c.hostNowPlaying.value?.service)
    }

    @Test fun afterAMemberJoined_switchingToSpotifyDoesntChangeTheKind() {
        transport.room.value = room()
        val c = coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 10_000, title = "Movie"))
        transport.room.value = room(others = listOf(MEMBER))

        val netflixPaused = session(StreamingService.NETFLIX, 12_000, LocalPlaybackState.PAUSED, title = "Movie")
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 50_000), netflixPaused)
        assertEquals(listOf("snap:10000", "snap:12000"), engine.events)
        assertEquals(StreamingService.NETFLIX, c.hostNowPlaying.value?.service)
    }

    @Test fun memberJoinedBeforeHostPlayed_firstPlayedAppStillDecides() {
        transport.room.value = room(others = listOf(MEMBER))
        val c = coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 1_000, LocalPlaybackState.PAUSED))
        assertEquals("a paused app doesn't decide", emptyList<String>(), engine.events)
        gateway.sessions.value = listOf(session(StreamingService.PRIME_VIDEO, 0, title = "Film"))
        assertEquals(StreamingService.PRIME_VIDEO, c.hostNowPlaying.value?.service)
    }

    @Test fun watchPartyHost_netflixToPrime_isALeaderItemChange() {
        transport.room.value = room(others = listOf(MEMBER))
        transport.preview.value = preview(StreamingService.NETFLIX, title = "Movie")
        val c = coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 600_000, title = "Movie"))
        assertEquals(0, c.hostNowPlaying.value?.item)

        val netflixPaused = session(StreamingService.NETFLIX, 601_000, LocalPlaybackState.PAUSED, title = "Movie")
        gateway.sessions.value = listOf(session(StreamingService.PRIME_VIDEO, 30_000, title = "Film"), netflixPaused)
        // Not a plain re-attach (which would align Prime to Netflix's room state): the engine
        // restarts the timeline and broadcasts the host's Prime state.
        assertEquals(listOf("snap:600000", "item:leader", "snap:null", "snap:30000"), engine.events)
        assertEquals(StreamingService.PRIME_VIDEO, c.hostNowPlaying.value?.service)
        assertEquals(1, c.hostNowPlaying.value?.item)
    }

    @Test fun watchPartyHost_nextEpisodeIsALeaderItemChange() {
        transport.room.value = room(others = listOf(MEMBER))
        transport.preview.value = preview(StreamingService.NETFLIX, title = "Ep 1")
        coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 2_900_000, title = "Ep 1"))
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 0, title = "Ep 2"))
        assertEquals(listOf("snap:2900000", "item:leader", "snap:0"), engine.events)
    }

    // ---- Item 2: jump to the start before the new track's metadata ----

    @Test fun host_trackChangeWithStateBeforeMetadata_noSeekToZeroForTheOldTrack() {
        transport.room.value = room()
        transport.preview.value = preview(StreamingService.SPOTIFY, ref = refA)
        coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 200_000, ref = refA))

        // The app publishes the next track's state (0) while its metadata is still Song A's.
        now += 50
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 0, ref = refA))
        assertEquals("held back", listOf("snap:200000"), engine.events)

        // Metadata follows: the engine hears about the new track before its first reading.
        now += 100
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 0, title = "Song B", ref = refB))
        assertEquals(listOf("snap:200000", "item:leader", "snap:0"), engine.events)
    }

    @Test fun host_realSeekToStartIsReleasedAfterTheHold() {
        transport.room.value = room()
        transport.preview.value = preview(StreamingService.SPOTIFY, ref = refA)
        val c = coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 120_000, ref = refA))
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 0, ref = refA))
        assertEquals(listOf("snap:120000"), engine.events)

        now += 800
        c.tick()
        assertEquals(listOf("snap:120000", "tick", "snap:0"), engine.events)
    }

    @Test fun member_earlyAutoAdvanceNeverReachesTheEngine() {
        transport.room.value = room()
        transport.preview.value = preview(StreamingService.SPOTIFY, ref = refA)
        coordinator(MEMBER)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 200_000, ref = refA))
        assertEquals(listOf("snap:200000"), engine.events)

        // Member's app moves on a moment before the host's: state first, then its own next song.
        now += 50
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 0, ref = refA))
        now += 100
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 0, title = "Song C", ref = refC))
        assertEquals("hidden, never a SEEK@0 on the host's song", listOf("snap:200000", "snap:null"), engine.events)
    }

    // ---- Item 3 + jam follow wiring ----

    @Test fun host_refArrivingAfterTitleIsNotATrackChange() {
        transport.room.value = room()
        transport.preview.value = preview(StreamingService.SPOTIFY)
        val c = coordinator(HOST)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 10_000))
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 10_000, ref = refA))
        assertFalse(engine.events.any { it.startsWith("item") })
        assertEquals(0, c.hostNowPlaying.value?.item)
    }

    @Test fun member_hostTrackChangeRestartsTimelineAndStartsTheTrack() {
        val uriActions = setOf(MediaAction.PLAY, MediaAction.PAUSE, MediaAction.SEEK_TO, MediaAction.PLAY_FROM_URI)
        transport.room.value = room()
        transport.preview.value = preview(StreamingService.SPOTIFY, ref = refA)
        coordinator(MEMBER)
        gateway.sessions.value = listOf(session(StreamingService.SPOTIFY, 30_000, ref = refA, actions = uriActions))

        transport.preview.value = preview(StreamingService.SPOTIFY, title = "Song B", ref = refB)
        assertEquals(listOf("snap:30000", "item:member", "snap:null"), engine.events)
        assertEquals(listOf("uri:$refB"), gateway.commands)
        assertEquals(JamFollow.STARTING, published.last().jamFollow)
        assertEquals(SyncStatus.SYNCING, published.last().status)
    }

    @Test fun watchPartyMember_hostsNextEpisode_hiddenUntilOnItThenAligns() {
        transport.room.value = room(others = listOf(MEMBER))
        transport.preview.value = preview(StreamingService.NETFLIX, title = "Ep 1")
        coordinator(MEMBER)
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 10_000, title = "Ep 1"))

        transport.preview.value = preview(StreamingService.NETFLIX, title = "Ep 2")
        assertEquals(listOf("snap:10000", "item:member", "snap:null"), engine.events)
        assertEquals(SyncStatus.OTHER_TITLE, published.last().status)

        // Member's Netflix moves on to Ep 2: attached again; the engine aligns to the host's write.
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 0, title = "Ep 2"))
        assertEquals(listOf("snap:10000", "item:member", "snap:null", "snap:0"), engine.events)
        assertEquals(SyncStatus.SYNCING, published.last().status)
        assertEquals("only once per new item", 1, engine.events.count { it.startsWith("item") })
    }

    // ---- Fakes ----

    private class FakeEngine : SyncEngine {
        val events = mutableListOf<String>()
        override val mode = SyncMode.FULL
        override val status = SyncStatus.SYNCING
        override val lastDriftMs: Long? = null

        override fun onLocalSnapshot(snapshot: PlaybackSnapshot?): EngineOutput {
            events += "snap:${snapshot?.positionMs}"
            return EngineOutput.NONE
        }

        override fun onRoom(state: RoomState?, members: List<Member>) = EngineOutput.NONE

        override fun onTick(): EngineOutput {
            events += "tick"
            return EngineOutput.NONE
        }

        override fun onItemChanged(leader: Boolean): EngineOutput {
            events += if (leader) "item:leader" else "item:member"
            return EngineOutput.NONE
        }
    }

    private class FakeGateway : MediaControllerGateway {
        val commands = mutableListOf<String>()
        override val notificationAccessGranted: StateFlow<Boolean> = MutableStateFlow(true)
        override val sessions = MutableStateFlow<List<MediaSessionInfo>>(emptyList())
        override fun refresh() = Unit
        override fun play(sessionKey: String) = true.also { commands += "play" }
        override fun pause(sessionKey: String) = true.also { commands += "pause" }
        override fun seekTo(sessionKey: String, positionMs: Long) = true.also { commands += "seek:$positionMs" }
        override fun playFromUri(sessionKey: String, uri: String) = true.also { commands += "uri:$uri" }
        override fun playFromSearch(sessionKey: String, query: String) = true.also { commands += "search:$query" }
    }

    private class FakeTransport : SyncTransport {
        var uid = HOST
        val room = MutableStateFlow<Room?>(null)
        val preview = MutableStateFlow<RoomPreview?>(null)
        override val currentUid: StateFlow<String?> = MutableStateFlow(null)
        override val connected: StateFlow<Boolean> = MutableStateFlow(true)
        override val serverClock = object : ServerClock {
            override fun serverNowMs() = 0L
        }
        override suspend fun ensureSignedIn() = uid
        override suspend fun createRoom(displayName: String) = CODE
        override suspend fun joinRoom(code: String, displayName: String) = Unit
        override suspend fun leaveRoom(code: String) = Unit
        override fun observeRoom(code: String): Flow<Room?> = room
        override suspend fun writeState(code: String, broadcast: StateBroadcast) = Unit
        override suspend fun reportPosition(code: String, positionMs: Long) = Unit
        override suspend fun setTitle(code: String, service: StreamingService, deepLink: String) = Unit
        override fun observePreview(code: String): Flow<RoomPreview?> = preview
        override suspend fun setNowPlaying(code: String, service: StreamingService, metadata: NowPlayingMetadata) = Unit
        override suspend fun startCountdown(code: String, leadMs: Long) = Unit
        override fun observeChat(code: String, limit: Int): Flow<List<ChatMessage>> = emptyFlow()
        override suspend fun sendChat(code: String, displayName: String, text: String) = Unit
        override fun observeReactions(code: String, sinceServerMs: Long): Flow<Reaction> = emptyFlow()
        override suspend fun sendReaction(code: String, displayName: String, emoji: String) = Unit
    }

    private companion object {
        const val CODE = "ABC123"
        const val HOST = "host"
        const val MEMBER = "member"
    }
}
