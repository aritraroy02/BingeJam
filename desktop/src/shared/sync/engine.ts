// Sync logic for one device in one room: a port of sync-engine/.../DefaultSyncEngine.kt, so a
// PC makes the same decisions as a phone. See PLAN.md "Sync rules" for the summary.
//
// Time bases:
//  - `now` / local: clock.localNowMs(), local and monotonic (performance.now()). Used for local
//    playback extrapolation, echo windows, timers and rate limits.
//  - server: clock.serverNowMs(), used only against server-stamped room fields
//    (RoomState.updatedAt, Member.reportedAt).
// The two are never mixed in a single subtraction.
//
// Desktop addition: speed nudges (SyncConfig.rateNudges). Android leaves drift at or below
// driftThresholdMs alone; when the player can change playbackRate, the engine closes that drift by
// playing at 0.98x / 1.02x for |drift| / 2% instead. Seeks above the threshold, the room state
// written and every other rule are unchanged.
//
// Not thread-safe by design: call it from one place (the renderer's room session).

import { CapabilityDetector } from './capabilityDetector.ts'
import { DEFAULT_SYNC_CONFIG, type SyncConfig } from './config.ts'
import { EchoSuppressor } from './echoSuppressor.ts'
import { localLivePositionMs, memberExpectedPositionMs, roomExpectedPositionMs } from './positionMath.ts'
import {
  engineOutput,
  mergeOutput,
  NO_OUTPUT,
  type BroadcastReason,
  type EngineOutput,
  type LocalSnapshot,
  type LocalState,
  type Member,
  type PlaybackStatus,
  type RoomState,
  type SyncClock,
  type SyncCommand,
  type SyncMode,
  type SyncStatus
} from './types.ts'

/** What a jump seen on a buffering/skipping reading turned out to be. */
type TransientJump =
  /** The user scrubbed: broadcast once the player settles. */
  | 'user'
  /** Inside the attach-settle window (player start-up): realign once it settles. */
  | 'startup'

/** An item change in progress. Times are local ms. */
interface ItemRestart {
  readonly leader: boolean
  readonly startedAt: number
  /** Room writes with seq <= this were about the old item. */
  readonly roomSeqFloor: number
  /** A new reading arrived while attached after the change. */
  sawNewSnapshot: boolean
  /** Member: a write by someone else newer than roomSeqFloor arrived (the new item's state). */
  roomReady: boolean
}

/** Local `until`; `seqFloor` = room seq when the leader broadcast its restart. */
interface OwnWriteGuard {
  readonly seqFloor: number
  readonly until: number
}

/** A running speed nudge. Times are local ms. */
interface RateNudge {
  readonly rate: number
  /** We were ahead of the room (playing slower). */
  readonly ahead: boolean
  readonly startedAt: number
  endsAt: number
  /** A reading has shown the nudge speed. */
  applied: boolean
}

export interface RateNudgeInfo {
  readonly rate: number
  /** Local time the nudge is planned to end. */
  readonly endsAt: number
}

const TRANSIENT_STATES: ReadonlySet<LocalState> = new Set<LocalState>(['buffering', 'connecting', 'skipping'])

function isTransient(state: LocalState): boolean {
  return TRANSIENT_STATES.has(state)
}

/**
 * Only playing and paused count as room states. 'stopped' is deliberately ignored: it usually
 * means someone closed the player, and that shouldn't pause the whole room.
 */
function toRoomStatus(state: LocalState): PlaybackStatus | null {
  if (state === 'playing') return 'playing'
  if (state === 'paused') return 'paused'
  return null
}

/** Kotlin data-class equality of two readings. */
export function sameSnapshot(a: LocalSnapshot | null, b: LocalSnapshot | null): boolean {
  if (a === b) return true
  if (a === null || b === null) return false
  return (
    a.state === b.state &&
    a.positionMs === b.positionMs &&
    a.sampledAtMs === b.sampledAtMs &&
    a.rate === b.rate &&
    a.capabilities.playPause === b.capabilities.playPause &&
    a.capabilities.seek === b.capabilities.seek &&
    a.capabilities.rate === b.capabilities.rate
  )
}

function nearRate(a: number, b: number): boolean {
  return Math.abs(a - b) < 0.001
}

const RATE_NORMAL: SyncCommand = Object.freeze({ type: 'rate', playbackRate: 1 })

export class SyncEngine {
  private readonly myUid: string
  private readonly clock: SyncClock
  private readonly config: SyncConfig

  private _mode: SyncMode = 'manualCountdown'
  private _status: SyncStatus = 'appNotOpen'
  private _lastDriftMs: number | null = null

