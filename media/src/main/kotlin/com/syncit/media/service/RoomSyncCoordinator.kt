package com.syncit.media.service

import android.os.SystemClock
import android.util.Log
import com.syncit.core.model.EngineOutput
import com.syncit.core.model.MediaSessionInfo
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService
import com.syncit.core.model.SyncCommand
import com.syncit.core.model.SyncStatus
import com.syncit.core.session.JamFollow
import com.syncit.core.session.SyncDependencies
import com.syncit.core.session.SyncSessionState
import com.syncit.core.sync.SyncClock
import com.syncit.core.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wires the gateway, the transport and a [SyncEngine] together for one room. Runs entirely on
 * the [scope]'s dispatcher (main thread), so the engine is only ever called from one thread.
 * Depends only on :core interfaces.
 *
 * Every input (sessions, room, preview, the 1 s tick, "Back to the jam") goes through [evaluate],
 * which decides which session the engine may see:
 *  - watch-party member on a different title/app: hidden (OTHER_TITLE), as before jams;
 *  - jam member: [JamFollower] starts the host's track and hides the app until it's on it;
 *  - jam host: always visible.
 * Hiding = the engine sees "no session" (it detaches); un-hiding = re-attach, which the engine
 * handles as a late join (align to the room's position, ignore the app's start-up changes).
 *
 * Jams: a new track is a new timeline. When the host's track changes, the host's engine gets
 * onItemChanged(leader = true) and, once the app has settled on the new track, broadcasts its
 * position as the room's. When the host's track (the preview) changes, members' engines get
 * onItemChanged(leader = false) and align to the host's write for the new track. Watch parties
 * never call it.
 */
