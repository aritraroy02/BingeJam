// Port of sync-engine/.../CapabilityDetector.kt.
//
// Learns at runtime what the attached player supports. Reset whenever the player goes away,
// because a reopened tab (or another site) may behave differently.

import type { SyncConfig } from './config.ts'
import type { LocalSnapshot } from './types.ts'

export class CapabilityDetector {
  private readonly config: SyncConfig

  // Union of every capability seen. Some players drop actions while buffering; don't flap on that.
  private seenPlayPause = false
  private seenSeek = false
  private seenRate = false

  private _positionReliable = true
  /** False once a speed nudge was never applied: the player ignores playbackRate. */
  private _rateReliable = true

  private playStartedAt: number | null = null
  private positionAtPlayStart = 0

  constructor(config: SyncConfig) {
    this.config = config
  }

  get positionReliable(): boolean {
    return this._positionReliable
  }

  get supportsPlayPause(): boolean {
    return this.seenPlayPause
  }

  get supportsSeek(): boolean {
    return this.seenSeek
  }

  /** Can change playbackRate, and hasn't been caught ignoring it. */
  get supportsRate(): boolean {
    return this.seenRate && this._rateReliable
  }

  observe(snapshot: LocalSnapshot, now: number): void {
    this.seenPlayPause ||= snapshot.capabilities.playPause
    this.seenSeek ||= snapshot.capabilities.seek
    this.seenRate ||= snapshot.capabilities.rate

    if (snapshot.positionMs < 0) {
      // Position unknown while actually playing or paused: the player tells us outright it has
      // no position. While it's still loading (Netflix's first reading after opening a title is
      // CONNECTING with -1, then PAUSED at the resume point) that says nothing.
      if (snapshot.state === 'playing' || snapshot.state === 'paused') this._positionReliable = false
      return
    }

    switch (snapshot.state) {
      case 'playing':
        if (this.playStartedAt === null) {
          this.playStartedAt = now
          this.positionAtPlayStart = snapshot.positionMs
        }
        break
      case 'paused':
      case 'stopped': {
        const started = this.playStartedAt
        // After real play time, a pause must report a position that moved. If it says 0, or
        // exactly where play started, the player never updates its position.
        if (started !== null && now - started >= this.config.positionCheckMinPlayMs) {
          const moved = Math.abs(snapshot.positionMs - this.positionAtPlayStart) >= this.config.positionCheckToleranceMs
          if (snapshot.positionMs === 0 || !moved) this._positionReliable = false
        }
        this.playStartedAt = null
        break
      }
      default:
        break
    }
  }

  /** Called when seeks repeatedly fail to fix drift. */
  markPositionUnreliable(): void {
    this._positionReliable = false
  }

  /** Called when the player never applied a speed nudge. */
  markRateUnreliable(): void {
    this._rateReliable = false
  }

  reset(): void {
    this.seenPlayPause = false
    this.seenSeek = false
    this.seenRate = false
    this._positionReliable = true
    this._rateReliable = true
    this.playStartedAt = null
    this.positionAtPlayStart = 0
  }
}