  // ---- Local player ----
  private snapshot: LocalSnapshot | null = null
  /** Last stable play/pause state of the local player (null while buffering/unknown). */
  private localStatus: PlaybackStatus | null = null
  private everAttached = false
  /**
   * Local time until which the player's own changes are start-up noise (autoplay, jumping to its
   * resume point), not user actions: attach time + attachSettleMs.
   */
  private settleUntil = Number.NEGATIVE_INFINITY
  /**
   * Realigns caused by the player's own start-up changes since the last attach. Capped: Netflix's
   * Android app lands a seek on the keyframe before the target and starts playing by itself, so
   * re-aligning on every one of its changes became a pause/seek loop (~25 times in 4 s on a
   * Motorola, 2026-10-29). Browser seeks are frame-accurate, but the cap costs nothing.
   */
  private settleRealigns = 0
  /** Cap reached: realign once the settle window is over (onTick). */
  private realignWhenSettled = false
  /** True until we've lined the local player up with the room after (re)attaching. */
  private needsAlignment = true
  /** Local time the player entered its current transient state (buffering...); null once settled. */
  private transientSince: number | null = null
  /** A position jump seen as the player went transient, decided once it settles. */
  private transientJump: TransientJump | null = null

  // ---- Room ----
  private roomLoaded = false
  private roomState: RoomState | null = null
  private members: readonly Member[] = []

  // ---- Item change (onItemChanged) ----
  private restart: ItemRestart | null = null
  /** Leader only, after its restart broadcast: others' (old-item) writes wait until ours lands. */
  private ownWriteGuard: OwnWriteGuard | null = null

  private readonly echo: EchoSuppressor
  private readonly capabilities: CapabilityDetector

  // ---- Timers (local ms) ----
  private lastBroadcastAt: number | null = null
  private lastDriftCheckAt: number | null = null
  private lastCorrectionAt: number | null = null
  private consecutiveCorrections = 0
  private lastReportAt: number | null = null
  /** Set while we're deliberately paused so slower members can catch up (no-seek mode). */
  private catchUpResumeAt: number | null = null
  private nudge: RateNudge | null = null

  constructor(myUid: string, clock: SyncClock, config: SyncConfig = DEFAULT_SYNC_CONFIG) {
    this.myUid = myUid
    this.clock = clock
    this.config = config
    this.echo = new EchoSuppressor(config.echoWindowMs, config.echoMaxHoldMs, config.seekThresholdMs)
    this.capabilities = new CapabilityDetector(config)
  }

  get mode(): SyncMode {
    return this._mode
  }

  get status(): SyncStatus {
    return this._status
  }

  /** Our position minus the room's expected position at the last drift check, if known (> 0: ahead). */
  get lastDriftMs(): number | null {
    return this._lastDriftMs
  }

  /** The speed nudge in progress, if any. */
  get rateNudge(): RateNudgeInfo | null {
    const n = this.nudge
    return n ? { rate: n.rate, endsAt: n.endsAt } : null
  }

  private get timelineEnabled(): boolean {
    return this._mode === 'full' || this._mode === 'playPauseCatchUp'
  }

  // =============================================================================================
  // Inputs
  // =============================================================================================

  /** A new local reading of the player, or null if it went away (tab closed, other title...). */
  onLocalSnapshot(snapshot: LocalSnapshot | null): EngineOutput {
    const now = this.clock.localNowMs()
    const previous = this.snapshot
    if (snapshot === null) {
      if (previous !== null) this.detach()
      const output = this.advanceRestart(now)
      this.updateStatus()
      return output
    }

    this.snapshot = snapshot
    this.capabilities.observe(snapshot, now)
    this._mode = this.selectMode()
    this.observeNudgeRate(snapshot)

    let output: EngineOutput
    if (previous === null) {
      // (Re)attached: the player was just opened or reopened. Its current state is not a user
      // action. Line it up with the room instead (this is also the late-join path). During an
      // item change the restart's own rules apply instead (no settle window).
      this.everAttached = true
      this.localStatus = toRoomStatus(snapshot.state)
      this.transientSince = isTransient(snapshot.state) ? now : null
      this.transientJump = null
      if (this.restart === null) {
        this.settleUntil = now + this.config.attachSettleMs
        this.needsAlignment = true
        this.settleRealigns = 0
        this.realignWhenSettled = false
      }
      output = NO_OUTPUT
    } else {
      if (this.restart !== null && !sameSnapshot(snapshot, previous)) this.restart.sawNewSnapshot = true
      output = this.detectLocalAction(previous, snapshot, now)
    }
    output = mergeOutput(output, this.advanceRestart(now))
    if (this.needsAlignment && this.restart === null) output = mergeOutput(output, this.align(now))
    this.updateStatus()
    return output
  }