internal class RoomSyncCoordinator(
    private val deps: SyncDependencies,
    private val scope: CoroutineScope,
    private val publish: (SyncSessionState) -> Unit,
) {
    private var job: Job? = null
    private var roomCode: String? = null
    private var uid: String? = null
    private var engine: SyncEngine? = null
    private var room: Room? = null
    private var sessions: List<MediaSessionInfo> = emptyList()
    private var preview: RoomPreview? = null

    /** The session the engine currently sees (null = none, or hidden). Commands go to it only. */
    private var session: MediaSessionInfo? = null
    private var fedEngine = false

    /** Set by [evaluate] when the engine's own status doesn't tell the story (app hidden). */
    private var statusOverride: SyncStatus? = null
    private var jamFollow = JamFollow.IDLE

    /** The room's app, for the UI, while a jam member's app is hidden or missing. */
    private var serviceHint: StreamingService? = null

    private val follower = JamFollower()
    /** Host: the host's own jam track. Member: the host's track from the preview. */
    private val hostItem = ItemKeyTracker()
    private val previewItem = ItemKeyTracker()
    private var state = SyncSessionState()

    fun start(code: String) {
        if (code == roomCode && job?.isActive == true) return
        stop()
        roomCode = code
        state = SyncSessionState(roomCode = code, status = SyncStatus.WAITING_FOR_ROOM)
        publish(state)

        job = scope.launch {
            val transport = deps.transport
            val uid = try {
                transport.ensureSignedIn()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(lastError = "Sign-in failed: ${e.message}")
                publish(state)
                return@launch
            }
            this@RoomSyncCoordinator.uid = uid

            val clock = object : SyncClock {
                // Local monotonic clock; same base as PlaybackState.getLastPositionUpdateTime().
                override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

                // Server time from the transport (Firebase offset anchored to elapsedRealtime).
                override fun serverNowMs(): Long = transport.serverClock.serverNowMs()
            }
            val engine = deps.engineFactory.create(uid, clock).also { this@RoomSyncCoordinator.engine = it }

            // One Firebase listener shared by both consumers below.
            val roomFlow = transport.observeRoom(code)
                .catch { emit(null) }
                .shareIn(this, SharingStarted.Eagerly, replay = 1)

            launch {
                roomFlow.collect { r ->
                    room = r
                    // Engine first: a jam host's own timeline-restart write must be the engine's
                    // room state before evaluate() lets the host's app re-attach.
                    if (r != null) execute(code, engine.onRoom(r.state, r.members))
                    evaluate(code, engine)
                }
            }

            // What the host is playing. Members only sync when they're on the same title/track.
            val previewFlow = transport.observePreview(code).catch { emit(null) }

            launch {
                combine(deps.gateway.sessions, previewFlow) { s, p -> s to p }.collect { (s, p) ->
                    sessions = s
                    preview = p
                    evaluate(code, engine)
                }
            }

            launch { publishNowPlaying(code, uid, roomFlow) }

            launch {
                while (isActive) {
                    delay(TICK_MS)
                    execute(code, engine.onTick())
                    // Jam timers (start timeout, host settle) advance with time alone.
                    evaluate(code, engine)
                }
            }
        }
    }

    /** "Back to the jam": retry starting the host's track here. No-op for hosts and watch parties. */
    fun followHost() {
        val code = roomCode ?: return
        val engine = engine ?: return
        follower.requestRetry()
        evaluate(code, engine)
    }

    /**
     * Picks the session, applies the watch-party / jam gates, feeds the engine when what it may
     * see changed, and publishes the state. Main thread only.
     */
    private fun evaluate(code: String, engine: SyncEngine) {
        val uid = uid ?: return
        // Local monotonic time for the jam timers; never wall-clock time.
        val nowMs = SystemClock.elapsedRealtime()
        val r = room
        val currentPreview = preview
        val isHost = r != null && r.hostUid == uid
        val picked = pickSession(sessions, r, currentPreview, isHost)

        var visible = picked
        var override: SyncStatus? = null
        var follow = JamFollow.IDLE
        var hint: StreamingService? = null

        when {
            r != null && isHost -> {
                follower.reset()
                val hostService = picked?.let { StreamingService.forPackage(it.packageName) }
                previewItem.reset()
                if (hostService?.isJam == true) {
                    // Before feeding the new track's snapshot: the engine then treats its jump to
                    // ~0 as part of the switch and broadcasts the new timeline itself (at least
                    // 800 ms later, after our immediate preview write; see publishNowPlaying).
                    if (hostItem.changed(JamTracks.key(picked?.metadata))) {
                        execute(code, engine.onItemChanged(leader = true))
                    }
                } else if (hostService != null) {
                    hostItem.reset() // a watch party now
                }
                // No session: keep the last track, so reopening the app on another one restarts.
            }

            isJamForMember(r, currentPreview) -> {
                hostItem.reset()
                // The host moved to a new track: room writes from now on are about it. Told whether
                // or not our app is visible; JamFollower still hides it until it's on that track.
                if (currentPreview != null && previewItem.changed(JamTracks.key(currentPreview))) {
                    execute(code, engine.onItemChanged(leader = false))
                }
                hint = currentPreview?.service
                val decision = follower.update(currentPreview, picked, nowMs)
                follow = decision.follow
                if (decision.hideSession) {
                    visible = null
                    override = when {
                        decision.otherTitle -> SyncStatus.OTHER_TITLE
                        follow == JamFollow.NEEDS_APP -> null // engine: APP_NOT_OPEN / SESSION_LOST
                        else -> SyncStatus.SYNCING // switching to the host's track
                    }
                }
                decision.action?.let { action ->
                    if (!perform(action)) {
                        follower.actionNotApplied()
                        follow = JamFollow.FAILED
                        override = SyncStatus.OTHER_TITLE
                    }
                }
            }

            else -> {
                follower.reset()
                hostItem.reset()
                previewItem.reset()
                // On a different episode the app is invisible to the engine: none of its
                // play/pause/seek is broadcast and no commands are sent to it. Switching to
                // the right episode then looks like opening the app, which the engine
                // handles as a late join: it jumps to the room's position.
                if (isOtherTitle(picked, currentPreview, isHost)) {
                    visible = null
                    override = SyncStatus.OTHER_TITLE
                }
            }
        }

        statusOverride = override
        jamFollow = follow
        serviceHint = hint
        feed(code, engine, visible)
        publishState(engine)
    }

    /**
     * Gives the engine [visible]'s snapshot, but only when the session or its PlaybackState
     * changed: metadata (title/artwork) changes and re-evaluations aren't playback changes.
     */
    private fun feed(code: String, engine: SyncEngine, visible: MediaSessionInfo?) {
        val previous = session
        session = visible
        val changed = !fedEngine || visible?.sessionKey != previous?.sessionKey || visible?.snapshot != previous?.snapshot
        if (!changed) return
        fedEngine = true
        execute(code, engine.onLocalSnapshot(visible?.snapshot))
    }

    /** Asks the member's app to start the host's track. False if the app didn't take the call. */
    private fun perform(action: JamFollower.Action): Boolean {
        val ok = when (action) {
            is JamFollower.Action.PlayFromUri -> deps.gateway.playFromUri(action.sessionKey, action.uri)
            is JamFollower.Action.PlayFromSearch -> deps.gateway.playFromSearch(action.sessionKey, action.query)
        }
        Log.i(TAG, "Jam: ${action::class.simpleName} on ${action.sessionKey}: ${if (ok) "sent" else "not applied"}")
        return ok
    }

    /**
     * Host only: mirrors what the host's streaming app says is playing (title, artwork, public
     * playableRef) into the room preview, so members and invitees see it. Debounced: apps often
     * publish metadata in several steps (title first, artwork a moment later). In a jam a NEW
     * track is published at once, so members learn about it before the host engine's timeline
     * restart (onItemChanged, broadcast at least 800 ms later) reaches them: writes from one client
     * arrive in order, so members don't apply the new timeline to the old track.
     */
    @OptIn(FlowPreview::class)
    private suspend fun publishNowPlaying(code: String, uid: String, roomFlow: Flow<Room?>) {
        var lastPublishedKey: String? = null
        val isHostFlow = roomFlow.map { it?.hostUid == uid }.distinctUntilChanged()
        val titleServiceFlow = roomFlow.map { it?.title?.service }.distinctUntilChanged()
        combine(isHostFlow, deps.gateway.sessions, titleServiceFlow) { host, sessions, titleService ->
            if (!host) return@combine null
            val picked = pickHostSession(sessions, titleService) ?: return@combine null
            val service = StreamingService.forPackage(picked.packageName) ?: return@combine null
            val metadata = picked.metadata?.takeUnless { it.isEmpty } ?: return@combine null
            service to metadata
        }
            .filterNotNull()
            .distinctUntilChanged()
            .debounce { (service, metadata) ->
                if (service.isJam && JamTracks.key(metadata) != lastPublishedKey) 0L else NOW_PLAYING_DEBOUNCE_MS
            }
            .collect { (service, metadata) ->
                lastPublishedKey = JamTracks.key(metadata)
                safely("setNowPlaying") { deps.transport.setNowPlaying(code, service, metadata) }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        roomCode = null
        uid = null
        engine = null
        room = null
        sessions = emptyList()
        preview = null
        session = null
        fedEngine = false
        statusOverride = null
        jamFollow = JamFollow.IDLE
        serviceHint = null
        follower.reset()
        hostItem.reset()
        previewItem.reset()
        state = SyncSessionState()
        publish(state)
    }

    /**
     * The session to sync. The room's chosen service if set. Otherwise: for the host, whichever
     * supported app has an active session (music starts a jam, a movie a watch party), in the
     * gateway's order (most recently active first); for a member, the app the host is playing
     * (from the preview), or until that's known, video apps only, as before jams existed, so a
     * member's background music can't seed a watch party's timeline. No per-app special casing:
     * an app that doesn't report position or accept seekTo is handled by the engine's fallbacks.
     */
    private fun pickSession(
        sessions: List<MediaSessionInfo>,
        room: Room?,
        preview: RoomPreview?,
        isHost: Boolean,
    ): MediaSessionInfo? {
        if (isHost) return pickHostSession(sessions, room?.title?.service)
        val target = room?.title?.service ?: preview?.service
        val services = if (target != null) listOf(target) else StreamingService.entries.filter { !it.isJam }
        return firstSessionOf(sessions, services)
    }

    private fun pickHostSession(sessions: List<MediaSessionInfo>, titleService: StreamingService?): MediaSessionInfo? =
        firstSessionOf(sessions, if (titleService != null) listOf(titleService) else StreamingService.entries)

    private fun firstSessionOf(sessions: List<MediaSessionInfo>, services: List<StreamingService>): MediaSessionInfo? {
        val packages = services.filter { it.isConfigured }.map { it.packageName }
        return sessions.firstOrNull { it.packageName in packages }
    }

    /** A member in a room whose host plays a jam app (and the room's title, if any, agrees). */
    private fun isJamForMember(room: Room?, preview: RoomPreview?): Boolean {
        val service = preview?.service ?: return false
        if (room == null || !service.isJam) return false
        val titleService = room.title?.service
        return titleService == null || titleService == service
    }

    /**
     * Watch parties: true when we're a member (not the host) and our app is playing something
     * other than the host's title: a different streaming app, or a different title/episode.
     * Unknown (no title on either side) never blocks syncing.
     */
    private fun isOtherTitle(picked: MediaSessionInfo?, preview: RoomPreview?, isHost: Boolean): Boolean {
        if (isHost || picked == null || preview?.title == null || preview.service == null) return false
        if (StreamingService.forPackage(picked.packageName) != preview.service) return true
        return preview.matches(picked.metadata) == false
    }

    /** Applies commands locally and sends broadcasts/reports to the room. */
    private fun execute(code: String, output: EngineOutput) {
        val key = session?.sessionKey
        if (key != null) {
            for (command in output.commands) {
                val ok = when (command) {
                    SyncCommand.Play -> deps.gateway.play(key)
                    SyncCommand.Pause -> deps.gateway.pause(key)
                    is SyncCommand.SeekTo -> deps.gateway.seekTo(key, command.positionMs)
                }
                if (!ok) Log.w(TAG, "Command $command not applied (session gone)")
            }
        }
        output.broadcast?.let { broadcast ->
            scope.launch { safely("writeState") { deps.transport.writeState(code, broadcast) } }
        }
        output.positionReport?.let { position ->
            scope.launch { safely("reportPosition") { deps.transport.reportPosition(code, position) } }
        }
    }

    private fun publishState(engine: SyncEngine) {
        val target = room?.title?.service ?: session?.packageName?.let { StreamingService.forPackage(it) } ?: serviceHint
        val status = when {
            !deps.gateway.notificationAccessGranted.value -> SyncStatus.NO_NOTIFICATION_ACCESS
            target != null && !target.isConfigured -> SyncStatus.NOT_CONFIGURED
            room == null -> SyncStatus.WAITING_FOR_ROOM
            else -> statusOverride ?: engine.status
        }
        val next = state.copy(
            status = status,
            mode = engine.mode,
            driftMs = engine.lastDriftMs,
            service = target,
            jamFollow = jamFollow,
        )
        if (next != state) {
            state = next
            publish(next)
        }
    }

    private suspend fun safely(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
            state = state.copy(lastError = "$what failed: ${e.message}")
            publish(state)
        }
    }

    private companion object {
        const val TAG = "SyncItCoordinator"
        const val TICK_MS = 1_000L
        const val NOW_PLAYING_DEBOUNCE_MS = 1_500L
    }
}
