// Test helpers (port of sync-engine/src/test/.../TestSupport.kt). Not a test file itself.

import { DEFAULT_SYNC_CONFIG, type SyncConfig } from './config.ts'
import { SyncEngine } from './engine.ts'
import type { LocalSnapshot, LocalState, PlaybackStatus, PlayerCapabilities, RoomState, SyncClock } from './types.ts'

/** Both clocks move together; tests advance them explicitly. */
export class FakeClock implements SyncClock {
  local: number
  server: number
  constructor(local = 1_000_000, server = 1_700_000_000_000) {
    this.local = local
    this.server = server
  }
  localNowMs(): number {
    return this.local
  }
  serverNowMs(): number {
    return this.server
  }
  advance(ms: number): void {
    this.local += ms
    this.server += ms
  }
}

export const ME = 'me'
export const HOST = 'host'
export const START_LOCAL = 1_000_000

/** Android ALL_ACTIONS (PLAY, PAUSE, PLAY_PAUSE, SEEK_TO): no speed control, as the Kotlin engine never used it. */
export const ALL_ACTIONS: PlayerCapabilities = { playPause: true, seek: true, rate: false }
export const NO_SEEK_ACTIONS: PlayerCapabilities = { playPause: true, seek: false, rate: false }
/** An HTML5 video: everything, including playbackRate. */
export const RATE_ACTIONS: PlayerCapabilities = { playPause: true, seek: true, rate: true }
export const NO_ACTIONS: PlayerCapabilities = { playPause: false, seek: false, rate: false }

export function snapshot(clock: FakeClock, state: LocalState, positionMs: number, actions = ALL_ACTIONS, rate = 1): LocalSnapshot {
  return { state, positionMs, sampledAtMs: clock.local, rate, capabilities: actions }
}
/** A playing reading sampled "now". */
export function playing(clock: FakeClock, positionMs: number, actions = ALL_ACTIONS, rate = 1): LocalSnapshot {
  return snapshot(clock, 'playing', positionMs, actions, rate)
}
/** A paused reading sampled "now" (Android reports speed 0). */
export function paused(clock: FakeClock, positionMs: number, actions = ALL_ACTIONS): LocalSnapshot {
  return snapshot(clock, 'paused', positionMs, actions, 0)
}
/** A buffering reading sampled "now" (position not advancing). */
export function buffering(clock: FakeClock, positionMs: number, actions = ALL_ACTIONS): LocalSnapshot {
  return snapshot(clock, 'buffering', positionMs, actions, 0)
}
/** The player changing tracks (Android SKIPPING_TO_NEXT). */
export function skippingToNext(clock: FakeClock, positionMs: number, actions = ALL_ACTIONS): LocalSnapshot {
  return snapshot(clock, 'skipping', positionMs, actions, 0)
}

/** A room state written "now" (server time) unless updatedAt is given. */
export function roomState(
  clock: FakeClock,
  status: PlaybackStatus,
  positionMs: number,
  seq: number,
  by: string = HOST,
  updatedAt: number = clock.server
): RoomState {
  return { status, positionMs, updatedAt, updatedBy: by, seq }
}

export interface SyncedOptions {
  actions?: PlayerCapabilities
  positionMs?: number
  config?: SyncConfig
}

/**
 * An engine attached to a playing player, aligned with a room playing at the same spot, and past
 * the attach-settle window. Afterwards local and room position are both positionMs + attachSettleMs + 1.
 */
export function syncedPlaying(clock: FakeClock, options: SyncedOptions = {}): SyncEngine {
  const config = options.config ?? DEFAULT_SYNC_CONFIG
  const positionMs = options.positionMs ?? 60_000
  const engine = new SyncEngine(ME, clock, config)
  engine.onRoom(roomState(clock, 'playing', positionMs, 1), [])
  engine.onLocalSnapshot(playing(clock, positionMs, options.actions ?? ALL_ACTIONS))
  clock.advance(config.attachSettleMs + 1)
  return engine
}

/** Same as syncedPlaying but paused at positionMs (default 10_000). */
export function syncedPaused(clock: FakeClock, options: SyncedOptions = {}): SyncEngine {
  const config = options.config ?? DEFAULT_SYNC_CONFIG
  const positionMs = options.positionMs ?? 10_000
  const engine = new SyncEngine(ME, clock, config)
  engine.onRoom(roomState(clock, 'paused', positionMs, 1), [])
  engine.onLocalSnapshot(paused(clock, positionMs, options.actions ?? ALL_ACTIONS))
  clock.advance(config.attachSettleMs + 1)
  return engine
}

export const PLAY = { type: 'play' } as const
export const PAUSE = { type: 'pause' } as const
export const seekTo = (positionMs: number) => ({ type: 'seek', positionMs }) as const
export const rate = (playbackRate: number) => ({ type: 'rate', playbackRate }) as const
