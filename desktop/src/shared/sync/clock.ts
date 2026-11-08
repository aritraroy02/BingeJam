// Clocks for the desktop sync: the server-time estimate (pure logic of the Android app's
// FirebaseServerClock) and the conversion of the extension's PlayerSnapshot to the engine's
// local clock (referenced from ../player.ts).

import type { PlayerSnapshot } from '../player.ts'
import { POSITION_UNKNOWN, type LocalSnapshot, type LocalState, type PlayerCapabilities, type ServerClock } from './types.ts'

/**
 * Estimates Firebase server time.
 *
 * Firebase publishes .info/serverTimeOffset, defined as (server time − this machine's wall clock).
 * Reading the wall clock on every call would let a user changing the clock, or an NTP jump, shift
 * our sync math. So the wall clock is read exactly once per offset update (and once per
 * reconnect) to set an anchor, then we advance from that anchor with the monotonic clock:
 *
 *   anchorServer = wallClockAtSample + offset
 *   serverNow()  = anchorServer + (monotonicNow() − monotonicAtSample)
 *
 * The injected wallNow is the ONE documented use of the wall clock (Date.now()) in the desktop
 * sync, and only to interpret Firebase's offset, which is defined against it.
 */
export class AnchoredServerClock implements ServerClock {
  private readonly monotonicNow: () => number
  private readonly wallNow: () => number
  private anchorServerMs: number
  private anchorMonotonicMs: number
  private offsetMs = 0
  private _synced = false

  constructor(monotonicNow: () => number, wallNow: () => number) {
    this.monotonicNow = monotonicNow
    this.wallNow = wallNow
    // Until Firebase sends an offset: offset 0 (wall clock), still advanced monotonically.
    this.anchorMonotonicMs = monotonicNow()
    this.anchorServerMs = wallNow()
  }

  /** False until Firebase has sent at least one offset. */
  get synced(): boolean {
    return this._synced
  }

  /** A new .info/serverTimeOffset value. */
  onOffset(offsetMs: number): void {
    if (!Number.isFinite(offsetMs)) return
    this.offsetMs = offsetMs
    this._synced = true
    this.reanchor()
  }

  /**
   * Re-samples the anchor with the last known offset, e.g. when the connection reopens after the
   * PC slept (performance.now() may not advance during sleep on every platform).
   */
  reanchor(): void {
    // Sample both clocks back to back so the anchor pair is consistent.
    const monotonic = this.monotonicNow()
    this.anchorServerMs = this.wallNow() + this.offsetMs
    this.anchorMonotonicMs = monotonic
  }

  serverNowMs(): number {
    return this.anchorServerMs + (this.monotonicNow() - this.anchorMonotonicMs)
  }
}

/** What an HTML5 video in a browser tab can do; the extension doesn't report capabilities yet. */
export const BROWSER_VIDEO_CAPABILITIES: PlayerCapabilities = Object.freeze({ playPause: true, seek: true, rate: true })

/**
 * PlayerSnapshot (extension, its own clocks) → LocalSnapshot (engine, this renderer's monotonic
 * clock). Clocks of different processes can't be compared, so the sample time is reconstructed
 * from when the snapshot arrived here and how long the hops measured it travelling:
 *
 *   sampledAt (local) = receivedAt (local monotonic) − ageMs
 *
 * Unmeasured hop latency (a few ms over native messaging + IPC) makes the reading look that much
 * newer, i.e. we under-extrapolate by the same few ms: far below any threshold.
 *
 * State: buffering → 'buffering' (transient: echo holds, no drift checks), or 'connecting' when
 * the position isn't known yet (like Netflix's first CONNECTING reading on Android, which must not
 * turn timeline sync off); otherwise playing / paused. An ended video reports paused.
 */
export function toLocalSnapshot(
  snapshot: PlayerSnapshot,
  receivedAtLocalMs: number,
  capabilities: PlayerCapabilities = BROWSER_VIDEO_CAPABILITIES
): LocalSnapshot {
  const known = Number.isFinite(snapshot.positionMs) && snapshot.positionMs >= 0
  const positionMs = known ? Math.round(snapshot.positionMs) : POSITION_UNKNOWN
  const ageMs = Number.isFinite(snapshot.ageMs) ? Math.max(0, snapshot.ageMs) : 0
  let state: LocalState
  if (snapshot.buffering) state = known ? 'buffering' : 'connecting'
  else state = snapshot.playing ? 'playing' : 'paused'
  const rate = Number.isFinite(snapshot.playbackRate) && snapshot.playbackRate > 0 ? snapshot.playbackRate : 1
  return { state, positionMs, sampledAtMs: receivedAtLocalMs - ageMs, rate, capabilities }
}