  /** The room's latest state (null if nobody has played yet) and members. */
  onRoom(state: RoomState | null, members: readonly Member[]): EngineOutput {
    const now = this.clock.localNowMs()
    this.members = members
    this.roomLoaded = true
    const previous = this.roomState
    let output = NO_OUTPUT

    if (state === null) {
      this.roomState = null
    } else if (previous === null || state.seq >= previous.seq) {
      // Conflict resolution: the room is authoritative and the last write wins. The server stamps
      // updatedAt and increments seq in the same write, so a higher seq is always a later server
      // write. A lower seq than we've already seen is stale and dropped. Equal seq is the same
      // write re-delivered (e.g. our optimistic copy replaced by the server-resolved one), so take
      // it but don't act on it again.
      this.roomState = state
      const isNewWrite = previous === null || state.seq !== previous.seq || state.updatedBy !== previous.updatedBy
      const ours = state.updatedBy === this.myUid

      // Leader after an item change: our write for the new item has landed (or we've waited long
      // enough, local time), so others' writes apply normally again.
      const guard = this.ownWriteGuard
      if (guard !== null && ((ours && state.seq > guard.seqFloor) || now >= guard.until)) this.ownWriteGuard = null

      const r = this.restart
      if (r !== null) {
        // Member mid item change: the first write by someone else after the change is the new
        // item's state. Our own writes never are (we don't write during a restart).
        if (!r.leader && !ours && state.seq > r.roomSeqFloor) r.roomReady = true
      } else if (!this.needsAlignment && isNewWrite && !ours && this.ownWriteGuard === null) {
        // Events we wrote ourselves come back to us too; we've already acted on them.
        output = this.applyRoomState(state, now)
      }
    }

    output = mergeOutput(output, this.advanceRestart(now))
    if (this.needsAlignment && this.restart === null) output = mergeOutput(output, this.align(now))
    this.updateStatus()
    return output
  }

  /** Called about once a second; runs drift checks, catch-up and nudge timers, position reports. */
  onTick(): EngineOutput {
    const now = this.clock.localNowMs()
    let output = this.advanceRestart(now)
    const snap = this.snapshot
    if (snap === null) {
      this.updateStatus()
      return output
    }

    output = mergeOutput(output, this.expireTransientHold(now))
    if (this.realignWhenSettled && now >= this.settleUntil) {
      this.realignWhenSettled = false
      this.needsAlignment = true
    }
    if (this.needsAlignment && this.restart === null) output = mergeOutput(output, this.align(now))

    const resumeAt = this.catchUpResumeAt
    if (resumeAt !== null && now >= resumeAt) {
      this.catchUpResumeAt = null
      if (this.roomState?.status === 'playing' && this.localStatus !== 'playing') {
        output = mergeOutput(output, this.issue([{ type: 'play' }], now))
      }
    }

    output = mergeOutput(output, this.advanceNudge(now))

    if (this.timelineEnabled && this.restart === null && due(this.lastReportAt, this.config.positionReportIntervalMs, now)) {
      this.lastReportAt = now
      const position = localLivePositionMs(snap, now)
      if (position >= 0) output = mergeOutput(output, engineOutput({ positionReport: position }))
    }

    if (due(this.lastDriftCheckAt, this.config.driftCheckIntervalMs, now)) {
      this.lastDriftCheckAt = now
      output = mergeOutput(output, this.checkDrift(snap, now))
    }

    this.updateStatus()
    return output
  }

  /**
   * The timeline restarts for a new item (next episode, another title or service, a new track).
   * Call it once per change, as soon as the change is known, before feeding further readings.
   * Works whether or not the player is currently attached.
   *
   * Until the restart completes, the player's jumps and play/pause changes are part of the
   * switch: never broadcast, no drift checks, no alignment to the old item's room state.
   *
   * leader = true (host): once the player reports a settled (playing/paused) reading for the new
   * item, the engine broadcasts its state and live position as authoritative (reason 'seek'). No
   * attach-settle window afterwards, so a pause right after is broadcast normally. Other members'
   * room writes are not applied until our own write has come back.
   *
   * leader = false (member): room writes that arrive after this call belong to the new item. Once
   * one has arrived and the player has settled on the new item, the engine aligns to it (seek +
   * play/pause, echo-suppressed). No attach-settle window afterwards.
   *
   * If the restart can't complete in time the engine falls back to the late-join behaviour.
   */
  onItemChanged(leader: boolean): EngineOutput {
    const now = this.clock.localNowMs()
    // Room writes up to the current seq were about the old item. The new item's (from the
    // leader) can only come later: the leader broadcasts at least itemSettleMinMs after its own
    // change, and its new now-playing info is written before that.
    this.restart = {
      leader,
      startedAt: now,
      roomSeqFloor: this.roomState?.seq ?? Number.NEGATIVE_INFINITY,
      sawNewSnapshot: false,
      roomReady: false
    }
    this.ownWriteGuard = null
    // Nothing tracked against the old item's timeline applies to the new one.
    this.echo.clear()
    this.transientJump = null
    this.catchUpResumeAt = null
    this.consecutiveCorrections = 0
    this._lastDriftMs = null
    this.needsAlignment = false
    this.settleUntil = now // no attach-settle window: the restart has its own rules
    const output = mergeOutput(this.endNudge(), this.advanceRestart(now))
    this.updateStatus()
    return output
  }

