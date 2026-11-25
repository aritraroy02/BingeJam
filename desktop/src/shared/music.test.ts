import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseMusicLine, sameSong } from './music.ts'

const line = (o: Record<string, unknown>): string => JSON.stringify(o)

test('Spotify from the Store or spotify.com: song, artist, length, playing', () => {
  assert.deepEqual(
    parseMusicLine(line({ app: 'SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify', status: 'Playing', title: 'Sitaare (From "Ikkis")', artist: 'Arijit Singh', durationMs: 242916 })),
    { app: 'spotify', title: 'Sitaare (From "Ikkis")', artist: 'Arijit Singh', playing: true, durationMs: 242916 }
  )
  const paused = parseMusicLine(line({ app: 'Spotify.exe', status: 'Paused', title: 'Kesariya', artist: 'Arijit Singh', durationMs: 0 }))
  assert.equal(paused?.playing, false)
  assert.equal(paused?.durationMs, null)
})

test('other apps, ads, between songs and helper noise show nothing', () => {
  assert.equal(parseMusicLine(line({ app: 'Chrome', status: 'Playing', title: 'Some video', artist: '' })), null)
  assert.equal(parseMusicLine(line({ app: 'Spotify.exe', status: 'Playing', title: 'Advertisement', artist: '' })), null)
  assert.equal(parseMusicLine(line({ app: 'Spotify.exe', status: 'Paused', title: 'Spotify Premium', artist: null })), null)
  assert.equal(parseMusicLine(line({ app: 'Spotify.exe', status: 'Playing', title: '  ', artist: 'X' })), null)
  assert.equal(parseMusicLine('none'), null)
  assert.equal(parseMusicLine('error'), null)
  assert.equal(parseMusicLine('{broken'), null)
})

test('a song called "Spotify" by someone still counts', () => {
  assert.equal(parseMusicLine(line({ app: 'Spotify.exe', status: 'Playing', title: 'Spotify', artist: 'Band' }))?.title, 'Spotify')
})

test('sameSong compares what friends see', () => {
  const a = parseMusicLine(line({ app: 'Spotify.exe', status: 'Playing', title: 'A', artist: 'B', durationMs: 1000 }))
  assert.ok(sameSong(a, { ...a! }))
  assert.ok(!sameSong(a, { ...a!, playing: false }))
  assert.ok(sameSong(null, null))
})
