import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { ChatMessage, Group, Profile, RoomLink, TextMessage } from './parse.ts'
import {
  buildChatSummaries,
  buildGroupMembers,
  buildMessageRows,
  countFrom,
  dmPreview,
  groupPreview,
  groupSubtitle,
  latestVisible,
  roomSummary,
  watchedHeadline
} from './chatModel.ts'

const IST = { timeZone: 'Asia/Kolkata', hour12: true }
const NOW = Date.UTC(2026, 10, 8, 15, 32) // 9:02 pm IST
const DAY = 86_400_000
const text = (id: string, from: string, sentAt: number, t = 'hi'): TextMessage => ({ kind: 'text', id, from, text: t, sentAt, forwarded: false, deleted: false })
const profile = (uid: string, name: string): Profile => ({ uid, username: uid, displayName: name, photoUrl: null, bio: null, createdAt: 0, groupInvites: 'ask' })

test('previews', () => {
  assert.equal(dmPreview(text('1', 'me', 1, 'Count me in'), 'me'), 'You: Count me in')
  assert.equal(dmPreview({ ...text('1', 'f', 1), deleted: true, text: '' }, 'me'), 'This message was deleted')
  assert.equal(dmPreview({ kind: 'watched', id: 'w', by: 'f', title: 'Leo', service: 'NETFLIX', durationMs: 1, items: 1, sentAt: 1 }, 'me'), 'Watched Leo together')
  assert.equal(watchedHeadline({ title: 'Song', service: 'SPOTIFY', items: 4 }), 'Listened to Song + 3 more together')
  assert.equal(groupPreview(text('1', 'm', 1, "who's free Saturday?"), 'me', 'Meera Kapoor', 4), "Meera: who's free Saturday?")
  assert.equal(groupPreview(null, 'me', null, 5), '5 members')
  assert.equal(groupPreview(null, 'me', null, 1), 'Just you · add people')
})

test('latest skips deleted-for-me and anything before "delete chat"; unread counts only the friend', () => {
  const msgs: ChatMessage[] = [text('a', 'f', 10), text('b', 'f', 20), text('c', 'me', 30)]
  assert.equal(latestVisible(msgs, new Set(['c']), 0)?.id, 'b')
  assert.equal(latestVisible(msgs, new Set(), 30), null)
  assert.equal(countFrom(msgs, 'f'), 2)
})

test('chats list: DMs with messages or a live room, groups, newest first', () => {
  const room: RoomLink = { code: 'ABCDEF', startedBy: 'f2', startedAt: NOW - 60_000, inRoom: ['f2', 'x'] }
  const group: Group = { id: '-g', name: 'College Gang', createdBy: 'me', createdAt: NOW - 5 * DAY, members: [{ uid: 'me', admin: true, joinedAt: 1 }] }
  const vms = buildChatSummaries(
    [
      { chatId: 'f1_me', friend: { id: 'f1', name: 'Riya' }, latest: text('m', 'me', NOW - 2 * 3_600_000, 'Count me in'), unread: 0, room: null },
      { chatId: 'f2_me', friend: { id: 'f2', name: 'Kabir' }, latest: null, unread: 0, room },
      { chatId: 'f3_me', friend: { id: 'f3', name: 'Nobody' }, latest: null, unread: 0, room: null }
    ],
    [{ group, latest: null, latestSenderName: null, unread: 0, room: null, people: [] }],
    'me',
    { now: NOW, ...IST }
  )
  assert.deepEqual(vms.map((v) => v.name), ['Kabir', 'Riya', 'College Gang'])
  assert.deepEqual(vms[0].liveRoom, { inCount: 2 })
  assert.equal(vms[1].preview, 'You: Count me in')
  assert.equal(vms[1].timeLabel, '7:02 pm')
  assert.equal(vms[2].preview, 'Just you · add people')
})

