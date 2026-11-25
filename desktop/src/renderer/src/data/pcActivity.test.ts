import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pcActivity } from './pcActivity.ts'
import type { DeviceActivity } from './devicePresence.ts'
import type { NowPlaying } from '../../../shared/music.ts'

const tab = (playing: boolean, inWatchParty = false): DeviceActivity => ({ app: 'netflix', title: 'Dune', subtitle: null, playing, durationMs: null, inWatchParty, watchingWith: null })
const song = (playing: boolean): NowPlaying => ({ app: 'spotify', title: 'Sitaare', artist: 'Arijit Singh', playing, durationMs: 242916 })

test('Spotify playing shows as the PC activity', () => {
  assert.deepEqual(pcActivity(null, song(true), false), { app: 'spotify', title: 'Sitaare', subtitle: 'Arijit Singh', playing: true, durationMs: 242916, inWatchParty: false, watchingWith: null })
})

test('paused Spotify shows nothing; a paused tab still shows', () => {
  assert.equal(pcActivity(null, song(false), false), null)
  assert.equal(pcActivity(tab(false), song(false), false)?.app, 'netflix')
})

test('a playing tab or a watch party beats Spotify; Spotify beats a paused tab', () => {
  assert.equal(pcActivity(tab(true), song(true), false)?.app, 'netflix')
  assert.equal(pcActivity(tab(false, true), song(true), false)?.app, 'netflix')
  assert.equal(pcActivity(tab(false), song(true), false)?.app, 'spotify')
})

test('while a game runs the song is not published', () => {
  assert.equal(pcActivity(null, song(true), true), null)
  assert.equal(pcActivity(tab(true), song(true), true)?.app, 'netflix')
})
