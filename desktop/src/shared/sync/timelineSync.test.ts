// Port of TimelineSyncTest.kt: seek detection, drift correction + rate limit, late join, reports.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_SYNC_CONFIG } from './config.ts'
import { SyncEngine } from './engine.ts'
import type { LocalSnapshot } from './types.ts'
import { buffering, FakeClock, ME, PLAY, paused, playing, roomState, seekTo, START_LOCAL, syncedPlaying } from './testSupport.ts'

// After syncedPlaying(), local and room are both at 64_001.
describe('TimelineSync', () => {
  it('a scrub of more than 3 s is broadcast as a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 124_001)).broadcast, { status: 'playing', positionMs: 124_001, reason: 'seek' })
  })

  it('a backward scrub is broadcast as a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    const out = engine.onLocalSnapshot(playing(clock, 20_000))
    assert.equal(out.broadcast?.reason, 'seek')
    assert.equal(out.broadcast?.positionMs, 20_000)
  })

  it('jitter under 3 s is not a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.equal(engine.onLocalSnapshot(playing(clock, 66_001)).broadcast, null)
  })

  it('a remote seek is applied', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.deepEqual(engine.onRoom(roomState(clock, 'playing', 600_000, 2), []).commands, [seekTo(600_000)])
  })

  it('echo: our applied seek is not rebroadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onRoom(roomState(clock, 'playing', 600_000, 2), [])
    clock.advance(700)
    assert.equal(engine.onLocalSnapshot(playing(clock, 600_100)).broadcast, null)
  })

  it('drift at or below the threshold is not corrected', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(playing(clock, 65_001)) // 1_000 ms ahead
    assert.equal(engine.onTick().commands.length, 0)
    assert.equal(engine.lastDriftMs, 1_000)
    assert.equal(engine.status, 'syncing')
  })

  it('drift above the threshold seeks to the room expected position', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(playing(clock, 66_001)) // 2_000 ms ahead, not a seek
    assert.deepEqual(engine.onTick().commands, [seekTo(64_001)])
    assert.equal(engine.status, 'correcting')
  })

  it('drift behind is corrected too', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(playing(clock, 62_001))
    assert.deepEqual(engine.onTick().commands, [seekTo(64_001)])
  })

  it('drift is only checked every 5 s', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onTick() // first check, in sync
    clock.advance(1_000)
    engine.onLocalSnapshot(playing(clock, 67_001)) // 2_000 ahead of 65_001
    assert.equal(engine.onTick().commands.length, 0)
    clock.advance(4_000)
    assert.deepEqual(engine.onTick().commands, [seekTo(69_001)])
  })

  it('corrections are rate limited', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(playing(clock, 66_001))
    assert.equal(engine.onTick().commands.length, 1) // correction #1 at t
    // Player ignored the seek; still 2 s ahead at t + 5 s: cooldown, no seek.
    clock.advance(5_000)
    assert.equal(engine.onTick().commands.length, 0)
    // t + 10 s: cooldown over, correct again.
    clock.advance(5_000)
    assert.deepEqual(engine.onTick().commands, [seekTo(74_001)])
  })

  it('seeks that never take effect turn timeline sync off', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(playing(clock, 66_001))
    for (let i = 0; i < 3; i++) {
      assert.equal(engine.onTick().commands.length, 1)
      clock.advance(10_000)
    }
    // 4th time still off by 2 s: give up seeking.
    assert.equal(engine.onTick().commands.length, 0)
    assert.equal(engine.mode, 'playPauseOnly')
  })

  it('position is reported periodically', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.equal(engine.onTick().positionReport, 64_001)
    clock.advance(1_000)
    assert.equal(engine.onTick().positionReport, null)
    clock.advance(4_000)
    assert.equal(engine.onTick().positionReport, 69_001)
  })

  it('late join, playing room: seeks to the expected position, then plays', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    // Host pressed play at 60 s, 10 s ago (server time).
    engine.onRoom(roomState(clock, 'playing', 60_000, 3, undefined, clock.server - 10_000), [])
    const out = engine.onLocalSnapshot(paused(clock, 0))
    assert.deepEqual(out.commands, [seekTo(70_000), PLAY])
    assert.equal(out.broadcast, null)
  })

  it('late join, paused room: seeks to the paused position', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(roomState(clock, 'paused', 60_000, 3, undefined, clock.server - 10_000), [])
    assert.deepEqual(engine.onLocalSnapshot(paused(clock, 0)).commands, [seekTo(60_000)])
  })

  /**
   * Netflix on a Motorola (2026-10-29), room paused: each pause + seek landed on the keyframe 3 s
   * before the target and Netflix started playing by itself, so every reading in the attach-settle
   * window triggered another realign (~25 in 4 s). Now: a couple, then one more after the window.
   */
  it('late join with a player that lands early and autoplays does not loop', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    const room = 5_226_911
    engine.onRoom(roomState(clock, 'paused', room, 9), [])
    let commandBatches = 0
    let broadcasts = 0
    const feed = (s: LocalSnapshot): void => {
      const out = engine.onLocalSnapshot(s)
      if (out.commands.length > 0) commandBatches++
      if (out.broadcast !== null) broadcasts++
    }
    feed(paused(clock, 5_224_634))
    while (clock.local < START_LOCAL + DEFAULT_SYNC_CONFIG.attachSettleMs - 200) {
      clock.advance(60); feed(buffering(clock, room))
      clock.advance(60); feed(playing(clock, room - 3_060))
      clock.advance(30); feed(paused(clock, room - 3_060))
    }
    assert.ok(commandBatches <= 3, `realigned ${commandBatches} times inside the window`)
    // Window over: exactly one more realign on the next tick.
    clock.advance(400)
    const after = engine.onTick()
    assert.ok(after.commands.length > 0)
    assert.equal(broadcasts, 0)
    assert.equal(after.broadcast, null)
  })

  it('late join: room arriving after the player still aligns', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onLocalSnapshot(paused(clock, 0))
    const out = engine.onRoom(roomState(clock, 'playing', 60_000, 3, undefined, clock.server - 10_000), [])
    assert.deepEqual(out.commands, [seekTo(70_000), PLAY])
  })

  it('reopening the player realigns to the room', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(null) // tab closed
    assert.equal(engine.status, 'sessionLost')
    clock.advance(20_000)
    // Reopened at its own resume point, paused. Room is at 64_001 + 20_000.
    assert.deepEqual(engine.onLocalSnapshot(paused(clock, 30_000)).commands, [seekTo(84_001), PLAY])
  })
})