  // =============================================================================================
  // Local changes → broadcasts
  // =============================================================================================

  private detectLocalAction(previous: LocalSnapshot, current: LocalSnapshot, now: number): EngineOutput {
    // Seek detection: extrapolate the previous reading to now and compare with what the player
    // reports now. While playing both advance at the same rate, so a gap of more than the seek
    // threshold can only be a jump: the user scrubbed. Readings that aren't playing (buffering
    // included) aren't extrapolated: a stalled player's position doesn't move, so a network stall
    // (playing@a -> buffering@a ... playing@a) is never a jump.
    const livePosition = localLivePositionMs(current, now)
    const expectedFromPrevious = localLivePositionMs(previous, now)
    let jumped = false
    if (this.timelineEnabled && livePosition >= 0 && expectedFromPrevious >= 0) {
      if (isTransient(current.state)) {
        // Entering buffering. A video can freeze while the player still says playing, and only
        // later report buffering at the real (older) position. Anywhere from the last RAW
        // position to the extrapolated one is therefore a stall, not a scrub; with the seek
        // threshold as tolerance, but never reaching back more than stallWindowMaxMs behind the
        // extrapolation (the last raw report may be old):
        //   lower  = max(previous.positionMs - threshold, extrapolated - stallWindowMax)
        //   stall <=> lower <= live <= extrapolated + threshold
        // A scrub lands outside: back before that lower bound, or forward past the
        // extrapolation. (When previous wasn't playing, raw == extrapolated.)
        const stallLower = Math.max(
          previous.positionMs - this.config.seekThresholdMs,
          expectedFromPrevious - this.config.stallWindowMaxMs
        )
        jumped = livePosition < stallLower || livePosition > expectedFromPrevious + this.config.seekThresholdMs
      } else {
        jumped = Math.abs(livePosition - expectedFromPrevious) > this.config.seekThresholdMs
      }
    }

    if (isTransient(current.state)) {
      // Buffering/skipping, often right after our own play or seek: hold the echo expectations
      // until the player settles.
      if (this.transientSince === null) this.transientSince = now
      this.echo.hold(now)
      // A scrub usually reads playing@a -> buffering@b -> playing@b: the jump is only visible on
      // this reading, so remember it and decide once the player settles. (During an item change,
      // jumps are part of the switch.)
      if (jumped && this.restart === null) this.noteTransientJump(livePosition, now)
      return NO_OUTPUT
    }
    const newStatus = toRoomStatus(current.state)
    if (newStatus === null) return NO_OUTPUT

    // Settled (playing/paused). Close any transient phase.
    const pendingJump = this.transientJump
    if (this.transientSince !== null) this.echo.release(now)
    this.transientSince = null
    this.transientJump = null
    const statusChanged = newStatus !== this.localStatus
    this.localStatus = newStatus

    // Mid item change: this is the player switching items; advanceRestart() takes it from here.
    if (this.restart !== null) return NO_OUTPUT

    if (!statusChanged && !jumped && pendingJump === null) return NO_OUTPUT

    if (now < this.settleUntil || pendingJump === 'startup') {
      // The player is still starting up (autoplay, jumping to its resume point). That's not the
      // user; pull it back in line with the room instead of broadcasting it, but only a few
      // times; after that, once more when the window is over.
      if (this.settleRealigns < this.config.maxSettleRealigns) {
        this.settleRealigns++
        this.needsAlignment = true
      } else {
        this.realignWhenSettled = true
      }
      return NO_OUTPUT
    }

    // Echo suppression: a change we caused by applying a remote command is not a user action.
    // A jump already judged on the buffering reading (pendingJump === 'user') stays a user seek.
    const userStatusChange = statusChanged && !this.echo.matchesStatus(newStatus, now)
    const userSeek = (jumped && !this.echo.matchesSeek(livePosition, now)) || pendingJump === 'user'
    if (!userStatusChange && !userSeek) return NO_OUTPUT

    return this.broadcastUserChange(newStatus, livePosition, userStatusChange, now)
  }

