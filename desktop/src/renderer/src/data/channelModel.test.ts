import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  callElapsed,
  channelList,
  channelUnread,
  cleanChannelName,
  nextOrder,
  parseChannels,
  parseVoiceOccupants,
  parseVoiceStatuses
} from './channelModel.ts'

test('every group starts with #general and the General voice channel', () => {
  const { text, voice } = channelList([])
  assert.deepEqual(text.map((c) => c.id), ['general'])
  assert.deepEqual(voice.map((c) => c.id), ['lounge'])
  assert.ok(text[0].builtIn && voice[0].builtIn)
})

test('stored channels come after the built-in ones, by order then age', () => {
  const stored = parseChannels({
    b: { name: 'off-topic', kind: 'text', order: 1, createdAt: 5 },
    a: { name: 'bot-music', kind: 'text', order: 0, createdAt: 9 },
    g: { name: 'Gaming', kind: 'voice', order: 0, createdAt: 1 },
    bad: { name: 'x', kind: 'video', order: 0 },
    general: { name: 'sneaky', kind: 'text', order: 0 },
    'no spaces': { name: 'x', kind: 'text', order: 0 }
  })
  const { text, voice } = channelList(stored)
  assert.deepEqual(text.map((c) => c.name), ['general', 'bot-music', 'off-topic'])
  assert.deepEqual(voice.map((c) => c.name), ['General', 'Gaming'])
})

test('a new channel goes last among its kind', () => {
  const stored = parseChannels({ a: { name: 'a', kind: 'text', order: 3 }, v: { name: 'v', kind: 'voice', order: 7 } })
  assert.equal(nextOrder(stored, 'text'), 4)
  assert.equal(nextOrder(stored, 'voice'), 8)
  assert.equal(nextOrder([], 'voice'), 0)
})

test('text channel names read like Discord, voice names stay as typed', () => {
  assert.equal(cleanChannelName('  Movie Picks!! ', 'text'), 'movie-picks')
  assert.equal(cleanChannelName('git push/pull', 'text'), 'git-pushpull')
  assert.equal(cleanChannelName('--', 'text'), '')
  assert.equal(cleanChannelName('  Gaming   Night ', 'voice'), 'Gaming Night')
  assert.equal(cleanChannelName('x'.repeat(40), 'voice').length, 32)
})

test('people in voice channels, first to join first', () => {
  const m = parseVoiceOccupants({
    lounge: { u2: { name: 'Kabir', since: 200, muted: true }, u1: { name: 'Riya', since: 100, live: true } },
    g: { u3: { name: 'Meera' } }, // no since: skipped
    empty: {}
  })
  assert.deepEqual([...m.keys()], ['lounge'])
  assert.deepEqual(m.get('lounge')?.map((p) => [p.name, p.muted, p.live]), [
    ['Riya', false, true],
    ['Kabir', true, false]
  ])
})

test('channel statuses', () => {
  const s = parseVoiceStatuses({ g: { text: 'Valorant ranked', by: 'u', at: 1 }, h: { text: '  ' }, i: 5 })
  assert.deepEqual([...s.entries()], [['g', 'Valorant ranked']])
})

test('the call timer counts from the first person still in the channel', () => {
  const people = [
    { uid: 'a', name: 'A', since: 1_000, muted: false, deafened: false, live: false },
    { uid: 'b', name: 'B', since: 50_000, muted: false, deafened: false, live: false }
  ]
  assert.equal(callElapsed(people, 1_000 + 905_000), '15:05')
  assert.equal(callElapsed(people, 1_000 + 4_505_000), '1:15:05')
  assert.equal(callElapsed([], 5), null)
  assert.equal(callElapsed(people, 0), '0:00') // server clock a little behind
})

test('unread only for messages from others after our last read', () => {
  assert.equal(channelUnread({ from: 'them', sentAt: 10 }, 5, 'me'), true)
  assert.equal(channelUnread({ from: 'me', sentAt: 10 }, 5, 'me'), false)
  assert.equal(channelUnread({ from: 'them', sentAt: 10 }, 10, 'me'), false)
  assert.equal(channelUnread(null, 0, 'me'), false)
})
