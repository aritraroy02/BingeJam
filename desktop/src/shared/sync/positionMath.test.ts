// Port of PositionMathTest.kt.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { localLivePositionMs, memberExpectedPositionMs, roomExpectedPositionMs } from './positionMath.ts'
import { POSITION_UNKNOWN, type Member } from './types.ts'
import { FakeClock, paused, playing, roomState } from './testSupport.ts'

const member = (positionMs: number, reportedAt: number): Member => ({
  uid: 'b', name: 'B', online: true, positionMs, reportedAt, lastSeen: null, inVoice: false
})

describe('PositionMath', () => {
  it('live: playing extrapolates from the last sample', () => {
    const clock = new FakeClock()
    assert.equal(localLivePositionMs(playing(clock, 10_000), clock.local + 3_000), 13_000)
  })
  it('live: playing scales by rate', () => {
    const clock = new FakeClock()
    assert.equal(localLivePositionMs(playing(clock, 10_000, undefined, 2), clock.local + 3_000), 16_000)
  })
  it('live: paused does not move', () => {
    const clock = new FakeClock()
    assert.equal(localLivePositionMs(paused(clock, 10_000), clock.local + 3_000), 10_000)
  })
  it('live: rate 0 while playing is treated as 1x', () => {
    const clock = new FakeClock()
    assert.equal(localLivePositionMs(playing(clock, 10_000, undefined, 0), clock.local + 2_000), 12_000)
  })
  it('live: unknown position stays unknown', () => {
    const clock = new FakeClock()
    assert.equal(localLivePositionMs(playing(clock, POSITION_UNKNOWN), clock.local + 2_000), POSITION_UNKNOWN)
  })
  it('live (desktop): no sample time means no extrapolation', () => {
    const clock = new FakeClock()
    assert.equal(localLivePositionMs({ ...playing(clock, 10_000), sampledAtMs: null }, clock.local + 2_000), 10_000)
  })
  it('room expected: playing adds server time since the update (network delay included)', () => {
    const clock = new FakeClock()
    const state = roomState(clock, 'playing', 60_000, 1, undefined, clock.server - 2_500)
    assert.equal(roomExpectedPositionMs(state, clock.server), 62_500)
  })
  it('room expected: paused is the stored position', () => {
    const clock = new FakeClock()
    const state = roomState(clock, 'paused', 60_000, 1, undefined, clock.server - 2_500)
    assert.equal(roomExpectedPositionMs(state, clock.server), 60_000)
  })
  it('room expected: server estimate behind the update never goes backwards', () => {
    const clock = new FakeClock()
    const state = roomState(clock, 'playing', 60_000, 1, undefined, clock.server + 200)
    assert.equal(roomExpectedPositionMs(state, clock.server), 60_000)
  })
  it('member: fresh report extrapolated while playing', () => {
    const clock = new FakeClock()
    assert.equal(memberExpectedPositionMs(member(30_000, clock.server - 4_000), 'playing', clock.server, 15_000), 34_000)
  })
  it('member: stale report ignored', () => {
    const clock = new FakeClock()
    assert.equal(memberExpectedPositionMs(member(30_000, clock.server - 20_000), 'playing', clock.server, 15_000), null)
  })
})