  /** A jump seen on entering a transient state: our own seek, player start-up, or the user. */
  private noteTransientJump(livePosition: number, now: number): void {
    if (this.transientJump === 'startup') return // keep: realigning is the safe outcome
    if (now < this.settleUntil) {
      this.transientJump = 'startup'
      return
    }
    // Buffering at (or, if it extrapolates, near) the target of our own seek.
    if (this.echo.matchesSeek(livePosition, now)) return
    this.transientJump = 'user'
  }

  /**
   * The player has been transient (buffering) for transientHoldMs since it entered that state
   * (local time). Stop waiting for it to settle: broadcast a pending scrub at the position it's
   * buffering at, keeping our last stable play/pause state, or realign a start-up jump.
   */
  private expireTransientHold(now: number): EngineOutput {
    const since = this.transientSince
    if (since === null) return NO_OUTPUT
    if (now - since < this.config.transientHoldMs) return NO_OUTPUT
    const jump = this.transientJump
    if (jump === null) return NO_OUTPUT
    this.transientJump = null
    if (jump === 'startup') {
      this.needsAlignment = true
      return NO_OUTPUT // onTick aligns right after this
    }
    const status = this.localStatus
    const snap = this.snapshot
    if (status === null || snap === null) return NO_OUTPUT
    return this.broadcastUserChange(status, localLivePositionMs(snap, now), false, now)
  }

  /** A user play/pause and/or seek, already told apart from echoes: write it to the room. */
  private broadcastUserChange(newStatus: PlaybackStatus, livePosition: number, userStatusChange: boolean, now: number): EngineOutput {
    // A real user action overrides any catch-up pause we were running.
    this.catchUpResumeAt = null

    const room = this.roomState
    const roomExpected = room !== null ? roomExpectedPositionMs(room, this.clock.serverNowMs()) : null
    // No usable local position: keep the room's timeline.
    const position = this.timelineEnabled ? livePosition : (roomExpected ?? 0)

    // Already what the room says (a late echo, or two people pressing the same button):
    // broadcasting would only bump seq and make everyone re-check.
    if (
      room !== null &&
      roomExpected !== null &&
      room.status === newStatus &&
      (!this.timelineEnabled || Math.abs(position - roomExpected) <= this.config.driftThresholdMs)
    ) {
      return NO_OUTPUT
    }

    const reason: BroadcastReason = userStatusChange ? (newStatus === 'playing' ? 'play' : 'pause') : 'seek'
    // Our position becomes the room's: a nudge toward the old timeline would now pull us away.
    return mergeOutput(this.endNudge(), this.broadcast(newStatus, position, reason, now))
  }

  private broadcast(status: PlaybackStatus, positionMs: number, reason: BroadcastReason, now: number): EngineOutput {
    this.lastBroadcastAt = now
    return engineOutput({ broadcast: { status, positionMs: Math.max(0, positionMs), reason } })
  }

  // =============================================================================================
  // Item change
  // =============================================================================================

  /**
   * Moves an in-progress item change forward; called after every input. Finishes it once the
   * player has settled on the new item (and, for a member, the new item's room state is in), or
   * falls back to the late-join path after itemRestartTimeoutMs.
   */
  private advanceRestart(now: number): EngineOutput {
    const r = this.restart
    if (r === null) return NO_OUTPUT
    // Timing: all local ms. sinceChange = time since onItemChanged.
    const sinceChange = now - r.startedAt
    const snap = this.snapshot
    const status = snap !== null ? toRoomStatus(snap.state) : null

    // Is the current reading the new item's? Yes if a new reading arrived after the change, or
    // if it was sampled (local time) no more than itemFreshToleranceMs before the change: players
    // often publish the new state a moment before the new metadata. After itemSettleMaxMs, take
    // whatever settled reading there is.
    const fresh =
      snap !== null &&
      (r.sawNewSnapshot || (snap.sampledAtMs !== null && snap.sampledAtMs >= r.startedAt - this.config.itemFreshToleranceMs))
    const settled = snap !== null && status !== null && (fresh || sinceChange >= this.config.itemSettleMaxMs)

    if (r.leader) {
      if (settled && sinceChange >= this.config.itemSettleMinMs) return this.finishLeaderRestart(snap, status, now)
    } else if (settled && r.roomReady) {
      return this.finishMemberRestart(now)
    }

    if (sinceChange >= this.config.itemRestartTimeoutMs) {
      // The player never settled on the new item, or (member) no write for it arrived. Fall back
      // to what a re-attach does: align to the room, start-up changes ignored for a bit.
      this.restart = null
      this.needsAlignment = true
      this.settleUntil = now + this.config.attachSettleMs
    }
    return NO_OUTPUT
  }

