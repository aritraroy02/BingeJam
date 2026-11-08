// Port of sync-engine/.../EchoSuppressor.kt.
//
// Remembers what we expect the local player to report after we apply a remote command, so that
// report isn't mistaken for a user action and broadcast back. Without this, two devices would
// bounce the same pause back and forth forever.
//
// All times are the local monotonic clock (ms).

import type { PlaybackStatus } from './types.ts'

interface Expectation {
  status: PlaybackStatus | null
  positionMs: number | null
  issuedAt: number
  expiresAt: number
  /** True while the player is buffering/skipping: kept alive until it settles (see hold). */
  held: boolean
}

export class EchoSuppressor {
  private readonly windowMs: number
  private readonly maxHoldMs: number
  private readonly seekToleranceMs: number
  private pending: Expectation[] = []

  constructor(windowMs: number, maxHoldMs: number, seekToleranceMs: number) {
    this.windowMs = windowMs
    this.maxHoldMs = maxHoldMs
    this.seekToleranceMs = seekToleranceMs
  }

  expectStatus(status: PlaybackStatus, now: number): void {
    this.pending.push({ status, positionMs: null, issuedAt: now, expiresAt: now + this.windowMs, held: false })
  }

  expectSeek(positionMs: number, now: number): void {
    this.pending.push({ status: null, positionMs, issuedAt: now, expiresAt: now + this.windowMs, held: false })
  }

  /** Matching doesn't consume: players often repeat the same state within the window. */
  matchesStatus(status: PlaybackStatus, now: number): boolean {
    this.prune(now)
    return this.pending.some((e) => e.status === status)
  }

  /**
   * A seek we issued to X at time t shows up as position ≈ X (paused) or ≈ X + (now − t)
   * (playing). Accept either, within the seek threshold.
   */
  matchesSeek(positionMs: number, now: number): boolean {
    this.prune(now)
    return this.pending.some((e) => {
      if (e.positionMs === null) return false
      const target = e.positionMs
      return (
        Math.abs(positionMs - target) <= this.seekToleranceMs ||
        Math.abs(positionMs - (target + (now - e.issuedAt))) <= this.seekToleranceMs
      )
    })
  }

  /**
   * The player is buffering/skipping: it hasn't finished applying our commands yet. Keep every
   * live expectation until it settles, however long one buffering phase lasts, but never past
   * maxHoldMs after the command was issued:
   *
   *   expiresAt = issuedAt + maxHoldMs        while held
   */
  hold(now: number): void {
    this.prune(now)
    for (const e of this.pending) {
      e.held = true
      e.expiresAt = Math.max(e.expiresAt, e.issuedAt + this.maxHoldMs)
    }
  }

  /**
   * The player settled (playing/paused again). Held expectations get one normal window from now,
   * so repeated readings right after settling still match, still capped at maxHoldMs:
   *
   *   expiresAt = min(issuedAt + maxHoldMs, now + windowMs)
   *
   * Releasing before matching the settling reading is fine: a held expectation still has windowMs
   * to go (unless it's already past maxHoldMs, when it should expire anyway).
   */
  release(now: number): void {
    for (const e of this.pending) {
      if (!e.held) continue
      e.held = false
      e.expiresAt = Math.min(e.issuedAt + this.maxHoldMs, now + this.windowMs)
    }
  }

  hasPending(now: number): boolean {
    this.prune(now)
    return this.pending.length > 0
  }

  clear(): void {
    this.pending = []
  }

  private prune(now: number): void {
    this.pending = this.pending.filter((e) => e.expiresAt > now)
  }
}
