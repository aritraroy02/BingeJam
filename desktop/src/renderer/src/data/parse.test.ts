import { test } from 'node:test'
import assert from 'node:assert/strict'
import { aggregatePresence, parseDmMessage, parseGroup, parseHidden, parsePresence, parseProfile, parseRoomLink } from './parse.ts'

test('presence: online if the phone or ANY device is; last seen is the latest (PresenceStatus.of)', () => {
  assert.deepEqual(aggregatePresence(false, 100, [{ online: true, lastSeen: 50 }]), { online: true, lastSeen: 100 })
  assert.deepEqual(aggregatePresence(true, 100, []), { online: true, lastSeen: 100 })
  assert.deepEqual(aggregatePresence(undefined, undefined, [{ online: false, lastSeen: 300 }, { online: false, lastSeen: 200 }]), { online: false, lastSeen: 300 })
  assert.deepEqual(aggregatePresence(false, 'x', ['junk', null, { online: 'yes', lastSeen: 'soon' }]), { online: false, lastSeen: null })
  assert.deepEqual(aggregatePresence(null, null, []), { online: false, lastSeen: null })
})

test('parsePresence reads devices and keeps the activity from presence/activity only', () => {
  const p = parsePresence({
    online: false,
    lastSeen: 10,
    activity: { app: 'netflix', title: 'Leo', playing: true, startedAt: 5 },
    devices: { abcdefgh12: { kind: 'desktop', online: true, lastSeen: 20, label: 'Windows PC' } }
  })
  assert.equal(p?.online, true)
  assert.equal(p?.lastSeen, 20)
  assert.equal(p?.activity?.app, 'netflix')
  assert.equal(parsePresence({ online: true, activity: { app: 'tiktok', playing: true, startedAt: 1 } })?.activity, null)
})

test('profiles need a username and display name', () => {
  assert.equal(parseProfile('u', { username: 'riya' }), null)
  assert.equal(parseProfile('u', { username: 'riya', displayName: 'Riya', groupInvites: 'direct' })?.groupInvites, 'direct')
  assert.equal(parseProfile('u', { username: 'riya', displayName: 'Riya' })?.groupInvites, 'ask')
})

test('DM messages: text, deleted for everyone, watch cards, unknown kinds skipped', () => {
  assert.deepEqual(parseDmMessage('m1', { from: 'a', text: 'hi', sentAt: 5, forwarded: true }), {
    kind: 'text', id: 'm1', from: 'a', text: 'hi', sentAt: 5, forwarded: true, deleted: false
  })
  assert.equal(parseDmMessage('m2', { from: 'a', sentAt: 5, deleted: true })?.kind, 'text')
  const card = parseDmMessage('w-ABCDEF-0011aabb', { kind: 'watch', by: 'a', title: 'Leo', service: 'NETFLIX', durationMs: 600000, items: 1, sentAt: 9 })
  assert.equal(card?.kind, 'watched')
  assert.equal(parseDmMessage('x', { kind: 'poll', title: 'q' }), null)
  assert.equal(parseDmMessage('x', { text: 'no sender' }), null)
})

test('groups: members oldest first, admin role', () => {
  const g = parseGroup('-g1', { name: 'Movie Night', createdBy: 'a', createdAt: 1, members: { b: { role: 'member', joinedAt: 3 }, a: { role: 'admin', joinedAt: 1 } } })
  assert.deepEqual(g?.members.map((m) => [m.uid, m.admin]), [['a', true], ['b', false]])
  assert.equal(parseGroup('-g2', { name: '  ' }), null)
})

test('room links are live only while someone is in', () => {
  assert.equal(parseRoomLink({ code: 'ABCDEF', startedBy: 'a', startedAt: 1 }), null)
  assert.equal(parseRoomLink({ code: 'abc', in: { a: true } }), null)
  assert.deepEqual(parseRoomLink({ code: 'ABCDEF', startedBy: 'a', startedAt: 1, in: { b: true, a: true } })?.inRoom, ['a', 'b'])
})

test('dmHidden maps chats to hidden ids', () => {
  assert.deepEqual([...(parseHidden({ a_b: { m1: true, m2: true } }).get('a_b') ?? [])], ['m1', 'm2'])
})

test("a PC's activity shows when the phone has none; an offline PC's doesn't", () => {
  const pc = { kind: 'desktop', online: true, lastSeen: 5, activity: { app: 'netflix', title: 'Dune', playing: true, startedAt: 1 } }
  assert.equal(parsePresence({ devices: { abcdefgh12: pc } })?.activity?.title, 'Dune')
  assert.equal(parsePresence({ devices: { abcdefgh12: { ...pc, online: false } } })?.activity, null)
  const phone = { app: 'spotify', title: 'Song', playing: true, startedAt: 2 }
  assert.equal(parsePresence({ online: true, activity: phone, devices: { abcdefgh12: pc } })?.activity?.title, 'Song')
})