  /** Leader: the new item's state becomes the room's. */
  private finishLeaderRestart(snap: LocalSnapshot, status: PlaybackStatus, now: number): EngineOutput {
    this.restart = null
    this.needsAlignment = false
    this.localStatus = status
    // Members extrapolate from the server's updatedAt, a network delay after this sample
    // (~100 ms); the same small lag every broadcast has.
    const live = localLivePositionMs(snap, now)
    const position = this.timelineEnabled && live >= 0 ? live : 0
    // Writes already in flight from others are about the old item: don't apply them until ours
    // has landed (Firebase delivers our own write to us first), or itemOwnWriteWaitMs.
    this.ownWriteGuard = {
      seqFloor: this.roomState?.seq ?? Number.NEGATIVE_INFINITY,
      until: now + this.config.itemOwnWriteWaitMs
    }
    return this.broadcast(status, position, 'seek', now)
  }

  /** Member: line the new item up with the leader's state for it. */
  private finishMemberRestart(now: number): EngineOutput {
    this.restart = null
    const room = this.roomState
    if (room === null) {
      this.needsAlignment = true // room state was cleared meanwhile: nothing to follow
      return NO_OUTPUT
    }
    this.needsAlignment = false
    return this.applyRoomState(room, now)
  }

  // =============================================================================================
  // Room → local commands
  // =============================================================================================

  /** Late join / reattach: bring the local player to the room's current state. */
  private align(now: number): EngineOutput {
    const snap = this.snapshot
    const status = this.localStatus
    if (snap === null || status === null || !this.roomLoaded || this._mode === 'manualCountdown') return NO_OUTPUT
    const room = this.roomState
    if (room === null) {
      // Nobody has played yet. Once our player has settled, seed the room with our state so
      // there's something for everyone else to align to.
      if (now < this.settleUntil) return NO_OUTPUT
      this.needsAlignment = false
      const position = this.timelineEnabled ? localLivePositionMs(snap, now) : 0
      return this.broadcast(status, position, status === 'playing' ? 'play' : 'pause', now)
    }
    this.needsAlignment = false
    return this.applyRoomState(room, now)
  }

  /**
   * Commands that make the local player match `room`. Order matters: seek before play (so we
   * don't play a moment from the wrong spot), pause before seek (so the seek lands where everyone
   * is paused).
   */
  private applyRoomState(room: RoomState, now: number): EngineOutput {
    const snap = this.snapshot
    if (snap === null) return NO_OUTPUT
    if (this._mode === 'manualCountdown') return NO_OUTPUT
    this.catchUpResumeAt = null
    // The room is authoritative: a later write wins over a scrub still buffering here.
    this.transientJump = null

    const target = roomExpectedPositionMs(room, this.clock.serverNowMs())
    const local = localLivePositionMs(snap, now)
    const needsSeek = this._mode === 'full' && local >= 0 && Math.abs(local - target) > this.config.driftThresholdMs
    const needsStatus = this.localStatus !== room.status

    const commands: SyncCommand[] = []
    if (room.status === 'playing') {
      if (needsSeek) commands.push({ type: 'seek', positionMs: target })
      if (needsStatus) commands.push({ type: 'play' })
    } else {
      if (needsStatus) commands.push({ type: 'pause' })
      if (needsSeek) commands.push({ type: 'seek', positionMs: target })
    }
    return this.issue(commands, now)
  }

  /** Records what each command should cause, so the resulting readings aren't re-broadcast. */
  private issue(commands: SyncCommand[], now: number): EngineOutput {
    if (commands.length === 0) return NO_OUTPUT
    for (const command of commands) {
      switch (command.type) {
        case 'play':
          this.echo.expectStatus('playing', now)
          break
        case 'pause':
          this.echo.expectStatus('paused', now)
          break
        case 'seek':
          this.echo.expectSeek(command.positionMs, now)
          this.lastCorrectionAt = now
          break
        case 'rate':
          break
      }
    }
    // Any play/pause/seek starts the timeline afresh: a running nudge would drift us off it.
    return mergeOutput(this.endNudge(), engineOutput({ commands }))
  }

  // =============================================================================================
  // Drift correction (every driftCheckIntervalMs)
  // =============================================================================================

