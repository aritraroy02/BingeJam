import { test } from 'node:test'
import assert from 'node:assert/strict'
import { artDataUrl, clock, parseMusicLine, positionNow } from './music.ts'

const line = (o: Record<string, unknown>): string => JSON.stringify(o)

test('Spotify from the Store or spotify.com: song, artist, length, position, art', () => {
  assert.deepEqual(
    parseMusicLine(line({ app: 'SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify', status: 'Playing', title: 'Sitaare (From "Ikkis")', artist: 'Arijit Singh', durationMs: 242916, positionMs: 4000, art: 'iVBORw0KGgo=' })),
    { song: { app: 'spotify', title: 'Sitaare (From "Ikkis")', artist: 'Arijit Singh', playing: true, durationMs: 242916, positionMs: 4000 }, art: 'iVBORw0KGgo=' }
  )
  const paused = parseMusicLine(line({ app: 'Spotify.exe', status: 'Paused', title: 'Kesariya', artist: 'Arijit Singh', durationMs: 0 }))
  assert.equal(paused?.song.playing, false)
  assert.equal(paused?.song.durationMs, null)
  assert.equal(paused?.song.positionMs, null)
  assert.equal(paused?.art, null)
  assert.equal(parseMusicLine(line({ app: 'Spotify.exe', status: 'Playing', title: 'A', artist: 'B', art: '<script>' }))?.art, null)
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
  assert.equal(parseMusicLine(line({ app: 'Spotify.exe', status: 'Playing', title: 'Spotify', artist: 'Band' }))?.song.title, 'Spotify')
})

test('the progress bar runs while playing, stops while paused, never past the end', () => {
  const song = { positionMs: 4000, at: 1000, playing: true, durationMs: 242916 }
  assert.equal(positionNow(song, 3000), 6000)
  assert.equal(positionNow({ ...song, playing: false }, 3000), 4000)
  assert.equal(positionNow(song, 10_000_000), 242916)
  assert.equal(positionNow({ ...song, positionMs: null }, 3000), null)
  assert.equal(clock(4000), '0:04')
  assert.equal(clock(242916), '4:02')
  assert.equal(clock(3723000), '1:02:03')
})

test('album art becomes a data URL of the right type', () => {
  assert.equal(artDataUrl('iVBORw0KGgo='), 'data:image/png;base64,iVBORw0KGgo=')
  assert.equal(artDataUrl('/9j/4AAQ'), 'data:image/jpeg;base64,/9j/4AAQ')
})
