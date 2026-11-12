import { useMemo } from 'react'
import { ref, remove, set } from 'firebase/database'
import { db } from '@/lib/firebase'
import type { TrendingVM } from '@/ui/models'
import { SERVICES, isServiceId, type ServiceId } from './catalog.ts'
import { requireUid, useLive, useUid } from './live.ts'
import { parseTrendingId, trendingId } from './trendingModel.ts'

// The watchlist in the account (2026-11-12), shared with the phone (FirebaseWatchlistStore):
//   watchlists/{uid}/{type_tmdbId} = {id, t, y?, p?, r?, k, s, a}

export interface SavedTitleVM extends TrendingVM {
  /** "movie_12345": same key as the phone. */
  key: string
  addedAt: number
}

const FALLBACK = 'linear-gradient(180deg, #2c2a26, #1a1917)'

function parse(key: string, v: unknown): SavedTitleVM | null {
  const o = v as Record<string, unknown> | null
  if (!o || typeof o.id !== 'number' || typeof o.t !== 'string' || typeof o.s !== 'string' || !isServiceId(o.s)) return null
  const type = o.k === 'tv' ? 'tv' : 'movie'
  const service = o.s as ServiceId
  return {
    key,
    id: trendingId(service, type, o.id),
    title: o.t,
    service: SERVICES[service].name,
    rating: typeof o.r === 'number' ? o.r : undefined,
    posterUrl: typeof o.p === 'string' ? o.p : null,
    gradient: FALLBACK,
    addedAt: typeof o.a === 'number' ? o.a : 0
  }
}

/** Our saved titles, newest first; null while loading. */
export function useWatchlist(): SavedTitleVM[] | null {
  const me = useUid()
  const snap = useLive(me ? `watchlists/${me}` : null)
  return useMemo(() => (snap ? snap.children.flatMap(([k, v]) => parse(k, v) ?? []).sort((a, b) => b.addedAt - a.addedAt) : null), [snap])
}

/** "movie_12345" for a poster, or null if its id isn't one of ours. */
export function watchlistKey(item: TrendingVM): string | null {
  const id = parseTrendingId(item.id)
  return id ? `${id.type}_${id.tmdbId}` : null
}

/** Saves a poster (or removes it when [saved]). The phone sees it at once. */
export async function toggleWatchlist(item: TrendingVM, saved: boolean): Promise<boolean> {
  const uid = requireUid()
  const id = parseTrendingId(item.id)
  if (!id) throw new Error("Can't save that title")
  const key = `${id.type}_${id.tmdbId}`
  if (saved) {
    await remove(ref(db, `watchlists/${uid}/${key}`))
    return false
  }
  const value: Record<string, unknown> = { id: id.tmdbId, t: item.title.slice(0, 200), k: id.type, s: id.service, a: Date.now() }
  if (item.posterUrl && item.posterUrl.startsWith('https://image.tmdb.org/') && item.posterUrl.length <= 500) value.p = item.posterUrl
  if (typeof item.rating === 'number' && item.rating >= 0 && item.rating <= 10) value.r = item.rating
  await set(ref(db, `watchlists/${uid}/${key}`), value)
  return true
}
