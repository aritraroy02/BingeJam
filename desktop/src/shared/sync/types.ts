// Models and contracts of the room sync, ported from the Android app's :core module
// (core/.../model/Room.kt, Sync.kt, PlaybackSnapshot.kt, sync/Clocks.kt).
// Pure TypeScript: no Firebase, no DOM, no Electron. String values that travel over the wire
// ('playing' | 'paused', service ids) are identical to Android's so phones and PCs agree.

import type { PlayerCommand, ServiceId } from '../player.ts'

// ---------------------------------------------------------------------------------------------
// Room (rooms/{code}, previews/{code})
// ---------------------------------------------------------------------------------------------

/** Room-level play state: the value stored in rooms/{code}/state/status (same as Android). */
export type PlaybackStatus = 'playing' | 'paused'

/**
 * The room's authoritative playback state: rooms/{code}/state.
 *
 * `updatedAt` is server time (ServerValue.TIMESTAMP). `seq` goes up by one on every write, in
 * the same server-side write, so a higher seq is always a later server write.
 */
export interface RoomState {
  status: PlaybackStatus
  positionMs: number
  updatedAt: number
  updatedBy: string
  seq: number
}

/** rooms/{code}/members/{uid}. `reportedAt` and `lastSeen` are server time. */
export interface Member {
  uid: string
  name: string
  online: boolean
  positionMs: number | null
  reportedAt: number | null
  lastSeen: number | null
  /** In the room's voice chat (members/{uid}/voice). */
  inVoice: boolean
}

/** rooms/{code}/title: what the host picked to watch. */
export interface RoomTitle {
  service: ServiceId
  deepLink: string
  setBy: string
}

/**
 * rooms/{code}/countdown: manual "3, 2, 1, play" fallback. Everyone presses play at server time
 * `requestedAt + leadMs` (see countdownPlayAt).
 */
export interface Countdown {
  requestedAt: number
  leadMs: number
  startedBy: string
}

export interface Room {
  code: string
  hostUid: string
  createdAt: number
  title: RoomTitle | null
  state: RoomState | null
  members: Member[]
  countdown: Countdown | null
}

/**
 * previews/{code}: what the host's player says is playing. Written by the host; readable by any
 * signed-in user who knows the code. `updatedAt` is server time. Artwork is left out here (the
 * sync doesn't need it).
 */
export interface RoomPreview {
  code: string
  hostName: string
  service: ServiceId | null
  title: string | null
  subtitle: string | null
  durationMs: number | null
  playableRef: string | null
  updatedAt: number
}

/** What the host publishes into previews/{code} while it plays (no artwork from a browser tab). */
export interface NowPlaying {
  service: ServiceId
  title: string | null
  subtitle: string | null
  durationMs: number | null
}

// ---------------------------------------------------------------------------------------------
// Local player (what the engine sees of this PC's player)
// ---------------------------------------------------------------------------------------------

/**
 * The local player's state. Mirrors the subset of android.media.session.PlaybackState the engine
 * distinguishes: 'buffering', 'connecting' and 'skipping' are transient (the player is busy and
 * will settle on 'playing' or 'paused'); 'none', 'stopped' and 'error' are neither a room state
 * nor transient.
 */
export type LocalState = 'none' | 'stopped' | 'paused' | 'playing' | 'buffering' | 'connecting' | 'skipping' | 'error'

/** Same value as PlaybackState.PLAYBACK_POSITION_UNKNOWN on Android. */
export const POSITION_UNKNOWN = -1

/** What the player can do. The engine keeps the union of everything it has seen per attach. */
export interface PlayerCapabilities {
  playPause: boolean
  seek: boolean
  /** Can change playbackRate (used for 0.98x / 1.02x drift nudges). */
  rate: boolean
}

/**
 * One reading of the local player, on the engine's local clock.
 *
 * Time base: `sampledAtMs` is the local monotonic time (SyncClock.localNowMs, performance.now()
 * in the renderer) at which `positionMs` was read; null if unknown (nothing to extrapolate from).
 * Never wall-clock time. A PlayerSnapshot from the extension is converted on receipt
 * (clock.ts toLocalSnapshot).
 */
