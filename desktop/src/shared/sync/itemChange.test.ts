// Port of ItemChangeTest.kt: onItemChanged() restarts the timeline (next episode, another title or
// service, a new track), for the leader (host) and members.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { DEFAULT_SYNC_CONFIG as config } from './config.ts'
import { SyncEngine } from './engine.ts'
import { NO_OUTPUT } from './types.ts'
import { buffering, FakeClock, ME, PAUSE, paused, playing, roomState, seekTo, skippingToNext, syncedPlaying } from './testSupport.ts'

// After syncedPlaying(), local and room (seq 1, written by HOST) are both at 64_001.

/** Leader changes item at "now"; the player skips and starts the new one 300 ms later. */
function leaderSwitchesTrack(clock: FakeClock, engine: SyncEngine): void {
  assert.deepEqual(engine.onItemChanged(true), NO_OUTPUT)
  clock.advance(100)
  assert.deepEqual(engine.onLocalSnapshot(skippingToNext(clock, 64_101)), NO_OUTPUT)
  clock.advance(200)
  // Jump from 64 s to the new item's start: part of the switch, not a user seek.
  assert.deepEqual(engine.onLocalSnapshot(playing(clock, 0)), NO_OUTPUT)
}

describe('ItemChange: leader', () => {
  it('broadcasts the new item after settle min', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    leaderSwitchesTrack(clock, engine)
    clock.advance(config.itemSettleMinMs - 301) // 799 ms after the change
    assert.equal(engine.onTick().broadcast, null)
    clock.advance(1)
    const out = engine.onTick()
    // Live position: 0 sampled 500 ms ago while playing.
    assert.deepEqual(out.broadcast, { status: 'playing', positionMs: 500, reason: 'seek' })
    assert.equal(out.commands.length, 0)
  })

  it('a pause right after the change is broadcast, not undone', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    leaderSwitchesTrack(clock, engine)
    clock.advance(500)
    assert.equal(engine.onTick().broadcast?.positionMs, 500)
    engine.onRoom(roomState(clock, 'playing', 500, 2, ME), [])
    // 1.8 s after the change (inside what used to be the 4 s re-attach settle window).
    clock.advance(1_000)
    const pause = engine.onLocalSnapshot(paused(clock, 1_500))
    assert.deepEqual(pause.broadcast, { status: 'paused', positionMs: 1_500, reason: 'pause' })
    assert.equal(pause.commands.length, 0)
    engine.onRoom(roomState(clock, 'paused', 1_500, 3, ME), [])
    clock.advance(5_000)
    assert.equal(engine.onTick().commands.length, 0)
  })

  it('during the switch: no drift check, and others writes are not applied', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onItemChanged(true)
    clock.advance(100)
    engine.onLocalSnapshot(playing(clock, 0))
    // A drift check against the old item's room position (64 s) would seek the new item.
    assert.equal(engine.onTick().commands.length, 0)
    // A member's write about the old item arrives mid-switch.
    assert.equal(engine.onRoom(roomState(clock, 'paused', 64_101, 2, 'b'), []).commands.length, 0)
    // The leader's state for the new item is authoritative.
    clock.advance(700)
    assert.deepEqual(engine.onTick().broadcast, { status: 'playing', positionMs: 700, reason: 'seek' })
  })

  it('others writes wait until our own write lands', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    leaderSwitchesTrack(clock, engine)
    clock.advance(500)
    assert.equal(engine.onTick().broadcast?.positionMs, 500)
    // Ordered before ours by the server, but delivered before our own write comes back.
    assert.equal(engine.onRoom(roomState(clock, 'paused', 64_000, 2, 'b'), []).commands.length, 0)
    assert.equal(engine.onRoom(roomState(clock, 'playing', 500, 3, ME), []).commands.length, 0)
    // After ours landed, later writes apply as usual.
    clock.advance(1_000)
    assert.deepEqual(engine.onRoom(roomState(clock, 'paused', 1_500, 4, 'b'), []).commands, [PAUSE])
  })

  it('the own-write guard expires if our write never comes back', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    leaderSwitchesTrack(clock, engine)
    clock.advance(500)
    engine.onTick()
    clock.advance(config.itemOwnWriteWaitMs)
    assert.deepEqual(engine.onRoom(roomState(clock, 'paused', 3_500, 2, 'b'), []).commands, [PAUSE])
  })

  it('a new state published just before the metadata counts as fresh', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    // The player publishes the new item's state first: without an item change that's a scrub.
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 0)).broadcast, { status: 'playing', positionMs: 0, reason: 'seek' })
    // The new metadata arrives 300 ms later (within itemFreshToleranceMs).
    clock.advance(300)
    engine.onItemChanged(true)
    clock.advance(config.itemSettleMinMs)
    assert.deepEqual(engine.onTick().broadcast, { status: 'playing', positionMs: 1_100, reason: 'seek' })
  })

  it('a stale reading is taken after settle max', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    // The player's last reading was sampled 4 s before the change and it never re-publishes.
    engine.onItemChanged(true)
    clock.advance(config.itemSettleMinMs)
    assert.equal(engine.onTick().broadcast, null)
    clock.advance(config.itemSettleMaxMs - config.itemSettleMinMs)
    // playing@60_000 sampled 4_001 + 3_000 ms ago.
    assert.deepEqual(engine.onTick().broadcast, { status: 'playing', positionMs: 67_001, reason: 'seek' })
  })

  it('a player that never settles falls back to late-join alignment', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onItemChanged(true)
    clock.advance(100)
    engine.onLocalSnapshot(buffering(clock, 0))
    clock.advance(config.itemRestartTimeoutMs - 101)
    assert.equal(engine.onTick().commands.length, 0)
    clock.advance(1)
    // Same as the old re-attach path: line up with the room (64_001 + 8_000).
    assert.deepEqual(engine.onTick().commands, [seekTo(72_001)])
  })

  it('an empty room is seeded without waiting for attach settle', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(null, [])
    engine.onLocalSnapshot(playing(clock, 30_000))
    clock.advance(1_000)
    engine.onItemChanged(true)
    clock.advance(100)
    engine.onLocalSnapshot(playing(clock, 0))
    clock.advance(700)
    assert.deepEqual(engine.onTick().broadcast, { status: 'playing', positionMs: 700, reason: 'seek' })
  })
})