  private checkDrift(snap: LocalSnapshot, now: number): EngineOutput {
    const room = this.roomState
    if (room === null) return NO_OUTPUT
    if (this.needsAlignment || this._mode === 'manualCountdown' || this.catchUpResumeAt !== null) return NO_OUTPUT
    // Mid item change, the room's timeline is the old item's.
    if (this.restart !== null) return NO_OUTPUT
    // Buffering/skipping: the position isn't moving and the player may be mid-seek (ours or the
    // user's). Wait for it to settle, but at most transientHoldMs (local) since it started.
    if (this.transientSince !== null && now - this.transientSince < this.config.transientHoldMs) return NO_OUTPUT
    // Let our last command, or our last broadcast, land before judging drift.
    if (this.echo.hasPending(now)) return NO_OUTPUT
    if (this.lastBroadcastAt !== null && now - this.lastBroadcastAt < this.config.broadcastGraceMs) return NO_OUTPUT

    const cooledDown = this.lastCorrectionAt === null || now - this.lastCorrectionAt >= this.config.correctionCooldownMs

    // Play/pause disagrees with the room for a whole check interval (the player ignored a
    // command, or we missed one): re-apply the room state.
    if (this.localStatus !== null && this.localStatus !== room.status) {
      if (!cooledDown) return NO_OUTPUT
      this.lastCorrectionAt = now
      return this.applyRoomState(room, now)
    }

    if (!this.timelineEnabled) {
      this._lastDriftMs = null
      return NO_OUTPUT
    }

    const local = localLivePositionMs(snap, now)
    if (local < 0) return NO_OUTPUT
    const expected = roomExpectedPositionMs(room, this.clock.serverNowMs())
    const drift = local - expected // > 0: we're ahead of the room
    this._lastDriftMs = drift

    if (this._mode === 'full') return this.correctBySeeking(drift, expected, cooledDown, now)
    if (this._mode === 'playPauseCatchUp') return this.catchUp(room, local, cooledDown, now)
    return NO_OUTPUT
  }

  private correctBySeeking(driftMs: number, expected: number, cooledDown: boolean, now: number): EngineOutput {
    if (Math.abs(driftMs) <= this.config.driftThresholdMs) {
      this.consecutiveCorrections = 0
      // Android stops here; a player that can change speed closes the small drift smoothly.
      return this.nudgeToward(driftMs, now)
    }
    if (!cooledDown) return NO_OUTPUT
    if (this.consecutiveCorrections >= this.config.maxConsecutiveCorrections) {
      // We've seeked several times and are still off: the player ignores seeks or reports a
      // stale position. Stop fighting it; fall back to play/pause-only sync.
      this.capabilities.markPositionUnreliable()
      this._mode = this.selectMode()
      this._lastDriftMs = null
      this.consecutiveCorrections = 0
      return this.endNudge()
    }
    this.consecutiveCorrections++
    return this.issue([{ type: 'seek', positionMs: expected }], now)
  }

  /**
   * No-seek fallback: whoever is ahead of the slowest member pauses for exactly as long as
   * they're ahead. Members who are behind do nothing; the ones ahead wait for them.
   */
  private catchUp(room: RoomState, localMs: number, cooledDown: boolean, now: number): EngineOutput {
    if (room.status !== 'playing' || this.localStatus !== 'playing' || !cooledDown) return NO_OUTPUT
    const serverNow = this.clock.serverNowMs()
    // Reference: slowest online member with a fresh report (extrapolated to server-now), or the
    // room's expected position if nobody else has reported.
    let reference: number | null = null
    for (const m of this.members) {
      if (m.uid === this.myUid || !m.online) continue
      const p = memberExpectedPositionMs(m, room.status, serverNow, this.config.memberReportMaxAgeMs)
      if (p !== null && (reference === null || p < reference)) reference = p
    }
    reference ??= roomExpectedPositionMs(room, serverNow)
    const aheadMs = localMs - reference
    if (aheadMs <= this.config.driftThresholdMs) return NO_OUTPUT

    this.lastCorrectionAt = now
    // While paused we don't move and they do, so pausing for aheadMs closes the gap.
    this.catchUpResumeAt = now + Math.min(aheadMs, this.config.maxCatchUpPauseMs)
    return this.issue([{ type: 'pause' }], now)
  }

  // =============================================================================================
  // Speed nudges (desktop addition)
  // =============================================================================================

