// Desktop only: the server-time anchor and PlayerSnapshot → LocalSnapshot conversion.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import type { PlayerSnapshot } from '../player.ts'
import { AnchoredServerClock, toLocalSnapshot } from './clock.ts'
import { POSITION_UNKNOWN } from './types.ts'

describe('AnchoredServerClock', () => {
  it('anchors wall + offset once, then advances with the monotonic clock only', () => {
    let mono = 500
    let wall = 1_700_000_000_000
    const clock = new AnchoredServerClock(() => mono, () => wall)
    assert.equal(clock.synced, false)
    clock.onOffset(250)
    assert.equal(clock.synced, true)
    assert.equal(clock.serverNowMs(), 1_700_000_000_250)
    mono += 1_000
    wall += 3_600_000 // the user changes the PC clock: ignored
    assert.equal(clock.serverNowMs(), 1_700_000_001_250)
  })

  it('a new offset or a reconnect re-anchors', () => {
    let mono = 0
    let wall = 1_000_000
    const clock = new AnchoredServerClock(() => mono, () => wall)
    clock.onOffset(100)
    mono += 10_000
    wall += 10_000
    clock.onOffset(-50)
    assert.equal(clock.serverNowMs(), 1_009_950)
    // Slept: monotonic stood still while the wall clock moved 60 s.
    wall += 60_000
    clock.reanchor()
    assert.equal(clock.serverNowMs(), 1_069_950)
  })

  it('before the first offset: wall clock time, advanced monotonically', () => {
    let mono = 0
    const clock = new AnchoredServerClock(() => mono, () => 42_000)
    mono += 5
    assert.equal(clock.serverNowMs(), 42_005)
  })
})

const base: PlayerSnapshot = {
  tabId: 7, service: 'NETFLIX', title: 'Midnight Signal', subtitle: null, durationMs: 6_000_000,
  positionMs: 61_234.6, playing: true, buffering: false, playbackRate: 1, sampledAt: 99_999, ageMs: 12
}

describe('toLocalSnapshot', () => {
  it('converts the sample time to the local clock: receivedAt − ageMs', () => {
    const s = toLocalSnapshot(base, 5_000)
    assert.deepEqual(
      { state: s.state, positionMs: s.positionMs, sampledAtMs: s.sampledAtMs, rate: s.rate },
      { state: 'playing', positionMs: 61_235, sampledAtMs: 4_988, rate: 1 }
    )
    assert.deepEqual(s.capabilities, { playPause: true, seek: true, rate: true })
  })

  it('buffering is transient; without a known position it is connecting', () => {
    assert.equal(toLocalSnapshot({ ...base, buffering: true }, 0).state, 'buffering')
    const loading = toLocalSnapshot({ ...base, buffering: true, positionMs: Number.NaN }, 0)
    assert.equal(loading.state, 'connecting')
    assert.equal(loading.positionMs, POSITION_UNKNOWN)
  })

  it('paused, bad rate and bad age are sanitised', () => {
    const s = toLocalSnapshot({ ...base, playing: false, playbackRate: Number.NaN, ageMs: -5 }, 1_000)
    assert.equal(s.state, 'paused')
    assert.equal(s.rate, 1)
    assert.equal(s.sampledAtMs, 1_000)
  })
})
