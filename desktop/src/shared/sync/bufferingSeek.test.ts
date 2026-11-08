// Port of BufferingSeekTest.kt: scrubs and stalls that pass through buffering. Players usually
// report a scrub as playing@a -> buffering@b -> playing@b, so the jump is only visible on the
// buffering reading.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_SYNC_CONFIG } from './config.ts'
import { SyncEngine } from './engine.ts'
import { buffering, FakeClock, ME, paused, playing, roomState, seekTo, syncedPaused, syncedPlaying } from './testSupport.ts'

// After syncedPlaying(), local and room are both at 64_001.
describe('BufferingSeek', () => {
  it('a scrub through buffering while playing is broadcast once settled', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.equal(engine.onLocalSnapshot(buffering(clock, 124_001)).broadcast, null, 'not settled yet')
    clock.advance(500)
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 124_001)).broadcast, { status: 'playing', positionMs: 124_001, reason: 'seek' })
  })

  it('a scrub through buffering while paused is broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPaused(clock)
    engine.onLocalSnapshot(buffering(clock, 50_000))
    clock.advance(400)
    assert.deepEqual(engine.onLocalSnapshot(paused(clock, 50_000)).broadcast, { status: 'paused', positionMs: 50_000, reason: 'seek' })
  })

  it('a scrub through buffering is not pulled back by the drift check', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 124_001))
    // Still buffering at the next drift check: the old room position must not win.
    clock.advance(5_000)
    assert.equal(engine.onTick().commands.length, 0)
    const out = engine.onLocalSnapshot(playing(clock, 124_001))
    assert.equal(out.broadcast?.positionMs, 124_001)
    // Our write comes back; afterwards drift checks find nothing to fix.
    engine.onRoom(roomState(clock, 'playing', 124_001, 2, ME), [])
    clock.advance(5_000)
    engine.onLocalSnapshot(playing(clock, 129_001))
    assert.equal(engine.onTick().commands.length, 0)
  })

  it('a scrub through buffering that lands on the room position is not broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 124_001))
    clock.advance(300)
    // The user scrubbed away and straight back before the player settled.
    assert.equal(engine.onLocalSnapshot(playing(clock, 64_301)).broadcast, null)
  })

  it('a network stall is not a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 64_001))
    // Buffering doesn't advance the position, so 5 s later the player resumes where it stalled.
    clock.advance(5_000)
    assert.equal(engine.onTick().commands.length, 0, 'no drift correction while buffering')
    assert.equal(engine.onLocalSnapshot(playing(clock, 64_001)).broadcast, null)
  })

  it('a scrub then long buffering is broadcast when the hold expires', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 124_001))
    clock.advance(DEFAULT_SYNC_CONFIG.transientHoldMs - 1)
    assert.equal(engine.onTick().broadcast, null)
    clock.advance(1)
    // Keep the room's play state; the position is where the player is stuck buffering.
    assert.deepEqual(engine.onTick().broadcast, { status: 'playing', positionMs: 124_001, reason: 'seek' })
    // Settling at the same spot afterwards isn't broadcast again.
    clock.advance(500)
    assert.equal(engine.onLocalSnapshot(playing(clock, 124_001)).broadcast, null)
  })

  it('echo: our seek through buffering is not broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.deepEqual(engine.onRoom(roomState(clock, 'playing', 600_000, 2), []).commands, [seekTo(600_000)])
    clock.advance(200)
    assert.equal(engine.onLocalSnapshot(buffering(clock, 600_000)).broadcast, null)
    // Buffering outlasts the 1.5 s echo window; the expectation is held until the player settles.
    clock.advance(2_300)
    assert.equal(engine.onLocalSnapshot(playing(clock, 600_000)).broadcast, null)
  })

  it('echo: our seek, player buffers at the old position first, is not broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onRoom(roomState(clock, 'playing', 600_000, 2), [])
    clock.advance(200)
    engine.onLocalSnapshot(buffering(clock, 64_201))
    clock.advance(2_300)
    assert.equal(engine.onLocalSnapshot(playing(clock, 600_000)).broadcast, null)
  })

  it('echo: a held expectation expires after settling', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onRoom(roomState(clock, 'playing', 600_000, 2), [])
    clock.advance(200)
    engine.onLocalSnapshot(buffering(clock, 600_000))
    clock.advance(2_300)
    engine.onLocalSnapshot(playing(clock, 600_000))
    // 3 s after settling the user scrubs near the old target: that's a real seek now.
    clock.advance(3_000)
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 500_000)).broadcast, { status: 'playing', positionMs: 500_000, reason: 'seek' })
  })

  it('a remote write while buffering after a scrub: the room wins', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 124_001))
    clock.advance(300)
    assert.deepEqual(engine.onRoom(roomState(clock, 'playing', 300_000, 2), []).commands, [seekTo(300_000)])
    clock.advance(700)
    assert.equal(engine.onLocalSnapshot(playing(clock, 300_000)).broadcast, null)
  })

  it('a jump through buffering during attach settle is realigned, not broadcast', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(roomState(clock, 'playing', 60_000, 1), [])
    engine.onLocalSnapshot(playing(clock, 60_000))
    // The player jumps to its own resume point while starting up, via buffering...
    clock.advance(1_000)
    engine.onLocalSnapshot(buffering(clock, 200_000))
    // ...and only settles after the 4 s settle window.
    clock.advance(4_000)
    const out = engine.onLocalSnapshot(playing(clock, 200_000))
    assert.equal(out.broadcast, null)
    assert.deepEqual(out.commands, [seekTo(65_000)])
  })

  // ---- Stalls reported late (the player kept saying playing while frozen) ----
  // syncedPlaying()'s last raw reading is playing@60_000, sampled 4_001 ms ago, so it
  // extrapolates to 64_001. The video actually froze at ~60_500.

  it('frozen while reporting playing, then buffering at an older position, is a stall', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.equal(engine.onLocalSnapshot(buffering(clock, 60_500)).broadcast, null)
    clock.advance(1_000)
    assert.equal(engine.onLocalSnapshot(playing(clock, 60_500)).broadcast, null, 'a stall must not seek the whole room backwards')
    // The drift check corrects the stalled player forward instead (room: 64_001 + 1_000).
    assert.deepEqual(engine.onTick().commands, [seekTo(65_001)])
  })

  it('a stall reported slightly before the last raw position is a stall', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 58_000))
    clock.advance(500)
    assert.equal(engine.onLocalSnapshot(playing(clock, 58_000)).broadcast, null)
  })

  it('a scrub back before the last raw position, through buffering, is a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 20_000))
    clock.advance(500)
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 20_000)).broadcast, { status: 'playing', positionMs: 20_000, reason: 'seek' })
  })

  it('a scrub just past extrapolation + threshold, through buffering, is a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(buffering(clock, 67_002)) // 64_001 + 3_000 + 1
    clock.advance(300)
    assert.equal(engine.onLocalSnapshot(playing(clock, 67_002)).broadcast?.reason, 'seek')
  })

  it('a ten-second freeze is still a stall', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    clock.advance(10_000 - 4_001)
    engine.onLocalSnapshot(buffering(clock, 60_000))
    clock.advance(500)
    assert.equal(engine.onLocalSnapshot(playing(clock, 60_000)).broadcast, null)
  })

  it('an old raw report: a scrub back within its range is a seek', () => {
    const clock = new FakeClock()
    // Last raw report playing@10:00, 20 minutes ago: extrapolates to 30:00.
    const engine = syncedPlaying(clock, { positionMs: 600_000 })
    clock.advance(1_200_000 - 4_001)
    // Scrub back to 25:00: after the raw 10:00 but 5 min behind the extrapolation: not a stall.
    engine.onLocalSnapshot(buffering(clock, 1_500_000))
    clock.advance(500)
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 1_500_000)).broadcast, { status: 'playing', positionMs: 1_500_000, reason: 'seek' })
  })
})
