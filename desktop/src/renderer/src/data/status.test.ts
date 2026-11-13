import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { Presence, Profile } from './parse.ts'
import { buildFriendVMs, friendAction, friendStatus, headerStatus } from './status.ts'

const NOW = 1_000_000_000
const profile = (uid: string, name: string): Profile => ({ uid, username: uid, displayName: name, photoUrl: null, bio: null, createdAt: 0, groupInvites: 'ask' })
const watching: Presence = {
  online: true,
  lastSeen: NOW,
  activity: { app: 'netflix', title: 'Kaer Morhen', subtitle: 'S2:E4', playing: true, durationMs: null, startedAt: 1, inWatchParty: false, watchingWith: null }
}
const music: Presence = {
  online: true,
  lastSeen: NOW,
  activity: { app: 'spotify', title: 'Neon Streets', subtitle: 'Artist', playing: false, durationMs: null, startedAt: 1, inWatchParty: false, watchingWith: null }
}

test('status lines follow the phone: activity while online, Online, Last seen', () => {
  assert.equal(friendStatus(watching, NOW), 'S2:E4 · Kaer Morhen · Netflix')
  assert.equal(headerStatus(watching, NOW), 'Watching S2:E4 · Kaer Morhen · Netflix')
  assert.equal(friendStatus(music, NOW), 'Neon Streets · Spotify (paused)')
  assert.equal(headerStatus(music, NOW), 'Listening to Neon Streets · Spotify (paused)')
  assert.equal(friendStatus({ online: true, lastSeen: 1, activity: null }, NOW), 'Online')
  // Offline: a stale activity isn't shown.
  assert.equal(friendStatus({ ...watching, online: false, lastSeen: NOW - 2 * 3_600_000 }, NOW), 'Last seen 2 h ago')
  assert.equal(friendStatus(null, NOW), 'Offline')
})

test('actions: join a watch party, jam on music, nothing while idle or offline', () => {
  assert.equal(friendAction(watching), 'join')
  assert.equal(friendAction(music), 'jam')
  assert.equal(friendAction({ online: true, lastSeen: 1, activity: null }), null)
  assert.equal(friendAction({ ...watching, online: false }), null)
})

test('friends sort: playing, online, then offline by last seen, then name', () => {
  const vms = buildFriendVMs(
    [
      { uid: 'z', profile: profile('z', 'Zoe'), presence: { online: false, lastSeen: NOW - 10, activity: null } },
      { uid: 'k', profile: profile('k', 'Kabir'), presence: { online: false, lastSeen: NOW - 5, activity: null } },
      { uid: 'm', profile: profile('m', 'Meera'), presence: { online: true, lastSeen: NOW, activity: null } },
      { uid: 'r', profile: profile('r', 'Riya'), presence: watching },
      { uid: 'a', profile: profile('a', 'Ananya'), presence: null }
    ],
    NOW
  )
  assert.deepEqual(vms.map((v) => v.id), ['r', 'm', 'k', 'z', 'a'])
  assert.equal(vms[0].action, 'join')
  assert.equal(vms[0].online, true)
})

test('a game shows as "Playing X"; Do not disturb when nothing else', () => {
  const base = { online: true, lastSeen: 1, activity: null }
  assert.equal(friendStatus({ ...base, game: { name: 'VALORANT', startedAt: 0 } }, 10), 'Playing VALORANT')
  assert.equal(friendStatus({ ...base, dnd: true }, 10), 'Do not disturb')
  assert.equal(headerStatus({ ...base, game: { name: 'VALORANT', startedAt: 0 } }, 10), 'Playing VALORANT')
})
