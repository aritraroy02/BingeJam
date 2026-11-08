// Port of FallbackModesTest.kt: fallbacks chosen at runtime from capabilities and position behaviour.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { SyncEngine } from './engine.ts'
import { POSITION_UNKNOWN, type Member } from './types.ts'
import {
  ALL_ACTIONS, buffering, FakeClock, ME, NO_ACTIONS, NO_SEEK_ACTIONS, PAUSE, PLAY, paused, playing, roomState, snapshot, syncedPlaying
} from './testSupport.ts'

describe('FallbackModes', () => {
  it('no player is manual countdown', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onLocalSnapshot(null)
    assert.equal(engine.mode, 'manualCountdown')
    assert.equal(engine.status, 'appNotOpen')
  })

  it('no play/pause is manual countdown, and no commands', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(roomState(clock, 'playing', 60_000, 1), [])
    const out = engine.onLocalSnapshot(paused(clock, 0, NO_ACTIONS))
    assert.equal(engine.mode, 'manualCountdown')
    assert.equal(out.commands.length, 0)
  })

  it('no seek is catch-up mode, and late join only matches play/pause', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(roomState(clock, 'playing', 60_000, 1, undefined, clock.server - 10_000), [])
    const out = engine.onLocalSnapshot(paused(clock, 0, NO_SEEK_ACTIONS))
    assert.equal(engine.mode, 'playPauseCatchUp')
    assert.deepEqual(out.commands, [PLAY])
  })

  it('catch-up: ahead of the room pauses, then resumes', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, { actions: NO_SEEK_ACTIONS })
    // 2.9 s ahead: past the drift threshold, below the seek threshold.
    engine.onLocalSnapshot(playing(clock, 66_901, NO_SEEK_ACTIONS))
    assert.deepEqual(engine.onTick().commands, [PAUSE])
    assert.equal(engine.status, 'catchingUp')
    // The resulting paused reading is our own doing, so it's not broadcast.
    clock.advance(500)
    assert.equal(engine.onLocalSnapshot(paused(clock, 67_401, NO_SEEK_ACTIONS)).broadcast, null)
    // After being paused as long as we were ahead, play again.
    clock.advance(2_400)
    assert.deepEqual(engine.onTick().commands, [PLAY])
  })

  it('catch-up: a slower member makes us wait', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, { actions: NO_SEEK_ACTIONS })
    // We're on the room's position, but member B reported 4 s behind just now.
    const slow: Member = { uid: 'b', name: 'B', online: true, positionMs: 60_001, reportedAt: clock.server, lastSeen: null, inVoice: false }
    engine.onRoom(roomState(clock, 'playing', 60_000, 1, undefined, clock.server - 4_001), [slow])
    assert.deepEqual(engine.onTick().commands, [PAUSE])
  })

  it('catch-up: behind does nothing', () => {
    const clock = new FakeClock()
    const engine = syncedPlaying(clock, { actions: NO_SEEK_ACTIONS })
    engine.onLocalSnapshot(playing(clock, 62_001, NO_SEEK_ACTIONS))
    assert.equal(engine.onTick().commands.length, 0)
  })

  it('position unknown is play/pause only', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onLocalSnapshot(playing(clock, POSITION_UNKNOWN))
    assert.equal(engine.mode, 'playPauseOnly')
  })

  /**
   * Netflix opened from a watch link (Motorola, 2026-10-29): CONNECTING with no position, then
   * PAUSED at the viewer's resume point without SEEK_TO, BUFFERING, then PLAYING. The loading
   * reading must not turn timeline sync off: the member is pulled to the room's position.
   */
  it('position unknown while connecting keeps timeline sync, and late join seeks', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    const room = 5_162_000
    engine.onRoom(roomState(clock, 'playing', room, 7), [])
    const loading = { playPause: true, seek: false, rate: false } // STOP + PLAY
    engine.onLocalSnapshot(snapshot(clock, 'connecting', POSITION_UNKNOWN, loading))
    clock.advance(5)
    engine.onLocalSnapshot(paused(clock, 5_134_000, loading))
    clock.advance(66)
    engine.onLocalSnapshot(buffering(clock, 5_134_000, NO_ACTIONS)) // STOP only
    clock.advance(934)
    const out = engine.onLocalSnapshot(playing(clock, 5_133_398, ALL_ACTIONS))
    assert.equal(engine.mode, 'full')
    const seeks = out.commands.filter((c) => c.type === 'seek')
    assert.equal(seeks.length, 1, `expected a seek to the room, got ${JSON.stringify(out.commands)}`)
    const seek = seeks[0]
    assert.ok(seek.type === 'seek' && Math.abs(seek.positionMs - (room + 1_005)) < 50)
  })

  it('position unknown while playing is still play/pause only', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onLocalSnapshot(snapshot(clock, 'connecting', POSITION_UNKNOWN))
    engine.onLocalSnapshot(playing(clock, POSITION_UNKNOWN))
    assert.equal(engine.mode, 'playPauseOnly')
  })

  it('a position that never updates switches to play/pause only, and still syncs pause', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(roomState(clock, 'playing', 0, 1), [])
    engine.onLocalSnapshot(playing(clock, 0))
    assert.equal(engine.mode, 'full')
    // Played 6 s, user pauses, and the player still says position 0.
    clock.advance(6_000)
    const out = engine.onLocalSnapshot(paused(clock, 0))
    assert.equal(engine.mode, 'playPauseOnly')
    // Pause still goes to the room, keeping the room's own timeline position.
    assert.equal(out.broadcast?.status, 'paused')
    assert.equal(out.broadcast?.positionMs, 6_000)
  })

  it('play/pause only: no drift correction or reports', () => {
    const clock = new FakeClock()
    const engine = new SyncEngine(ME, clock)
    engine.onRoom(roomState(clock, 'playing', 0, 1), [])
    engine.onLocalSnapshot(playing(clock, POSITION_UNKNOWN))
    clock.advance(10_000)
    const out = engine.onTick()
    assert.equal(out.commands.length, 0)
    assert.equal(out.positionReport, null)
  })
})