describe('ItemChange: member', () => {
  it('own jump not broadcast; aligns to the leader new state', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.deepEqual(engine.onItemChanged(false), NO_OUTPUT)
    clock.advance(100)
    assert.deepEqual(engine.onLocalSnapshot(skippingToNext(clock, 64_101)), NO_OUTPUT)
    clock.advance(300)
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 0)), NO_OUTPUT)
    // The leader's restart write: its new item is already at 5 s.
    clock.advance(600)
    const out = engine.onRoom(roomState(clock, 'playing', 5_000, 2), [])
    assert.deepEqual(out.commands, [seekTo(5_000)])
    assert.equal(out.broadcast, null)
    // Our seek lands through buffering: echo, not a user seek.
    clock.advance(200)
    assert.equal(engine.onLocalSnapshot(buffering(clock, 5_000)).broadcast, null)
    clock.advance(1_000)
    assert.equal(engine.onLocalSnapshot(playing(clock, 5_000)).broadcast, null)
  })

  it('a pause right after following is broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onItemChanged(false)
    clock.advance(100)
    engine.onLocalSnapshot(playing(clock, 0))
    clock.advance(400)
    assert.equal(engine.onRoom(roomState(clock, 'playing', 400, 2), []).commands.length, 0)
    clock.advance(1_000)
    const out = engine.onLocalSnapshot(paused(clock, 1_400))
    assert.deepEqual(out.broadcast, { status: 'paused', positionMs: 1_400, reason: 'pause' })
    assert.equal(out.commands.length, 0)
  })

  it('while waiting: no drift correction against the old item', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onItemChanged(false)
    clock.advance(100)
    engine.onLocalSnapshot(playing(clock, 0))
    assert.equal(engine.onTick().commands.length, 0)
    clock.advance(config.driftCheckIntervalMs)
    const out = engine.onTick()
    assert.equal(out.commands.length, 0)
    assert.equal(out.broadcast, null)
    assert.equal(engine.status, 'syncing')
  })

  it('while waiting: own play/pause changes are not broadcast', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onItemChanged(false)
    clock.advance(100)
    assert.equal(engine.onLocalSnapshot(paused(clock, 0)).broadcast, null)
    clock.advance(300)
    assert.equal(engine.onLocalSnapshot(playing(clock, 0)).broadcast, null)
  })

  it('hidden during the switch: aligns on reattach, without a settle window', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onLocalSnapshot(null) // the session hides the player while it switches
    engine.onItemChanged(false)
    clock.advance(1_000)
    // The leader's write arrives while we're detached: nothing to command yet.
    assert.equal(engine.onRoom(roomState(clock, 'playing', 0, 2), []).commands.length, 0)
    clock.advance(1_000)
    // Our player is on the leader's item now, 5 s in; the room expects 1 s.
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 5_000)).commands, [seekTo(1_000)])
    clock.advance(500)
    assert.equal(engine.onLocalSnapshot(playing(clock, 1_000)).broadcast, null) // our seek landing
    // A real pause 1.5 s after re-attaching is broadcast, not undone.
    clock.advance(1_000)
    const out = engine.onLocalSnapshot(paused(clock, 2_000))
    assert.deepEqual(out.broadcast, { status: 'paused', positionMs: 2_000, reason: 'pause' })
    assert.equal(out.commands.length, 0)
  })

  it('our own old write coming back is not the new item', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    assert.equal(engine.onLocalSnapshot(paused(clock, 64_001)).broadcast?.status, 'paused')
    engine.onItemChanged(false)
    clock.advance(100)
    engine.onLocalSnapshot(playing(clock, 0))
    // Our pause (about the old item) lands after the change: don't align to it.
    assert.equal(engine.onRoom(roomState(clock, 'paused', 64_001, 2, ME), []).commands.length, 0)
    clock.advance(400)
    assert.deepEqual(engine.onRoom(roomState(clock, 'paused', 0, 3), []).commands, [PAUSE])
  })

  it('a new state before the player settles waits for the player', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onItemChanged(false)
    clock.advance(100)
    engine.onLocalSnapshot(skippingToNext(clock, 64_101))
    clock.advance(100)
    assert.equal(engine.onRoom(roomState(clock, 'playing', 10_000, 2), []).commands.length, 0)
    clock.advance(500)
    // Room expects 10_000 + 500; the player started the item at 0.
    assert.deepEqual(engine.onLocalSnapshot(playing(clock, 0)).commands, [seekTo(10_500)])
  })

  it('no write for the new item falls back to late-join alignment', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock)
    engine.onItemChanged(false)
    clock.advance(100)
    engine.onLocalSnapshot(playing(clock, 0))
    clock.advance(config.itemRestartTimeoutMs - 101)
    assert.equal(engine.onTick().commands.length, 0)
    clock.advance(1)
    assert.deepEqual(engine.onTick().commands, [seekTo(72_001)])
  })
})

