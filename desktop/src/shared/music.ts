// "Listening to Sitaare by Arijit Singh on Spotify" from the PC: Windows' media sessions (the same
// list the volume flyout shows; src/main/music.ts reads it) give the song, artist, length, position,
// album art and whether it plays. Pure and unit-tested; only Spotify's session ever gets this far.

export interface NowPlaying {
  app: 'spotify'
  title: string
  artist: string | null
  playing: boolean
  durationMs: number | null
  /** Where the song was when read, at [at] (Date.now(), display only: the progress bar). */
  positionMs: number | null
  at: number
  /** The album art as a data: URL, for your own profile card only (never sent to friends). */
  artwork: string | null
}

/** Media session ids we pass on: Spotify from spotify.com ("Spotify.exe") and from the Microsoft Store. */
export const MUSIC_APP = /spotify/i

/** What Spotify reports between songs or during ads: not a song. */
const NOT_A_SONG = /^(spotify( free| premium)?|advertisement|ad)$/i

/** A line from the helper; [art] = the raw base64 album art when the line carries it (once per song). */
export interface MusicLine {
  song: Omit<NowPlaying, 'at' | 'artwork'>
  art: string | null
}

/** One line from the helper: the session as JSON, or "none" / "error". null = nothing to show. */
export function parseMusicLine(line: string): MusicLine | null {
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
  const ms = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) && v >= 0 ? Math.round(v) : null)
  const duration = ms(o.durationMs)
  const art = typeof o.art === 'string' && /^[A-Za-z0-9+/]+=*$/.test(o.art) ? o.art : null
  return {
    song: {
      app: 'spotify',
      title: title.slice(0, 200),
      artist: artist ? artist.slice(0, 200) : null,
      playing: o.status === 'Playing',
      durationMs: duration && duration > 0 ? duration : null,
      positionMs: ms(o.positionMs)
    },
    art
  }
}

/** data: URL for base64 image bytes (Spotify gives PNG or JPEG). */
export function artDataUrl(base64: string): string {
  const type = base64.startsWith('/9j/') ? 'image/jpeg' : base64.startsWith('R0lGOD') ? 'image/gif' : 'image/png'
  return `data:${type};base64,${base64}`
}

/** Where the song is now: the reading plus the time since, while it plays (display only). */
export function positionNow(song: Pick<NowPlaying, 'positionMs' | 'at' | 'playing' | 'durationMs'>, now: number): number | null {
  if (song.positionMs === null) return null
  const at = song.playing ? song.positionMs + Math.max(0, now - song.at) : song.positionMs
  return song.durationMs ? Math.min(at, song.durationMs) : at
}

/** "0:04", "4:02", "1:02:03". */
export function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = String(total % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}
