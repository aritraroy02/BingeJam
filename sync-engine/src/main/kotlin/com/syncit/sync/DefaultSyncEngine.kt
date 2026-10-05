package com.syncit.sync

import com.syncit.core.model.BroadcastReason
import com.syncit.core.model.EngineOutput
import com.syncit.core.model.LocalPlaybackState
import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackSnapshot
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.RoomState
import com.syncit.core.model.StateBroadcast
import com.syncit.core.model.SyncCommand
import com.syncit.core.model.SyncMode
import com.syncit.core.model.SyncStatus
import com.syncit.core.sync.SyncClock
import com.syncit.core.sync.SyncEngine
import kotlin.math.abs

/**
 * Sync logic for one device in one room. See PLAN.md "Sync rules" for the summary.
 *
 * Time bases:
 *  - `now` / elapsed: clock.elapsedRealtimeMs(), local and monotonic. Used for local
 *    playback extrapolation, echo windows, timers and rate limits.
 *  - server: clock.serverNowMs(), used only against server-stamped room fields
 *    (RoomState.updatedAt, Member.reportedAt).
 * The two are never mixed in a single subtraction.
 */
class DefaultSyncEngine(
    private val myUid: String,
    private val clock: SyncClock,
    private val config: SyncConfig = SyncConfig(),
) : SyncEngine {

    override var mode: SyncMode = SyncMode.MANUAL_COUNTDOWN
        private set
    override var status: SyncStatus = SyncStatus.APP_NOT_OPEN
        private set
    override var lastDriftMs: Long? = null
        private set

    // ---- Local app ----
    private var snapshot: PlaybackSnapshot? = null
    /** Last stable play/pause state of the local app (null while buffering/unknown). */
    private var localStatus: PlaybackStatus? = null
    private var everAttached = false
    private var attachedAt = 0L
    /** True until we've lined the local app up with the room after (re)attaching. */
    private var needsAlignment = true

    // ---- Room ----
    private var roomLoaded = false
    private var roomState: RoomState? = null
    private var members: List<Member> = emptyList()

    private val echo = EchoSuppressor(config.echoWindowMs, config.echoMaxHoldMs, config.seekThresholdMs)
    private val capabilities = CapabilityDetector(config)

    // ---- Timers (elapsed ms) ----
    private var lastBroadcastAt: Long? = null
    private var lastDriftCheckAt: Long? = null
    private var lastCorrectionAt: Long? = null
    private var consecutiveCorrections = 0
    private var lastReportAt: Long? = null
    /** Set while we're deliberately paused so slower members can catch up (no-SEEK_TO mode). */
    private var catchUpResumeAt: Long? = null

    private val timelineEnabled: Boolean
        get() = mode == SyncMode.FULL || mode == SyncMode.PLAY_PAUSE_CATCH_UP

    // =====================================================================================
    // Inputs
    // =====================================================================================

    override fun onLocalSnapshot(snapshot: PlaybackSnapshot?): EngineOutput {
        val now = clock.elapsedRealtimeMs()
        val previous = this.snapshot
        if (snapshot == null) {
            if (previous != null) detach()
            updateStatus()
            return EngineOutput.NONE
        }

        this.snapshot = snapshot
        capabilities.observe(snapshot, now)
        mode = selectMode()

        var output = if (previous == null) {
            // (Re)attached: the app was just opened or reopened. Its current state is not a
            // user action. Line it up with the room instead (this is also the late-join path).
            everAttached = true
            attachedAt = now
            localStatus = snapshot.state.toRoomStatus()
            needsAlignment = true
            EngineOutput.NONE
        } else {
            detectLocalAction(previous, snapshot, now)
        }
        if (needsAlignment) output += align(now)
        updateStatus()
        return output
    }

    override fun onRoom(state: RoomState?, members: List<Member>): EngineOutput {
        val now = clock.elapsedRealtimeMs()
        this.members = members
        roomLoaded = true
        val previous = roomState
        var output = EngineOutput.NONE

        if (state == null) {
            roomState = null
        } else if (previous == null || state.seq >= previous.seq) {
            // Conflict resolution: the room is authoritative and the last write wins. The server
            // stamps updatedAt and increments seq in the same write, so a higher seq is always a
            // later server write. A lower seq than we've already seen is stale and dropped.
            // Equal seq is the same write re-delivered (e.g. our optimistic copy replaced by the
            // server-resolved one), so take it but don't act on it again.
            roomState = state
            val isNewWrite = previous == null || state.seq != previous.seq || state.updatedBy != previous.updatedBy
            // Events we wrote ourselves come back to us too; we've already acted on them.
            if (!needsAlignment && isNewWrite && state.updatedBy != myUid) {
                output = applyRoomState(state, now)
            }
        }

        if (needsAlignment) output += align(now)
        updateStatus()
        return output
    }

    override fun onTick(): EngineOutput {
        val now = clock.elapsedRealtimeMs()
        val snap = snapshot
        if (snap == null) {
            updateStatus()
            return EngineOutput.NONE
        }
        var output = EngineOutput.NONE

        if (needsAlignment) output += align(now)

        catchUpResumeAt?.let { resumeAt ->
            if (now >= resumeAt) {
                catchUpResumeAt = null
                if (roomState?.status == PlaybackStatus.PLAYING && localStatus != PlaybackStatus.PLAYING) {
                    output += issue(listOf(SyncCommand.Play), now)
                }
            }
        }

        if (timelineEnabled && due(lastReportAt, config.positionReportIntervalMs, now)) {
            lastReportAt = now
            val position = PositionMath.localLivePositionMs(snap, now)
            if (position >= 0) output += EngineOutput(positionReport = position)
        }

        if (due(lastDriftCheckAt, config.driftCheckIntervalMs, now)) {
            lastDriftCheckAt = now
            output += checkDrift(snap, now)
        }

        updateStatus()
        return output
    }

    // =====================================================================================
    // Local changes → broadcasts
    // =====================================================================================

    private fun detectLocalAction(previous: PlaybackSnapshot, current: PlaybackSnapshot, now: Long): EngineOutput {
        if (current.state.isTransient()) {
            // Buffering/connecting, often right after our own play or seek. Keep the echo
            // expectations alive until the app settles.
            echo.extend(now)
            return EngineOutput.NONE
        }
        val newStatus = current.state.toRoomStatus() ?: return EngineOutput.NONE
        val statusChanged = newStatus != localStatus
        localStatus = newStatus

        // Seek detection: extrapolate the previous reading to now and compare with what the
        // app reports now. While playing both advance at the same rate, so a gap of more than
        // the seek threshold can only be a jump: the user scrubbed.
        val livePosition = PositionMath.localLivePositionMs(current, now)
        val expectedFromPrevious = PositionMath.localLivePositionMs(previous, now)
        val jumped = timelineEnabled && livePosition >= 0 && expectedFromPrevious >= 0 &&
            abs(livePosition - expectedFromPrevious) > config.seekThresholdMs

        if (!statusChanged && !jumped) return EngineOutput.NONE

        if (now - attachedAt < config.attachSettleMs) {
            // The app is still starting up (autoplay, jumping to its resume point). That's not
            // the user; pull it back in line with the room instead of broadcasting it.
            needsAlignment = true
            return EngineOutput.NONE
        }

        // Echo suppression: a change we caused by applying a remote command is not a user action.
        val userStatusChange = statusChanged && !echo.matchesStatus(newStatus, now)
        val userSeek = jumped && !echo.matchesSeek(livePosition, now)
        if (!userStatusChange && !userSeek) return EngineOutput.NONE

        // A real user action overrides any catch-up pause we were running.
        catchUpResumeAt = null

        val room = roomState
        val roomExpected = room?.let { PositionMath.roomExpectedPositionMs(it, clock.serverNowMs()) }
        val position = when {
            timelineEnabled -> livePosition
            roomExpected != null -> roomExpected // no usable local position: keep the room's timeline
            else -> 0L
        }

        // Already what the room says (a late echo, or two people pressing the same button):
        // broadcasting would only bump seq and make everyone re-check.
        if (room != null && roomExpected != null && room.status == newStatus &&
            (!timelineEnabled || abs(position - roomExpected) <= config.driftThresholdMs)
        ) {
            return EngineOutput.NONE
        }

        val reason = when {
            userStatusChange && newStatus == PlaybackStatus.PLAYING -> BroadcastReason.PLAY
            userStatusChange -> BroadcastReason.PAUSE
            else -> BroadcastReason.SEEK
        }
        return broadcast(newStatus, position, reason, now)
    }

    private fun broadcast(status: PlaybackStatus, positionMs: Long, reason: BroadcastReason, now: Long): EngineOutput {
        lastBroadcastAt = now
        return EngineOutput(broadcast = StateBroadcast(status, positionMs.coerceAtLeast(0L), reason))
    }

    // =====================================================================================
    // Room → local commands
    // =====================================================================================

    /** Late join / reattach: bring the local app to the room's current state. */
    private fun align(now: Long): EngineOutput {
        val snap = snapshot
        val status = localStatus
        if (snap == null || status == null || !roomLoaded || mode == SyncMode.MANUAL_COUNTDOWN) {
            return EngineOutput.NONE
        }
        val room = roomState
        if (room == null) {
            // Nobody has played yet. Once our app has settled, seed the room with our state so
            // there's something for everyone else to align to.
            if (now - attachedAt < config.attachSettleMs) return EngineOutput.NONE
            needsAlignment = false
            val position = if (timelineEnabled) PositionMath.localLivePositionMs(snap, now) else 0L
            val reason = if (status == PlaybackStatus.PLAYING) BroadcastReason.PLAY else BroadcastReason.PAUSE
            return broadcast(status, position, reason, now)
        }
        needsAlignment = false
        return applyRoomState(room, now)
    }

    /**
     * Commands that make the local app match [room]. Order matters: seek before play (so we
     * don't play a moment from the wrong spot), pause before seek (so the seek lands where
     * everyone is paused).
     */
    private fun applyRoomState(room: RoomState, now: Long): EngineOutput {
        val snap = snapshot ?: return EngineOutput.NONE
        if (mode == SyncMode.MANUAL_COUNTDOWN) return EngineOutput.NONE
        catchUpResumeAt = null

        val target = PositionMath.roomExpectedPositionMs(room, clock.serverNowMs())
        val local = PositionMath.localLivePositionMs(snap, now)
        val needsSeek = mode == SyncMode.FULL && local >= 0 && abs(local - target) > config.driftThresholdMs
        val needsStatus = localStatus != room.status

        val commands = buildList {
            when (room.status) {
                PlaybackStatus.PLAYING -> {
                    if (needsSeek) add(SyncCommand.SeekTo(target))
                    if (needsStatus) add(SyncCommand.Play)
                }
                PlaybackStatus.PAUSED -> {
                    if (needsStatus) add(SyncCommand.Pause)
                    if (needsSeek) add(SyncCommand.SeekTo(target))
                }
            }
        }
        return issue(commands, now)
    }

    /** Records what each command should cause, so the resulting callbacks aren't re-broadcast. */
    private fun issue(commands: List<SyncCommand>, now: Long): EngineOutput {
        if (commands.isEmpty()) return EngineOutput.NONE
        for (command in commands) {
            when (command) {
                SyncCommand.Play -> echo.expectStatus(PlaybackStatus.PLAYING, now)
                SyncCommand.Pause -> echo.expectStatus(PlaybackStatus.PAUSED, now)
                is SyncCommand.SeekTo -> {
                    echo.expectSeek(command.positionMs, now)
                    lastCorrectionAt = now
                }
            }
        }
        return EngineOutput(commands = commands)
    }

    // =====================================================================================
    // Drift correction (every driftCheckIntervalMs)
    // =====================================================================================

    private fun checkDrift(snap: PlaybackSnapshot, now: Long): EngineOutput {
        val room = roomState ?: return EngineOutput.NONE
        if (needsAlignment || mode == SyncMode.MANUAL_COUNTDOWN || catchUpResumeAt != null) return EngineOutput.NONE
        // Let our last command, or our last broadcast, land before judging drift.
        if (echo.hasPending(now)) return EngineOutput.NONE
        if (lastBroadcastAt?.let { now - it < config.broadcastGraceMs } == true) return EngineOutput.NONE

        val cooledDown = lastCorrectionAt?.let { now - it >= config.correctionCooldownMs } ?: true

        // Play/pause disagrees with the room for a whole check interval (the app ignored a
        // command, or we missed one): re-apply the room state.
        if (localStatus != null && localStatus != room.status) {
            if (!cooledDown) return EngineOutput.NONE
            lastCorrectionAt = now
            return applyRoomState(room, now)
        }

        if (!timelineEnabled) {
            lastDriftMs = null
            return EngineOutput.NONE
        }

        val local = PositionMath.localLivePositionMs(snap, now)
        if (local < 0) return EngineOutput.NONE
        val expected = PositionMath.roomExpectedPositionMs(room, clock.serverNowMs())
        val drift = local - expected // > 0: we're ahead of the room
        lastDriftMs = drift

        return when (mode) {
            SyncMode.FULL -> correctBySeeking(drift, expected, cooledDown, now)
            SyncMode.PLAY_PAUSE_CATCH_UP -> catchUp(room, local, cooledDown, now)
            else -> EngineOutput.NONE
        }
    }

    private fun correctBySeeking(driftMs: Long, expected: Long, cooledDown: Boolean, now: Long): EngineOutput {
        if (abs(driftMs) <= config.driftThresholdMs) {
            consecutiveCorrections = 0
            return EngineOutput.NONE
        }
        if (!cooledDown) return EngineOutput.NONE
        if (consecutiveCorrections >= config.maxConsecutiveCorrections) {
            // We've seeked several times and are still off: the app ignores seeks or reports a
            // stale position. Stop fighting it; fall back to play/pause-only sync.
            capabilities.markPositionUnreliable()
            mode = selectMode()
            lastDriftMs = null
            consecutiveCorrections = 0
            return EngineOutput.NONE
        }
        consecutiveCorrections++
        return issue(listOf(SyncCommand.SeekTo(expected)), now)
    }

    /**
     * No-SEEK_TO fallback: whoever is ahead of the slowest member pauses for exactly as long as
     * they're ahead. Members who are behind do nothing; the ones ahead wait for them.
     */
    private fun catchUp(room: RoomState, localMs: Long, cooledDown: Boolean, now: Long): EngineOutput {
        if (room.status != PlaybackStatus.PLAYING || localStatus != PlaybackStatus.PLAYING || !cooledDown) {
            return EngineOutput.NONE
        }
        val serverNow = clock.serverNowMs()
        // Reference: slowest online member with a fresh report (extrapolated to server-now),
        // or the room's expected position if nobody else has reported.
        val reference = members.asSequence()
            .filter { it.uid != myUid && it.online }
            .mapNotNull { PositionMath.memberExpectedPositionMs(it, room.status, serverNow, config.memberReportMaxAgeMs) }
            .minOrNull()
            ?: PositionMath.roomExpectedPositionMs(room, serverNow)
        val aheadMs = localMs - reference
        if (aheadMs <= config.driftThresholdMs) return EngineOutput.NONE

        lastCorrectionAt = now
        // While paused we don't move and they do, so pausing for aheadMs closes the gap.
        catchUpResumeAt = now + aheadMs.coerceAtMost(config.maxCatchUpPauseMs)
        return issue(listOf(SyncCommand.Pause), now)
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private fun detach() {
        snapshot = null
        localStatus = null
        needsAlignment = true
        echo.clear()
        capabilities.reset()
        catchUpResumeAt = null
        lastDriftMs = null
        consecutiveCorrections = 0
        mode = SyncMode.MANUAL_COUNTDOWN
    }

    private fun selectMode(): SyncMode = when {
        snapshot == null || !capabilities.supportsPlayPause -> SyncMode.MANUAL_COUNTDOWN
        !capabilities.positionReliable -> SyncMode.PLAY_PAUSE_ONLY
        !capabilities.supportsSeek -> SyncMode.PLAY_PAUSE_CATCH_UP
        else -> SyncMode.FULL
    }

    private fun updateStatus() {
        val drift = lastDriftMs
        status = when {
            snapshot == null -> if (everAttached) SyncStatus.SESSION_LOST else SyncStatus.APP_NOT_OPEN
            catchUpResumeAt != null -> SyncStatus.CATCHING_UP
            mode == SyncMode.FULL && drift != null && abs(drift) > config.driftThresholdMs -> SyncStatus.CORRECTING
            else -> SyncStatus.SYNCING
        }
    }

    private fun due(last: Long?, intervalMs: Long, now: Long): Boolean = last == null || now - last >= intervalMs

    private companion object {
        val TRANSIENT_STATES = setOf(
            LocalPlaybackState.BUFFERING,
            LocalPlaybackState.CONNECTING,
            LocalPlaybackState.FAST_FORWARDING,
            LocalPlaybackState.REWINDING,
            LocalPlaybackState.SKIPPING_TO_PREVIOUS,
            LocalPlaybackState.SKIPPING_TO_NEXT,
            LocalPlaybackState.SKIPPING_TO_QUEUE_ITEM,
        )

        fun LocalPlaybackState.isTransient(): Boolean = this in TRANSIENT_STATES

        /**
         * Only PLAYING and PAUSED count as room states. STOPPED is deliberately ignored: it
         * usually means someone closed the player, and that shouldn't pause the whole room.
         */
        fun LocalPlaybackState.toRoomStatus(): PlaybackStatus? = when (this) {
            LocalPlaybackState.PLAYING -> PlaybackStatus.PLAYING
            LocalPlaybackState.PAUSED -> PlaybackStatus.PAUSED
            else -> null
        }
    }
}
