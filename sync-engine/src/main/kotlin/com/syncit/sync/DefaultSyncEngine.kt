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
    /**
     * Elapsed time until which the app's own changes are start-up noise (autoplay, jumping to
     * its resume point), not user actions: attach time + attachSettleMs.
     */
    private var settleUntil = 0L
    /** True until we've lined the local app up with the room after (re)attaching. */
    private var needsAlignment = true
    /** Elapsed time the app entered its current transient state (buffering, skipping...); null once settled. */
    private var transientSince: Long? = null
    /** A position jump seen as the app went transient, decided once it settles. */
    private var transientJump: TransientJump? = null

    // ---- Room ----
    private var roomLoaded = false
    private var roomState: RoomState? = null
    private var members: List<Member> = emptyList()

    // ---- Item change (onItemChanged) ----
    private var restart: ItemRestart? = null
    /** Leader only, after its restart broadcast: others' (old-item) writes wait until ours lands. */
    private var ownWriteGuard: OwnWriteGuard? = null

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
            val output = advanceRestart(now)
            updateStatus()
            return output
        }

        this.snapshot = snapshot
        capabilities.observe(snapshot, now)
        mode = selectMode()

        var output = if (previous == null) {
            // (Re)attached: the app was just opened or reopened. Its current state is not a
            // user action. Line it up with the room instead (this is also the late-join path).
            // During an item change the restart's own rules apply instead (no settle window).
            everAttached = true
            localStatus = snapshot.state.toRoomStatus()
            transientSince = if (snapshot.state.isTransient()) now else null
            transientJump = null
            if (restart == null) {
                settleUntil = now + config.attachSettleMs
                needsAlignment = true
            }
            EngineOutput.NONE
        } else {
            if (snapshot != previous) restart?.sawNewSnapshot = true
            detectLocalAction(previous, snapshot, now)
        }
        output += advanceRestart(now)
        if (needsAlignment && restart == null) output += align(now)
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
            val ours = state.updatedBy == myUid

            // Leader after an item change: our write for the new item has landed (or we've waited
            // long enough, elapsed time), so others' writes apply normally again.
            ownWriteGuard?.let { guard ->
                if ((ours && state.seq > guard.seqFloor) || now >= guard.until) ownWriteGuard = null
            }

            val r = restart
            if (r != null) {
                // Member mid item change: the first write by someone else after the change is the
                // new item's state. Our own writes never are (we don't write during a restart).
                if (!r.leader && !ours && state.seq > r.roomSeqFloor) r.roomReady = true
            } else if (!needsAlignment && isNewWrite && !ours && ownWriteGuard == null) {
                // Events we wrote ourselves come back to us too; we've already acted on them.
                output = applyRoomState(state, now)
            }
        }

        output += advanceRestart(now)
        if (needsAlignment && restart == null) output += align(now)
        updateStatus()
        return output
    }

    override fun onTick(): EngineOutput {
        val now = clock.elapsedRealtimeMs()
        var output = advanceRestart(now)
        val snap = snapshot
        if (snap == null) {
            updateStatus()
            return output
        }

        output += expireTransientHold(now)
        if (needsAlignment && restart == null) output += align(now)

        catchUpResumeAt?.let { resumeAt ->
            if (now >= resumeAt) {
                catchUpResumeAt = null
                if (roomState?.status == PlaybackStatus.PLAYING && localStatus != PlaybackStatus.PLAYING) {
                    output += issue(listOf(SyncCommand.Play), now)
                }
            }
        }

        if (timelineEnabled && restart == null && due(lastReportAt, config.positionReportIntervalMs, now)) {
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

    override fun onItemChanged(leader: Boolean): EngineOutput {
        val now = clock.elapsedRealtimeMs()
        // Room writes up to the current seq were about the old item. The new item's (from the
        // leader) can only come later: the leader broadcasts at least itemSettleMinMs after its
        // own change, and its new now-playing info is written before that.
        restart = ItemRestart(leader, startedAt = now, roomSeqFloor = roomState?.seq ?: Long.MIN_VALUE)
        ownWriteGuard = null
        // Nothing tracked against the old item's timeline applies to the new one.
        echo.clear()
        transientJump = null
        catchUpResumeAt = null
        consecutiveCorrections = 0
        lastDriftMs = null
        needsAlignment = false
        settleUntil = now // no attach-settle window: the restart has its own rules
        val output = advanceRestart(now)
        updateStatus()
        return output
    }

    // =====================================================================================
    // Local changes → broadcasts
    // =====================================================================================

    private fun detectLocalAction(previous: PlaybackSnapshot, current: PlaybackSnapshot, now: Long): EngineOutput {
        // Seek detection: extrapolate the previous reading to now and compare with what the
        // app reports now. While playing both advance at the same rate, so a gap of more than
        // the seek threshold can only be a jump: the user scrubbed. Readings that aren't PLAYING
        // (buffering included) aren't extrapolated: a stalled app's position doesn't move, so
        // a network stall (PLAYING@a -> BUFFERING@a ... PLAYING@a) is never a jump.
        val livePosition = PositionMath.localLivePositionMs(current, now)
        val expectedFromPrevious = PositionMath.localLivePositionMs(previous, now)
        val jumped = timelineEnabled && livePosition >= 0 && expectedFromPrevious >= 0 &&
            if (current.state.isTransient()) {
                // Entering buffering. A video can freeze while the app still says PLAYING, and
                // only later report BUFFERING at the real (older) position. Anywhere from the last
                // RAW position to the extrapolated one is therefore a stall, not a scrub; with
                // the seek threshold as tolerance, but never reaching back more than
                // stallWindowMaxMs behind the extrapolation (the last raw report may be old):
                //   lower  = max(previous.positionMs - threshold, extrapolated - stallWindowMax)
                //   stall <=> lower <= live <= extrapolated + threshold
                // A scrub lands outside: back before that lower bound, or forward past the
                // extrapolation. (When previous wasn't PLAYING, raw == extrapolated.)
                val stallLower = maxOf(
                    previous.positionMs - config.seekThresholdMs,
                    expectedFromPrevious - config.stallWindowMaxMs,
                )
                livePosition < stallLower || livePosition > expectedFromPrevious + config.seekThresholdMs
            } else {
                abs(livePosition - expectedFromPrevious) > config.seekThresholdMs
            }

        if (current.state.isTransient()) {
            // Buffering/skipping, often right after our own play or seek: hold the echo
            // expectations until the app settles.
            if (transientSince == null) transientSince = now
            echo.hold(now)
            // A scrub usually reads PLAYING@a -> BUFFERING@b -> PLAYING@b: the jump is only
            // visible on this reading, so remember it and decide once the app settles. (During an
            // item change, jumps are part of the switch.)
            if (jumped && restart == null) noteTransientJump(livePosition, now)
            return EngineOutput.NONE
        }
        val newStatus = current.state.toRoomStatus() ?: return EngineOutput.NONE

        // Settled (PLAYING/PAUSED). Close any transient phase.
        val pendingJump = transientJump
        if (transientSince != null) echo.release(now)
        transientSince = null
        transientJump = null
        val statusChanged = newStatus != localStatus
        localStatus = newStatus

        // Mid item change: this is the app switching items; advanceRestart() takes it from here.
        if (restart != null) return EngineOutput.NONE

        if (!statusChanged && !jumped && pendingJump == null) return EngineOutput.NONE

        if (now < settleUntil || pendingJump == TransientJump.STARTUP) {
            // The app is still starting up (autoplay, jumping to its resume point). That's not
            // the user; pull it back in line with the room instead of broadcasting it.
            needsAlignment = true
            return EngineOutput.NONE
        }

        // Echo suppression: a change we caused by applying a remote command is not a user action.
        // A jump already judged on the buffering reading (pendingJump == USER) stays a user seek.
        val userStatusChange = statusChanged && !echo.matchesStatus(newStatus, now)
        val userSeek = (jumped && !echo.matchesSeek(livePosition, now)) || pendingJump == TransientJump.USER
        if (!userStatusChange && !userSeek) return EngineOutput.NONE

        return broadcastUserChange(newStatus, livePosition, userStatusChange, now)
    }

    /** A jump seen on entering a transient state: our own seek, app start-up, or the user. */
    private fun noteTransientJump(livePosition: Long, now: Long) {
        when {
            transientJump == TransientJump.STARTUP -> Unit // keep: realigning is the safe outcome
            now < settleUntil -> transientJump = TransientJump.STARTUP
            // Buffering at (or, if it extrapolates, near) the target of our own seekTo.
            echo.matchesSeek(livePosition, now) -> Unit
            else -> transientJump = TransientJump.USER
        }
    }

    /**
     * The app has been transient (buffering) for transientHoldMs since it entered that state
     * (elapsed time). Stop waiting for it to settle: broadcast a pending scrub at the position it's
     * buffering at, keeping our last stable play/pause state, or realign a start-up jump.
     */
    private fun expireTransientHold(now: Long): EngineOutput {
        val since = transientSince ?: return EngineOutput.NONE
        if (now - since < config.transientHoldMs) return EngineOutput.NONE
        val jump = transientJump ?: return EngineOutput.NONE
        transientJump = null
        return when (jump) {
            TransientJump.STARTUP -> {
                needsAlignment = true
                EngineOutput.NONE // onTick aligns right after this
            }
            TransientJump.USER -> {
                val status = localStatus ?: return EngineOutput.NONE
                val snap = snapshot ?: return EngineOutput.NONE
                broadcastUserChange(status, PositionMath.localLivePositionMs(snap, now), userStatusChange = false, now = now)
            }
        }
    }

    /** A user play/pause and/or seek, already told apart from echoes: write it to the room. */
    private fun broadcastUserChange(
        newStatus: PlaybackStatus,
        livePosition: Long,
        userStatusChange: Boolean,
        now: Long,
    ): EngineOutput {
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
    // Item change (a new track in a jam)
    // =====================================================================================

    /**
     * Moves an in-progress item change forward; called after every input. Finishes it once the
     * app has settled on the new item (and, for a member, the new item's room state is in), or
     * falls back to the late-join path after itemRestartTimeoutMs.
     */
    private fun advanceRestart(now: Long): EngineOutput {
        val r = restart ?: return EngineOutput.NONE
        // Timing: all elapsed ms. sinceChange = time since onItemChanged.
        val sinceChange = now - r.startedAt
        val snap = snapshot
        val status = snap?.state?.toRoomStatus()

        // Is the current reading the new item's? Yes if a new PlaybackState arrived after the
        // change, or if it was sampled (lastPositionUpdateTime, elapsed) no more than
        // itemFreshToleranceMs before the change: apps often publish the new state a moment before
        // the new metadata. After itemSettleMaxMs, take whatever settled reading there is.
        val fresh = snap != null && (
            r.sawNewSnapshot ||
                (snap.lastPositionUpdateElapsedMs > 0 && snap.lastPositionUpdateElapsedMs >= r.startedAt - config.itemFreshToleranceMs)
            )
        val settled = snap != null && status != null && (fresh || sinceChange >= config.itemSettleMaxMs)

        if (r.leader) {
            if (settled && sinceChange >= config.itemSettleMinMs) return finishLeaderRestart(snap!!, status!!, now)
        } else if (settled && r.roomReady) {
            return finishMemberRestart(now)
        }

        if (sinceChange >= config.itemRestartTimeoutMs) {
            // The app never settled on the new item, or (member) no write for it arrived. Fall
            // back to what a re-attach does: align to the room, start-up changes ignored for a bit.
            restart = null
            needsAlignment = true
            settleUntil = now + config.attachSettleMs
        }
        return EngineOutput.NONE
    }

    /** Leader: the new item's state becomes the room's. */
    private fun finishLeaderRestart(snap: PlaybackSnapshot, status: PlaybackStatus, now: Long): EngineOutput {
        restart = null
        needsAlignment = false
        localStatus = status
        // Members extrapolate from the server's updatedAt, a network delay after this sample
        // (~100 ms); the same small lag every broadcast has.
        val live = PositionMath.localLivePositionMs(snap, now)
        val position = if (timelineEnabled && live >= 0) live else 0L
        // Writes already in flight from others are about the old item: don't apply them until
        // ours has landed (Firebase delivers our own write to us first), or itemOwnWriteWaitMs.
        ownWriteGuard = OwnWriteGuard(seqFloor = roomState?.seq ?: Long.MIN_VALUE, until = now + config.itemOwnWriteWaitMs)
        return broadcast(status, position, BroadcastReason.SEEK, now)
    }

    /** Member: line the new item up with the leader's state for it. */
    private fun finishMemberRestart(now: Long): EngineOutput {
        restart = null
        val room = roomState
        if (room == null) {
            needsAlignment = true // room state was cleared meanwhile: nothing to follow
            return EngineOutput.NONE
        }
        needsAlignment = false
        return applyRoomState(room, now)
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
            if (now < settleUntil) return EngineOutput.NONE
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
        // The room is authoritative: a later write wins over a scrub still buffering here.
        transientJump = null

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
        // Mid item change, the room's timeline is the old item's.
        if (restart != null) return EngineOutput.NONE
        // Buffering/skipping: the position isn't moving and the app may be mid-seek (ours or the
        // user's). Wait for it to settle, but at most transientHoldMs (elapsed) since it started.
        if (transientSince?.let { now - it < config.transientHoldMs } == true) return EngineOutput.NONE
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

    /** The session went away. An item change in progress survives it (sessions are hidden while switching). */
    private fun detach() {
        snapshot = null
        localStatus = null
        needsAlignment = true
        transientSince = null
        transientJump = null
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

    /** What a jump seen on a buffering/skipping reading turned out to be. */
    private enum class TransientJump {
        /** The user scrubbed: broadcast once the app settles. */
        USER,

        /** Inside the attach-settle window (app start-up): realign once the app settles. */
        STARTUP,
    }

    /** An item change in progress. Times are elapsed ms. */
    private class ItemRestart(
        val leader: Boolean,
        val startedAt: Long,
        /** Room writes with seq <= this were about the old item. */
        val roomSeqFloor: Long,
    ) {
        /** A new PlaybackState arrived while attached after the change. */
        var sawNewSnapshot = false

        /** Member: a write by someone else newer than [roomSeqFloor] arrived (the new item's state). */
        var roomReady = false
    }

    /** Elapsed [until]; [seqFloor] = room seq when the leader broadcast its restart. */
    private class OwnWriteGuard(val seqFloor: Long, val until: Long)

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
