import { auth } from '@/lib/firebase'
import type { ServiceId } from './catalog.ts'
import { isSafeWatchLink, parseTrending, type TitleType, type TrendingRow } from './trendingModel.ts'

// The push relay (push-relay/, Cloudflare Worker), exactly like Android's PushRelay.kt,
// RelayTrending.kt and RelayTitleLookup.kt: every call carries "Authorization: Bearer <ID token>".
//
// CORS: the relay was written for the phone and answers no preflight (OPTIONS → 404, no
// Access-Control-Allow-* headers; checked 2026-11-08), so a renderer fetch with an Authorization
// header is blocked by Chromium. Until either the relay answers CORS or the main process proxies
// the call, requests go through `window.bingejam.relayFetch` when the preload offers it, else
// plain fetch (which starts working as soon as the relay sends CORS headers).

export const RELAY_URL = 'https://vyro-push-relay.aritraroy02.workers.dev'

export interface RelayRequest {
  method: 'GET' | 'POST'
  url: string
  headers: Record<string, string>
  body?: string
}

export interface RelayResponse {
  status: number
  body: string
}

type RelayFetch = (request: RelayRequest) => Promise<RelayResponse>

function transport(): RelayFetch {
  const bridge = (globalThis as { bingejam?: { relayFetch?: RelayFetch } }).bingejam
  if (bridge?.relayFetch) return bridge.relayFetch
  return async (r) => {
    const res = await fetch(r.url, { method: r.method, headers: r.headers, body: r.body, signal: AbortSignal.timeout(15_000) })
    return { status: res.status, body: await res.text() }
  }
}

async function idToken(): Promise<string | null> {
  const user = auth.currentUser
  if (!user || user.isAnonymous) return null
  return user.getIdToken()
}

async function relayGet(path: string, params: Record<string, string>): Promise<unknown> {
  const token = await idToken()
  if (!token) return null
  const url = new URL(path, RELAY_URL)
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v)
  const res = await transport()({ method: 'GET', url: url.toString(), headers: { Authorization: `Bearer ${token}` } })
  if (res.status < 200 || res.status > 299) throw new Error(`relay answered ${res.status}`)
  return JSON.parse(res.body) as unknown
}

// ---------- Notifications (POST /) ----------

/** What the relay can notify about. It reads the item itself; the text never comes from us. */
export type RelayNote =
  | { type: 'dm'; to: string; messageId: string }
  | { type: 'dm_deleted'; to: string; messageId: string }
  | { type: 'friend_request'; to: string }
  | { type: 'join_request'; to: string }
  | { type: 'invite'; to: string }
  | { type: 'group_message'; group: string; messageId: string }
  | { type: 'group_invite'; group: string; to: string }
  | { type: 'room_ping'; group: string; to: string }

/** Best effort, like Android: a failure only means no notification on their phone. Never rejects. */
export async function notifyRelay(note: RelayNote): Promise<void> {
  try {
    const token = await idToken()
    if (!token) return
    const res = await transport()({
      method: 'POST',
      url: `${RELAY_URL}/`,
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(note)
    })
    if (res.status < 200 || res.status > 299) console.warn('Push relay answered', res.status)
  } catch (e) {
    console.warn('Push relay request failed', e)
  }
}

// ---------- Trending (GET /trending) and links (GET /watch-link) ----------

export interface TrendingFilter {
  type: TitleType
  /** Keys of push-relay/src/trending.js GENRES; omit = all. */
  genre?: string
  /** ISO 639-1 from its LANGUAGES; omit = all. */
  lang?: string
}

const TRENDING_TTL_MS = 60 * 60_000
const trendingCache = new Map<string, { at: number; rows: TrendingRow[] }>()
const linkCache = new Map<string, string>()

/** Netflix / JioHotstar / Prime Video rows; empty when unavailable. Cached an hour per filter. */
export async function fetchTrending(filter: TrendingFilter): Promise<TrendingRow[]> {
  const key = `${filter.type}|${filter.genre ?? ''}|${filter.lang ?? ''}`
  const hit = trendingCache.get(key)
  // A cache TTL is not a sync decision, so performance.now() (monotonic) is plenty.
  if (hit && performance.now() - hit.at < TRENDING_TTL_MS) return hit.rows
  try {
    const params: Record<string, string> = { type: filter.type }
    if (filter.genre) params.genre = filter.genre
    if (filter.lang) params.lang = filter.lang
    const rows = parseTrending(await relayGet('/trending', params))
    if (rows.length > 0) trendingCache.set(key, { at: performance.now(), rows })
    return rows
  } catch (e) {
    console.warn('Trending request failed', e)
    return hit?.rows ?? []
  }
}

/** The link that opens (Netflix, JioHotstar: starts) a title in its service; null when not found. */
export async function fetchWatchLink(tmdbId: number, type: TitleType, service: ServiceId): Promise<string | null> {
  const key = `${service}:${type}:${tmdbId}`
  const hit = linkCache.get(key)
  if (hit) return hit
  try {
    const body = (await relayGet('/watch-link', { tmdb: String(tmdbId), type, service })) as { url?: unknown } | null
    const url = body?.url
    if (!isSafeWatchLink(service, url)) return null
    linkCache.set(key, url) // misses aren't remembered (the relay caches them)
    return url
  } catch (e) {
    console.warn('Watch link request failed', e)
    return null
  }
}

/** GET /netflix: a movie's Netflix id from its title + length (Watchmode); digits only. */
export async function lookupNetflixId(title: string, durationMs: number | null): Promise<string | null> {
  if (!title.trim()) return null
  try {
    const params: Record<string, string> = { title }
    if (durationMs && durationMs > 0) params.durationMs = String(Math.round(durationMs))
    const id = ((await relayGet('/netflix', params)) as { netflixId?: unknown } | null)?.netflixId
    return typeof id === 'string' && /^\d{1,12}$/.test(id) ? id : null
  } catch (e) {
    console.warn('Netflix lookup failed', e)
    return null
  }
}

/** Profile pictures are public: an <img> can load this without a token (no CORS needed). */
export function avatarUrl(uid: string): string {
  return `${RELAY_URL}/avatar/${encodeURIComponent(uid)}`
}

// ---------- App reports notifications (POST /broadcast, owner and developers) ----------

/** Sends a notification to everyone (toUids null) or, for the owner, to picked people. Returns phones reached. */
export async function broadcastNotification(title: string, body: string, toUids: string[] | null): Promise<number | null> {
  const token = await idToken()
  if (!token) throw new Error('Sign in first')
  const payload: Record<string, unknown> = { title, body }
  if (toUids) payload.to = toUids
  const res = await transport()({
    method: 'POST',
    url: `${RELAY_URL}/broadcast`,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(payload)
  })
  if (res.status === 403) throw new Error("Your account isn't allowed to send notifications")
  if (res.status < 200 || res.status > 299) throw new Error(`The relay answered ${res.status}`)
  const sent = (JSON.parse(res.body) as { sent?: unknown }).sent
  return typeof sent === 'number' ? sent : null
}