export interface LocalSnapshot {
  state: LocalState
  /** Position in ms at `sampledAtMs`, or POSITION_UNKNOWN. */
  positionMs: number
  sampledAtMs: number | null
  /** Playback speed; 1 = normal. 0 while playing is treated as 1 (some players report that). */
  rate: number
  capabilities: PlayerCapabilities
}

// ---------------------------------------------------------------------------------------------
// Engine I/O
// ---------------------------------------------------------------------------------------------

/** A command the engine wants applied to the local player: exactly the extension's PlayerCommand. */
export type SyncCommand = PlayerCommand

export type BroadcastReason = 'play' | 'pause' | 'seek'

/** A local user action the engine wants written to rooms/{code}/state. */
export interface StateBroadcast {
  status: PlaybackStatus
  positionMs: number
  reason: BroadcastReason
}

/**
 * How much we can sync with the current player, picked at runtime from its capabilities and how
 * its position behaves (Android SyncMode).
 *  - full: play/pause + seek + drift correction (seeks above the threshold; speed nudges below it
 *    when the player can change speed, see SyncConfig.rateNudges).
 *  - playPauseCatchUp: no seek: play/pause only; whoever is ahead pauses briefly so others catch up.
 *  - playPauseOnly: position unknown / never updates: play/pause only, no timeline sync.
 *  - manualCountdown: no player, or it can't be played/paused: "3, 2, 1, play" countdown.
 */
export type SyncMode = 'full' | 'playPauseCatchUp' | 'playPauseOnly' | 'manualCountdown'

/** Android SyncStatus, minus the phone-only ones (notification access, unconfigured app). */
export type SyncStatus =
  | 'idle'
  | 'waitingForRoom'
  | 'appNotOpen'
  | 'sessionLost'
  /** Not the host, and this PC plays something other than the host's title/episode. */
  | 'otherTitle'
  | 'syncing'
  | 'correcting'
  | 'catchingUp'

/** Everything one engine call wants done. */
export interface EngineOutput {
  readonly commands: readonly SyncCommand[]
  readonly broadcast: StateBroadcast | null
  /** Our live position to publish to members/{uid} for the drift overlay. */
  readonly positionReport: number | null
}

export const NO_OUTPUT: EngineOutput = Object.freeze({ commands: Object.freeze([]) as readonly SyncCommand[], broadcast: null, positionReport: null })

export function engineOutput(parts: Partial<EngineOutput>): EngineOutput {
  return { commands: parts.commands ?? [], broadcast: parts.broadcast ?? null, positionReport: parts.positionReport ?? null }
}

/** Kotlin's EngineOutput.plus: commands in order; the later broadcast / report wins. */
export function mergeOutput(a: EngineOutput, b: EngineOutput): EngineOutput {
  if (b === NO_OUTPUT) return a
  if (a === NO_OUTPUT) return b
  return {
    commands: [...a.commands, ...b.commands],
    broadcast: b.broadcast ?? a.broadcast,
    positionReport: b.positionReport ?? a.positionReport
  }
}

// ---------------------------------------------------------------------------------------------
// Clocks
// ---------------------------------------------------------------------------------------------

/** Server time: the clock that produced every ServerValue.TIMESTAMP in the room. */
export interface ServerClock {
  serverNowMs(): number
}

/** The only time source the engine may use. Injected so tests can fake it. */
export interface SyncClock {
  /** Local monotonic time (performance.now() in the renderer), same base as LocalSnapshot.sampledAtMs. */
  localNowMs(): number
  /** Estimated current server time, same base as RoomState.updatedAt. */
  serverNowMs(): number
}

/** Server time at which a countdown says "play". */
export function countdownPlayAt(countdown: Countdown): number {
  return countdown.requestedAt + countdown.leadMs
}

/** Services whose rooms are watch parties (movie/TV); the rest are jams (StreamingService.isJam). */
export const WATCH_PARTY_SERVICES: readonly ServiceId[] = ['NETFLIX', 'JIOHOTSTAR', 'PRIME_VIDEO']

export function isJamService(service: ServiceId): boolean {
  return !WATCH_PARTY_SERVICES.includes(service)
}
