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

    private fun room(host: String = HOST) = Room(CODE, host, 0L, null, null, emptyList(), null)

    private fun preview(service: StreamingService, title: String = "Song A", ref: String? = null) =
        RoomPreview(CODE, "Host", service, title, null, null, 0L, null, ref)

    // ---- Item 1: the room's kind ----

    @Test fun watchPartyHost_youtubeOrSpotifyCantTakeOver() {
        transport.room.value = room()
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
        assertEquals(listOf("snap:200000", "snap:null", "snap:5000"), engine.events)
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

    @Test fun watchPartyNeverCallsOnItemChanged() {
        transport.room.value = room()
        transport.preview.value = preview(StreamingService.NETFLIX, title = "Ep 1")
        coordinator(MEMBER)
        gateway.sessions.value = listOf(session(StreamingService.NETFLIX, 10_000, title = "Ep 1"))
        transport.preview.value = preview(StreamingService.NETFLIX, title = "Ep 2")
        assertTrue(engine.events.none { it.startsWith("item") })
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