test('message rows: day pills, watch cards, room event, group sender names', () => {
  const rows = buildMessageRows(
    [
      text('a', 'f', NOW - DAY, 'yesterday'),
      { kind: 'watched', id: 'w', by: 'f', title: 'Leo', service: 'NETFLIX', durationMs: 102 * 60_000, items: 1, sentAt: NOW - DAY + 1 },
      text('b', 'me', NOW - 60_000),
      text('c', 'f', NOW)
    ],
    {
      me: 'me',
      clock: { now: NOW, ...IST },
      group: true,
      names: new Map([['f', 'Riya']]),
      roomEvent: { code: 'ABCDEF', startedBy: 'f', startedAt: NOW - 30_000, name: 'Riya' },
      timeOf: () => '9:01 pm'
    }
  )
  assert.deepEqual(rows.map((r) => r.kind), ['day', 'text', 'watched', 'day', 'text', 'event', 'text'])
  assert.equal(rows[0].kind === 'day' && rows[0].label, 'Yesterday')
  assert.deepEqual(rows[2].kind === 'watched' && [rows[2].title, rows[2].durationLabel, rows[2].detail], ['Watched Leo together', '1 h 42 m', 'Netflix'])
  assert.equal(rows[5].kind === 'event' && rows[5].text, 'Riya started a room · 9:01 pm')
  assert.equal(rows[4].kind === 'text' && rows[4].senderName, undefined) // ours
  assert.equal(rows[6].kind === 'text' && rows[6].senderName, 'Riya')
})

test('header room summary and group subtitle', () => {
  assert.equal(
    roomSummary({ hostName: 'R', service: 'NETFLIX', title: 'Kaer Morhen', subtitle: 'S2:E4', durationMs: null }, 2),
    'Watching S2:E4 · Kaer Morhen · Netflix · talking in voice'
  )
  assert.equal(roomSummary({ hostName: 'R', service: 'SPOTIFY', title: 'Song', subtitle: 'Artist', durationMs: null }, 0), 'Listening to Song · Spotify')
  assert.equal(roomSummary(null, 0), 'Nothing playing yet')
  assert.equal(groupSubtitle(5, { code: 'ABCDEF', startedBy: 'a', startedAt: 1, inRoom: ['a', 'b', 'c'] }), '5 members · 3 in the room')
  assert.equal(groupSubtitle(1, null), 'Just you · add people')
})

test('group members: in the room first, ping state for the rest', () => {
  const room: RoomLink = { code: 'ABCDEF', startedBy: 'r', startedAt: NOW - 1, inRoom: ['k', 'r'] }
  const vms = buildGroupMembers(
    [
      { uid: 'z', profile: profile('z', 'Zoe'), presence: { online: true, lastSeen: NOW, activity: null }, joinedAt: 4, talking: false, pingedAgoMs: null },
      { uid: 'a', profile: profile('a', 'Ananya'), presence: { online: false, lastSeen: NOW - 2 * 3_600_000, activity: null }, joinedAt: 5, talking: false, pingedAgoMs: 60_000 },
      { uid: 'me', profile: profile('me', 'Alex'), presence: null, joinedAt: 3, talking: false, pingedAgoMs: null },
      { uid: 'k', profile: profile('k', 'Kabir'), presence: null, joinedAt: 2, talking: true, pingedAgoMs: null },
      { uid: 'r', profile: profile('r', 'Riya'), presence: null, joinedAt: 1, talking: false, pingedAgoMs: null }
    ],
    'me',
    room,
    NOW
  )
  assert.deepEqual(vms.map((v) => [v.id, v.detail]), [
    ['r', 'Started the room'],
    ['k', 'Talking'],
    ['me', 'Join any time'],
    ['z', 'Online'],
    ['a', 'Offline · 2 h']
  ])
  assert.equal(vms[2].ping, null)
  assert.equal(vms[3].ping, 'can')
  assert.deepEqual(vms[4].ping, { pingedLabel: 'Pinged · 1 min' })
  assert.equal(vms[0].ping, null)
})
