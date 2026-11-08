// Position extrapolation (port of sync-engine/.../PositionMath.kt). Every function is pure;
// callers pass "now" explicitly. Results are whole milliseconds, like Android's Long math.

import type { LocalSnapshot, Member, PlaybackStatus, RoomState } from './types.ts'

/**
 * Live position of the local player.
 *
 *   live = position + (nowLocal - sampledAt) * rate     while playing
 *   live = position                                     otherwise
 *
 * Both times are the local monotonic clock (performance.now()), so wall-clock changes don't
 * matter. Readings arrive only every so often, so this is how we know where the player is now.
 */
export function localLivePositionMs(snapshot: LocalSnapshot, nowLocalMs: number): number {
  if (snapshot.positionMs < 0) return snapshot.positionMs // POSITION_UNKNOWN
  if (snapshot.state !== 'playing') return snapshot.positionMs
  // A reading without a sample time gives us nothing to extrapolate from.
  if (snapshot.sampledAtMs === null) return snapshot.positionMs
  const sinceSampleMs = Math.max(0, nowLocalMs - snapshot.sampledAtMs)
  // Some players report rate 0 while playing; real playback is then 1x.
  const rate = snapshot.rate > 0 ? snapshot.rate : 1
  return snapshot.positionMs + Math.trunc(sinceSampleMs * rate)
}

/**
 * Where the room says everyone should be right now.
 *
 *   expected = positionMs + (serverNow - updatedAt)     while playing
 *   expected = positionMs                               while paused
 *
 * updatedAt was stamped by the server when the write landed, and serverNow is our estimate of
 * the same server clock, so the network delay of the write is already included: a device that
 * hears about a "play" 300 ms late computes a position 300 ms further on. Never negative time:
 * a server estimate slightly behind updatedAt doesn't move the position backwards.
 */
export function roomExpectedPositionMs(state: RoomState, serverNowMs: number): number {
  if (state.status !== 'playing') return state.positionMs
  const sinceUpdateMs = Math.max(0, serverNowMs - state.updatedAt)
  return state.positionMs + Math.trunc(sinceUpdateMs)
}

/**
 * A member's position extrapolated from their last report (reportedAt is server time), assuming
 * they've been following the room's play/pause state since:
 *
 *   expected = positionMs + (serverNow - reportedAt)     while the room plays
 *   expected = positionMs                               otherwise
 *
 * Null if they haven't reported, or the report is older than maxAgeMs.
 */
export function memberExpectedPositionMs(
  member: Member,
  roomStatus: PlaybackStatus | null,
  serverNowMs: number,
  maxAgeMs: number
): number | null {
  if (member.positionMs === null || member.reportedAt === null) return null
  const ageMs = Math.max(0, serverNowMs - member.reportedAt)
  if (ageMs > maxAgeMs) return null
  return roomStatus === 'playing' ? member.positionMs + Math.trunc(ageMs) : member.positionMs
}