// ---- End of item: the player moves on by itself, the item change is known ~1 s later ----
// syncedPlaying(positionMs = 195_000): local and room at 199_001, near the end.
describe('ItemChange: end of item', () => {
  it('leader: a jump to zero before the item change is not a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, { positionMs: 195_000 })
    assert.equal(engine.onLocalSnapshot(buffering(clock, 0)).broadcast, null)
    clock.advance(700)
    engine.onItemChanged(true)
    clock.advance(200)
    assert.equal(engine.onLocalSnapshot(playing(clock, 0)).broadcast, null, 'the jump to 0 is the new item, not a scrub')
    clock.advance(config.itemSettleMinMs - 200)
    assert.deepEqual(engine.onTick().broadcast, { status: 'playing', positionMs: 600, reason: 'seek' })
  })

  it('member: a jump to zero before the item change is not a seek', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, { positionMs: 195_000 })
    assert.equal(engine.onLocalSnapshot(skippingToNext(clock, 0)).broadcast, null)
    clock.advance(900)
    engine.onItemChanged(false)
    clock.advance(100)
    assert.equal(engine.onLocalSnapshot(playing(clock, 0)).broadcast, null)
    // Even past the transient hold, nothing is broadcast for the old jump.
    clock.advance(config.transientHoldMs)
    assert.equal(engine.onTick().broadcast, null)
  })
})
