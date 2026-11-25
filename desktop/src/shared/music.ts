// "Listening to Sitaare by Arijit Singh on Spotify" from the PC: Windows' media sessions (the same
// list the volume flyout shows; src/main/music.ts reads it) give the song, artist, length and
// whether it plays. Pure and unit-tested; only Spotify's session ever gets this far.

export interface NowPlaying {
  app: 'spotify'
  title: string
  artist: string | null
  playing: boolean
  durationMs: number | null
}

/** Media session ids we pass on: Spotify from spotify.com ("Spotify.exe") and from the Microsoft Store. */
export const MUSIC_APP = /spotify/i

/** What Spotify reports between songs or during ads: not a song. */
const NOT_A_SONG = /^(spotify( free| premium)?|advertisement|ad)$/i

/** One line from the helper: the session as JSON, or "none" / "error". null = nothing to show. */
export function parseMusicLine(line: string): NowPlaying | null {
  const text = line.trim()
  if (!text.startsWith('{')) return null
  let o: Record<string, unknown>
  try {
    o = JSON.parse(text) as Record<string, unknown>
  } catch {
    return null
  }
  if (typeof o.app !== 'string' || !MUSIC_APP.test(o.app)) return null
  const title = typeof o.title === 'string' ? o.title.trim() : ''
  const artist = typeof o.artist === 'string' && o.artist.trim() ? o.artist.trim() : null
  if (!title || (!artist && NOT_A_SONG.test(title))) return null
  const duration = typeof o.durationMs === 'number' && Number.isFinite(o.durationMs) && o.durationMs > 0 ? Math.round(o.durationMs) : null
  return { app: 'spotify', title: title.slice(0, 200), artist: artist ? artist.slice(0, 200) : null, playing: o.status === 'Playing', durationMs: duration }
}

export function sameSong(a: NowPlaying | null, b: NowPlaying | null): boolean {
  return a?.title === b?.title && a?.artist === b?.artist && a?.playing === b?.playing && a?.durationMs === b?.durationMs
}
