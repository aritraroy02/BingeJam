// Contracts between the room session (src/renderer/src/sync/roomSession.ts) and its two sides:
// the room backend (Firebase, src/renderer/src/sync/firebaseRoomTransport.ts) and the local
// player (the extension bridge). Pure types, like the Android :core module.

import type { BridgeMessage, PlayerCommandMessage } from '../player.ts'
import type { NowPlaying, Room, RoomPreview, ServerClock, StateBroadcast } from './types.ts'

export type Unsubscribe = () => void

/**
 * Room-state backend (port of the sync part of Android's SyncTransport). Every timestamp written
 * through it is stamped by the server. Room creation, joining and membership live elsewhere
 * (src/renderer/src/data); the session starts after we're a member.
 */
export interface RoomTransport {
  /** The signed-in uid; writes carry it as updatedBy / startedBy. */
  readonly uid: string
  readonly serverClock: ServerClock
  /** rooms/{code}; null if it doesn't exist or we can't read it (not a member). */
  observeRoom(code: string, listener: (room: Room | null) => void): Unsubscribe
  /** previews/{code}; null if there's none. */
  observePreview(code: string, listener: (preview: RoomPreview | null) => void): Unsubscribe
  /** rooms/{code}/state; updatedAt and seq are set server-side. */
  writeState(code: string, broadcast: StateBroadcast): Promise<void>
  /** rooms/{code}/members/{uid} positionMs + reportedAt. */
  reportPosition(code: string, positionMs: number): Promise<void>
  /** rooms/{code}/countdown: everyone presses play at server time requestedAt + leadMs. */
  startCountdown(code: string, leadMs: number): Promise<void>
  /** Host only: previews/{code} service/title/subtitle/durationMs. */
  setNowPlaying(code: string, nowPlaying: NowPlaying): Promise<void>
}

/** The player events the session needs: exactly what the extension bridge forwards. */
export type PlayerEvent = Extract<BridgeMessage, { kind: 'snapshot' } | { kind: 'tabClosed' }>

/**
 * This PC's player(s): every browser tab the extension controls, multiplexed by tabId. The real
 * implementation forwards BridgeMessages from the main process over IPC.
 */
export interface Player {
  send(message: PlayerCommandMessage): void
  subscribe(listener: (event: PlayerEvent) => void): Unsubscribe
}

/** Timer functions, injectable so tests run on a fake clock. */
export interface Timers {
  setInterval(fn: () => void, ms: number): unknown
  clearInterval(handle: unknown): void
  setTimeout(fn: () => void, ms: number): unknown
  clearTimeout(handle: unknown): void
}