  /** Starts, re-plans or ends a nudge for a drift at or below driftThresholdMs. */
  private nudgeToward(driftMs: number, now: number): EngineOutput {
    if (!this.canNudge()) return this.endNudge()
    const size = Math.abs(driftMs)
    const running = this.nudge
    if (running !== null) {
      // Done: close enough, or we've overshot (the drift changed sign).
      if (size <= this.config.rateNudgeDoneMs || driftMs > 0 !== running.ahead) return this.endNudge()
      // Still off the same way: keep going, re-planned from the measured drift (formula below).
      running.endsAt = now + size / this.config.rateNudgeDelta
      return NO_OUTPUT
    }
    if (size <= this.config.rateNudgeMinDriftMs) return NO_OUTPUT
    // Ahead: play slower; behind: play faster. While nudged the room's expected position moves
    // 1 ms per ms and ours (1 ∓ delta) ms per ms, so the gap closes by delta ms per ms:
    //   duration = |drift| / delta          e.g. 1 s at 2 % = 50 s
    const ahead = driftMs > 0
    const rate = ahead ? 1 - this.config.rateNudgeDelta : 1 + this.config.rateNudgeDelta
    this.nudge = { rate, ahead, startedAt: now, endsAt: now + size / this.config.rateNudgeDelta, applied: false }
    return engineOutput({ commands: [{ type: 'rate', playbackRate: rate }] })
  }

  /** Nudges only make sense while we and the room play normally in full mode. */
  private canNudge(): boolean {
    const snap = this.snapshot
    if (!this.config.rateNudges || this._mode !== 'full' || !this.capabilities.supportsRate) return false
    if (snap === null || snap.state !== 'playing' || this.localStatus !== 'playing') return false
    if (this.roomState?.status !== 'playing') return false
    // The viewer chose another speed (say 1.25x): leave it alone.
    const rate = snap.rate > 0 ? snap.rate : 1
    return nearRate(rate, 1) || (this.nudge !== null && nearRate(rate, this.nudge.rate))
  }

  /** What a playing reading says about the running nudge. */
  private observeNudgeRate(snapshot: LocalSnapshot): void {
    const nudge = this.nudge
    if (nudge === null || snapshot.state !== 'playing') return
    const rate = snapshot.rate > 0 ? snapshot.rate : 1
    if (nearRate(rate, nudge.rate)) {
      nudge.applied = true
    } else if (nearRate(rate, 1)) {
      // Not applied yet (command in flight), or the page put it back to 1x by itself.
      if (nudge.applied) this.nudge = null
    } else {
      // The viewer picked another speed: theirs wins, and there's nothing for us to undo.
      this.nudge = null
    }
  }

  /** onTick: ends a nudge on time, or when it can't work. */
  private advanceNudge(now: number): EngineOutput {
    const nudge = this.nudge
    if (nudge === null) return NO_OUTPUT
    if (!this.config.rateNudges || this._mode !== 'full') return this.endNudge()
    if (!nudge.applied && now - nudge.startedAt >= this.config.rateNudgeApplyTimeoutMs) {
      // No reading ever showed the new speed: the player ignores playbackRate. Stop nudging on
      // this attach (seeks above the threshold still work), and undo in case it applied silently.
      this.capabilities.markRateUnreliable()
      return this.endNudge()
    }
    if (now >= nudge.endsAt) return this.endNudge()
    return NO_OUTPUT
  }

  /** Back to 1x if a nudge is running. */
  private endNudge(): EngineOutput {
    if (this.nudge === null) return NO_OUTPUT
    this.nudge = null
    return engineOutput({ commands: [RATE_NORMAL] })
  }

  // =============================================================================================
  // Helpers
  // =============================================================================================

  /** The player went away. An item change in progress survives it (players are hidden while switching). */
  private detach(): void {
    this.snapshot = null
    this.localStatus = null
    this.needsAlignment = true
    this.realignWhenSettled = false
    this.transientSince = null
    this.transientJump = null
    this.echo.clear()
    this.capabilities.reset()
    this.catchUpResumeAt = null
    this._lastDriftMs = null
    this.consecutiveCorrections = 0
    this.nudge = null // nothing left to send "back to 1x" to
    this._mode = 'manualCountdown'
  }

  private selectMode(): SyncMode {
    if (this.snapshot === null || !this.capabilities.supportsPlayPause) return 'manualCountdown'
    if (!this.capabilities.positionReliable) return 'playPauseOnly'
    if (!this.capabilities.supportsSeek) return 'playPauseCatchUp'
    return 'full'
  }

  private updateStatus(): void {
    const drift = this._lastDriftMs
    if (this.snapshot === null) this._status = this.everAttached ? 'sessionLost' : 'appNotOpen'
    else if (this.catchUpResumeAt !== null) this._status = 'catchingUp'
    else if (this._mode === 'full' && drift !== null && Math.abs(drift) > this.config.driftThresholdMs) this._status = 'correcting'
    else this._status = 'syncing'
  }
}

function due(last: number | null, intervalMs: number, now: number): boolean {
  return last === null || now - last >= intervalMs
}
