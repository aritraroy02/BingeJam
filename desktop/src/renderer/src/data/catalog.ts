// The apps and services the Android app knows (core/.../model/ActivityApp.kt, StreamingService.kt).
// Pure: no Firebase, so it runs under node --test.
import type { ServiceId } from '../../../shared/player.ts'

export type { ServiceId }

/** Watch party (movies/series) or jam (music, YouTube): decides "Watching" vs "Listening". */
export type AppKind = 'movie' | 'music' | 'video'

/** presence/{uid}/activity/app wire values (ActivityApp.wire). */
export type AppId = 'netflix' | 'jiohotstar' | 'primevideo' | 'spotify' | 'ytmusic' | 'amazonmusic' | 'youtube'

export const APPS: Record<AppId, { name: string; kind: AppKind; service: ServiceId }> = {
  netflix: { name: 'Netflix', kind: 'movie', service: 'NETFLIX' },
  jiohotstar: { name: 'JioHotstar', kind: 'movie', service: 'JIOHOTSTAR' },
  primevideo: { name: 'Prime Video', kind: 'movie', service: 'PRIME_VIDEO' },
  spotify: { name: 'Spotify', kind: 'music', service: 'SPOTIFY' },
  ytmusic: { name: 'YouTube Music', kind: 'music', service: 'YOUTUBE_MUSIC' },
  amazonmusic: { name: 'Amazon Music', kind: 'music', service: 'AMAZON_MUSIC' },
  youtube: { name: 'YouTube', kind: 'video', service: 'YOUTUBE' }
}

/** rooms/previews/watch cards use these ids (StreamingService.name). Jams = music + YouTube. */
export const SERVICES: Record<ServiceId, { name: string; kind: AppKind }> = {
  NETFLIX: { name: 'Netflix', kind: 'movie' },
  JIOHOTSTAR: { name: 'JioHotstar', kind: 'movie' },
  PRIME_VIDEO: { name: 'Prime Video', kind: 'movie' },
  SPOTIFY: { name: 'Spotify', kind: 'music' },
  YOUTUBE_MUSIC: { name: 'YouTube Music', kind: 'music' },
  AMAZON_MUSIC: { name: 'Amazon Music', kind: 'music' },
  YOUTUBE: { name: 'YouTube', kind: 'video' }
}

export function isAppId(value: unknown): value is AppId {
  return typeof value === 'string' && Object.hasOwn(APPS, value)
}

export function isServiceId(value: unknown): value is ServiceId {
  return typeof value === 'string' && Object.hasOwn(SERVICES, value)
}

/** Music and YouTube rooms are jams (StreamingService.isJam). */
export function isJam(kind: AppKind): boolean {
  return kind !== 'movie'
}
