// Desktop only: the RTDB wire format is the same as Android's (RoomParser.kt, FirebaseSyncTransport.kt).
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { countdownValue, nowPlayingUpdate, parsePreview, parseRoom, positionUpdate, ROOM_CODE, stateUpdate } from './roomParser.ts'

const TS = { '.sv': 'timestamp' }
const INC = { '.sv': { increment: 1 } }
const sentinels = { timestamp: TS, increment: INC }

describe('roomParser', () => {
  it('parses a full room', () => {
    const room = parseRoom('ABC234', {
      hostUid: 'h',
      createdAt: 10,
      title: { service: 'NETFLIX', deepLink: 'https://www.netflix.com/watch/1', setBy: 'h', setAt: 11 },
      state: { status: 'playing', positionMs: 5_000, updatedAt: 12, updatedBy: 'h', seq: 3 },
      countdown: { requestedAt: 13, leadMs: 3_000, startedBy: 'b' },
      members: {
        h: { name: 'Host', online: true, joinedAt: 1, lastSeen: 2, positionMs: 4_900, reportedAt: 12, voice: true },
        b: { name: 'B', online: false },
        broken: { online: true }
      }
    })
    assert.ok(room)
    assert.deepEqual(room.state, { status: 'playing', positionMs: 5_000, updatedAt: 12, updatedBy: 'h', seq: 3 })
    assert.deepEqual(room.title, { service: 'NETFLIX', deepLink: 'https://www.netflix.com/watch/1', setBy: 'h' })
    assert.deepEqual(room.countdown, { requestedAt: 13, leadMs: 3_000, startedBy: 'b' })
    assert.deepEqual(room.members, [
      { uid: 'h', name: 'Host', online: true, positionMs: 4_900, reportedAt: 12, lastSeen: 2, inVoice: true },
      { uid: 'b', name: 'B', online: false, positionMs: null, reportedAt: null, lastSeen: null, inVoice: false }
    ])
  })

  it('tolerates missing and invalid parts', () => {
    assert.equal(parseRoom('ABC234', null), null)
    assert.equal(parseRoom('ABC234', { createdAt: 1 }), null)
    const room = parseRoom('ABC234', { hostUid: 'h', state: { status: 'stopped' }, title: { service: 'HULU', deepLink: 'x' } })
    assert.ok(room)
    assert.equal(room.state, null)
    assert.equal(room.title, null)
    assert.deepEqual(room.members, [])
  })

  it('parses a preview; unknown service is null', () => {
    assert.deepEqual(parsePreview('ABC234', { hostName: 'Riya', service: 'X', title: 'T', durationMs: 5, updatedAt: 9 }), {
      code: 'ABC234', hostName: 'Riya', service: null, title: 'T', subtitle: null, durationMs: 5, playableRef: null, updatedAt: 9
    })
  })

  it('state write: the fields the rules expect, server-stamped and incremented', () => {
    assert.deepEqual(stateUpdate({ status: 'paused', positionMs: -3.4, reason: 'pause' }, 'me', sentinels), {
      status: 'paused', positionMs: 0, updatedAt: TS, updatedBy: 'me', seq: INC
    })
  })

  it('position report, countdown and now playing payloads', () => {
    assert.deepEqual(positionUpdate(1_234.6, sentinels), { positionMs: 1_235, reportedAt: TS })
    assert.deepEqual(countdownValue(60_000, 'me', sentinels), { requestedAt: TS, leadMs: 10_000, startedBy: 'me' })
    const np = nowPlayingUpdate({ service: 'PRIME_VIDEO', title: 'x'.repeat(300), subtitle: null, durationMs: 1.6 }, sentinels)
    assert.equal((np.title as string).length, 200)
    assert.deepEqual({ ...np, title: null }, {
      service: 'PRIME_VIDEO', title: null, subtitle: null, durationMs: 2, playableRef: null, artwork: null, updatedAt: TS
    })
  })

  it('room codes', () => {
    assert.ok(ROOM_CODE.test('ABC234'))
    assert.ok(!ROOM_CODE.test('ABCI23')) // no I
    assert.ok(!ROOM_CODE.test('../x'))
  })
})
