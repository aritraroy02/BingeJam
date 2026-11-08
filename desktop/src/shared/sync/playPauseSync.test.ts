// Port of PlayPauseSyncTest.kt: play/pause both ways, echo suppression, own-uid filtering, conflicts.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { SyncEngine } from './engine.ts'
import { FakeClock, ME, PAUSE, PLAY, paused, playing, roomState, seekTo, snapshot, syncedPaused, syncedPlaying } from './testSupport.ts'

describe('PlayPauseSync', () => {
  it('local pause is broadcast with the current position', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    // syncedPlaying leaves us at 60_000 + 4_001
    const out = engine.onLocalSnapshot(paused(clock, 64_001))
    assert.deepEqual(out.broadcast, { status: 'paused', positionMs: 64_001, reason: 'pause' })
    assert.equal(out.commands.length, 0)
  })

  it('local play is broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    const out = engine.onLocalSnapshot(playing(clock, 10_000))
    assert.deepEqual(out.broadcast, { status: 'playing', positionMs: 10_000, reason: 'play' })
  })

  it('remote play is applied', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    const out = engine.onRoom(roomState(clock, 'playing', 10_000, 2), [])
    assert.deepEqual(out.commands, [PLAY])
    assert.equal(out.broadcast, null)
  })

  it('remote pause is applied', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.deepEqual(engine.onRoom(roomState(clock, 'paused', 64_001, 2), []).commands, [PAUSE])
  })

  it('echo: the local reading after a remote play is not broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    engine.onRoom(roomState(clock, 'playing', 10_000, 2), [])
    clock.advance(500) // within the 1.5 s echo window
    assert.equal(engine.onLocalSnapshot(playing(clock, 10_000)).broadcast, null, 'our own applied play must not bounce back')
  })

  it('echo window expired, but a reading matching the room is still not broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    engine.onRoom(roomState(clock, 'playing', 10_000, 2), [])
    // Player takes 2 s to start; window expired, but it agrees with the room (10_000 + 2_000).
    clock.advance(2_000)
    assert.equal(engine.onLocalSnapshot(playing(clock, 12_000)).broadcast, null)
  })

  it('a genuine user pause after the echo window is broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    engine.onRoom(roomState(clock, 'playing', 10_000, 2), [])
    clock.advance(500)
    engine.onLocalSnapshot(playing(clock, 10_000))
    clock.advance(3_000)
    const out = engine.onLocalSnapshot(paused(clock, 13_000))
    assert.deepEqual(out.broadcast, { status: 'paused', positionMs: 13_000, reason: 'pause' })
  })

  it('events written by our own uid are ignored', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    assert.equal(engine.onRoom(roomState(clock, 'playing', 10_000, 2, ME), []).commands.length, 0)
  })

  it('conflict: a stale seq is ignored, a later one wins', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    assert.deepEqual(engine.onRoom(roomState(clock, 'playing', 10_000, 5), []).commands, [PLAY])
    clock.advance(500)
    engine.onLocalSnapshot(playing(clock, 10_000))
    // A write the server ordered before seq 5 arrives late: must not win.
    assert.equal(engine.onRoom(roomState(clock, 'paused', 10_000, 4), []).commands.length, 0)
    // A later write does win.
    assert.deepEqual(engine.onRoom(roomState(clock, 'paused', 10_500, 6), []).commands, [PAUSE])
  })

  it('conflict: the same seq re-delivered is not applied twice', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    engine.onRoom(roomState(clock, 'playing', 10_000, 2), [])
    clock.advance(100)
    assert.equal(engine.onRoom(roomState(clock, 'playing', 10_000, 2), []).commands.length, 0)
  })

  it('player start-up within the settle window is realigned, not broadcast', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(roomState(clock, 'paused', 60_000, 3, undefined, clock.server - 10_000), [])
    // The player opens in an unknown state, then autoplays from 0.
    engine.onLocalSnapshot(snapshot(clock, 'none', 0))
    clock.advance(800)
    const out = engine.onLocalSnapshot(playing(clock, 0))
    assert.equal(out.broadcast, null, 'autoplay is not a user action')
    assert.deepEqual(out.commands, [PAUSE, seekTo(60_000)])
  })

  it('an empty room is seeded once the player settles', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(null, [])
    engine.onLocalSnapshot(playing(clock, 30_000))
    assert.equal(engine.onTick().broadcast, null) // still settling
    clock.advance(4_001)
    assert.deepEqual(engine.onTick().broadcast, { status: 'playing', positionMs: 34_001, reason: 'play' })
  })
})
