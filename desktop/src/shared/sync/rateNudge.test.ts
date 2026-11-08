// Desktop only: drift at or below the seek threshold closed with 0.98x / 1.02x speed nudges.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { syncConfig } from './config.ts'
import { FakeClock, PAUSE, paused, playing, RATE_ACTIONS, rate, roomState, seekTo, syncedPlaying } from './testSupport.ts'

// After syncedPlaying(), local and room are both at 64_001 (playing). Drift = local − room.
const opts = { actions: RATE_ACTIONS }

describe('RateNudge', () => {
  it('ahead by 1 s: plays at 0.98x, nothing broadcast, status stays syncing', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    const out = engine.onTick()
    assert.deepEqual(out.commands, [rate(0.98)])
    assert.equal(out.broadcast, null)
    assert.equal(engine.status, 'syncing')
    // 1 s at 2 % = 50 s.
    assert.equal(engine.rateNudge?.endsAt, clock.local + 50_000)
  })

  it('behind by 1 s: plays at 1.02x', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 63_001, RATE_ACTIONS))
    assert.deepEqual(engine.onTick().commands, [rate(1.02)])
  })

  it('tiny drift (noise) is left alone', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 64_201, RATE_ACTIONS)) // 200 ms ahead
    assert.equal(engine.onTick().commands.length, 0)
  })

  it('a nudge ends after |drift| / 2 %, back to 1x', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 64_501, RATE_ACTIONS)) // 500 ms ahead → 25 s
    assert.deepEqual(engine.onTick().commands, [rate(0.98)])
    clock.advance(100)
    // The player applied it: readings now advance at 0.98 (sampled position = 64_501 + 98).
    engine.onLocalSnapshot(playing(clock, 64_599, RATE_ACTIONS, 0.98))
    let ended = -1
    for (let t = 1; t <= 30; t++) {
      clock.advance(1_000)
      const out = engine.onTick()
      if (out.commands.some((c) => c.type === 'rate' && c.playbackRate === 1)) {
        ended = t
        break
      }
      assert.ok(!out.commands.some((c) => c.type === 'seek'), 'never seeks for small drift')
    }
    // Planned for 25 s; the check at 20 s finds 98 ms left (≤ rateNudgeDoneMs) and stops there.
    assert.ok(ended >= 20 && ended <= 26, `ended after ${ended} s`)
    assert.ok(Math.abs(engine.lastDriftMs ?? 999) <= 100)
    assert.equal(engine.rateNudge, null)
  })

  it('an overshoot (drift changed sign) ends the nudge at the next check', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    engine.onTick() // 0.98x
    clock.advance(5_000)
    // Something else moved us 400 ms behind the room.
    engine.onLocalSnapshot(playing(clock, 69_001 - 400, RATE_ACTIONS, 0.98))
    assert.deepEqual(engine.onTick().commands, [rate(1)])
  })

  it('drift above the threshold still seeks, resetting a running nudge first', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    engine.onTick() // 0.98x
    clock.advance(5_000)
    engine.onLocalSnapshot(playing(clock, 69_001 + 2_000, RATE_ACTIONS, 0.98)) // 2 s ahead now
    assert.deepEqual(engine.onTick().commands, [rate(1), seekTo(69_001)])
  })

  it('a remote command during a nudge resets the speed first', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    engine.onTick()
    assert.deepEqual(engine.onRoom(roomState(clock, 'paused', 64_001, 2), []).commands, [rate(1), PAUSE])
  })

  it('a user pause during a nudge is broadcast and resets the speed', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    engine.onTick()
    clock.advance(1_000)
    const out = engine.onLocalSnapshot(paused(clock, 66_000, RATE_ACTIONS))
    assert.equal(out.broadcast?.reason, 'pause')
    assert.deepEqual(out.commands, [rate(1)])
  })

  it('a player that ignores playbackRate: nudges off after the apply timeout', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    assert.deepEqual(engine.onTick().commands, [rate(0.98)])
    clock.advance(1_000)
    engine.onLocalSnapshot(playing(clock, 66_001, RATE_ACTIONS, 1)) // still 1x
    clock.advance(2_000)
    assert.deepEqual(engine.onTick().commands, [rate(1)]) // gave up, undo just in case
    clock.advance(5_000)
    engine.onLocalSnapshot(playing(clock, 72_001, RATE_ACTIONS)) // still 1 s ahead
    assert.equal(engine.onTick().commands.length, 0)
  })

  it('the viewer choosing another speed cancels the nudge without overriding it', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    engine.onTick()
    clock.advance(500)
    engine.onLocalSnapshot(playing(clock, 65_500, RATE_ACTIONS, 1.25))
    assert.equal(engine.rateNudge, null)
    clock.advance(5_000)
    const out = engine.onTick()
    assert.ok(!out.commands.some((c) => c.type === 'rate'))
  })

  it('without rate support (Android-like player) small drift is left alone', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock) // ALL_ACTIONS: no rate
    engine.onLocalSnapshot(playing(clock, 65_001))
    assert.equal(engine.onTick().commands.length, 0)
  })

  it('nudges can be switched off in the config', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, { ...opts, config: syncConfig({ rateNudges: false }) })
    engine.onLocalSnapshot(playing(clock, 65_001, RATE_ACTIONS))
    assert.equal(engine.onTick().commands.length, 0)
  })

  it('no nudge while the room is paused', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, opts)
    engine.onRoom(roomState(clock, 'paused', 64_001, 2), [])
    clock.advance(500)
    engine.onLocalSnapshot(paused(clock, 65_001, RATE_ACTIONS)) // echo of our pause, 1 s off
    clock.advance(5_000)
    assert.ok(!engine.onTick().commands.some((c) => c.type === 'rate'))
  })
})
