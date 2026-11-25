// What this PC shows friends as its activity (devices/{id}/activity): the browser tab the extension
// sees, or the song playing in Spotify (2026-11-25). Pure, unit-tested.
import type { NowPlaying } from '../../../shared/music.ts'
import type { DeviceActivity } from './devicePresence.ts'

/**
 * A watch party or a playing tab first; else Spotify while it plays (paused Spotify shows nothing,
 * like Discord); else a paused tab. A running game is separate (devices/{id}/game): friends see both,
 * "Playing VALORANT +1".
 */
export function pcActivity(watch: DeviceActivity | null, music: NowPlaying | null): DeviceActivity | null {
  if (watch && (watch.playing || watch.inWatchParty)) return watch
  const listening: DeviceActivity | null =
    music && music.playing
      ? { app: 'spotify', title: music.title, subtitle: music.artist, playing: true, durationMs: music.durationMs, inWatchParty: false, watchingWith: null }
      : null
  return listening ?? watch
}
