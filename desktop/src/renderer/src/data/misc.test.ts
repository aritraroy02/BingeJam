import { test } from 'node:test'
import assert from 'node:assert/strict'
import { agoShort } from './format.ts'
import { linkStartPayload, memberFields, newRoomPayload, pingState } from './roomModel.ts'
import { isSafeWatchLink, parseTrending, parseTrendingId, trendingVMs } from './trendingModel.ts'
import { isTypingFresh, nextTypingCheckMs, TypingSignal, TypingThrottle } from './typing.ts'

const TS = { '.sv': 'timestamp' }

test('room payloads match what Android writes', () => {
  assert.deepEqual(newRoomPayload('u1', '  Riya  ', TS), {
    hostUid: 'u1',
    createdAt: TS,
    members: { u1: { name: 'Riya', online: true, joinedAt: TS, lastSeen: TS } }
  })
  assert.equal(memberFields('', TS).name, 'Guest')
  assert.deepEqual(linkStartPayload('u1', 'ABCDEF', TS), { code: 'ABCDEF', startedBy: 'u1', startedAt: TS, in: { u1: true } })
  assert.equal(pingState(null, agoShort), 'can')
  assert.equal(pingState(130_000, agoShort), 'can')
  assert.deepEqual(pingState(10_000, agoShort), { pingedLabel: 'Pinged · now' })
})

test('trending: parse, drop odd entries, interleave platforms', () => {
  const rows = parseTrending({
    rows: [
      {
        service: 'NETFLIX',
        items: [
          { id: 1, type: 'movie', title: 'A', year: 2024, poster: 'https://image.tmdb.org/t/p/w342/a.jpg', rating: 8.1 },
          { id: 2, title: 'B', poster: 'http://evil/x.jpg' },
          { id: 0, title: 'bad' }
        ]
      },
      { service: 'JIOHOTSTAR', items: [{ id: 3, type: 'tv', title: 'C' }, { id: 1, type: 'movie', title: 'A again' }] },
      { service: 'HBO', items: [{ id: 9, title: 'nope' }] }
    ]
  })
  assert.equal(rows.length, 2)
  assert.equal(rows[0].titles[1].posterUrl, null)
  const vms = trendingVMs(rows)
  assert.deepEqual(vms.map((v) => v.id), ['NETFLIX:movie:1', 'JIOHOTSTAR:tv:3', 'NETFLIX:movie:2'])
  assert.equal(vms[1].service, 'JioHotstar')
  assert.deepEqual(parseTrendingId('JIOHOTSTAR:tv:3'), { service: 'JIOHOTSTAR', type: 'tv', tmdbId: 3 })
  assert.equal(parseTrendingId('X:tv:3'), null)
  assert.ok(isSafeWatchLink('NETFLIX', 'https://www.netflix.com/watch/81234567'))
  assert.ok(!isSafeWatchLink('NETFLIX', 'https://www.netflix.com/watch/1?x=y'))
  assert.ok(isSafeWatchLink('PRIME_VIDEO', 'https://app.primevideo.com/detail?gti=amzn1.dv.gti.0123abcd-0123-4567-89ab-0123456789ab'))
})

test('typing: freshness window, throttle, signal', () => {
  assert.ok(isTypingFresh(1000, 6999))
  assert.ok(!isTypingFresh(1000, 7000))
  assert.ok(!isTypingFresh(5000, 1000)) // too far in the future
  assert.equal(nextTypingCheckMs(1000, 6500), 501)
  assert.equal(nextTypingCheckMs(1000, 1000), 1000)
  let t = 0
  const throttle = new TypingThrottle(() => t)
  assert.ok(throttle.shouldWrite('c'))
  t = 2000
  assert.ok(!throttle.shouldWrite('c'))
  t = 3000
  assert.ok(throttle.shouldWrite('c'))
  assert.ok(throttle.clear('c'))
  assert.ok(!throttle.clear('c'))
  const s = new TypingSignal()
  assert.equal(s.onStopped(), null)
  assert.equal(s.onDraftChanged('h'), true)
  assert.equal(s.onDraftChanged('h'), null)
  assert.equal(s.onSent(), false)
  assert.equal(s.onSent(), null)
})
