// The relay's GET /trending answer → Home's posters (Android RelayTrending.parseTrending,
// TrendingCatalog.isSafeLink). Pure, unit-tested.
import type { TrendingVM } from '../ui/models.ts'
import { isServiceId, SERVICES, type ServiceId } from './catalog.ts'

/** TMDB "movie" / "tv" (web series): what the relay's ?type= takes. */
export type TitleType = 'movie' | 'tv'

export interface TrendingTitle {
  tmdbId: number
  type: TitleType
  title: string
  year: number | null
  posterUrl: string | null
  rating: number | null
}

export interface TrendingRow {
  service: ServiceId
  titles: TrendingTitle[]
}

const POSTER = /^https:\/\/image\.tmdb\.org\/t\/p\/w\d{2,4}\/[A-Za-z0-9._-]{1,80}$/

/** {rows: [{service, items: [{id, type, title, year, poster, rating}]}]}; odd entries are dropped. */
export function parseTrending(body: unknown): TrendingRow[] {
  const rows = (body as { rows?: unknown } | null)?.rows
  if (!Array.isArray(rows)) return []
  const out: TrendingRow[] = []
  for (const row of rows) {
    const r = row as { service?: unknown; items?: unknown } | null
    if (!r || !isServiceId(r.service) || !Array.isArray(r.items)) continue
    const titles: TrendingTitle[] = []
    for (const item of r.items) {
      const t = item as Record<string, unknown> | null
      if (!t) continue
      const id = typeof t.id === 'number' && t.id > 0 ? Math.floor(t.id) : 0
      const title = typeof t.title === 'string' ? t.title.trim().slice(0, 200) : ''
      if (!id || !title) continue
      titles.push({
        tmdbId: id,
        type: t.type === 'tv' ? 'tv' : 'movie',
        title,
        year: typeof t.year === 'number' && t.year >= 1880 && t.year <= 2200 ? t.year : null,
        posterUrl: typeof t.poster === 'string' && POSTER.test(t.poster) ? t.poster : null,
        rating: typeof t.rating === 'number' && t.rating >= 0 && t.rating <= 10 ? t.rating : null
      })
    }
    if (titles.length > 0) out.push({ service: r.service, titles })
  }
  return out
}

const GRADIENTS: Partial<Record<ServiceId, string>> = {
  NETFLIX: 'linear-gradient(170deg, #0F172A, #1E40AF 60%, #F43F5E 150%)',
  JIOHOTSTAR: 'linear-gradient(170deg, #052E16, #15803D 55%, #FACC15 150%)',
  PRIME_VIDEO: 'linear-gradient(170deg, #2E1065, #7E22CE 55%, #F472B6 150%)'
}
const FALLBACK_GRADIENT = 'linear-gradient(170deg, #1C1917, #92400E 55%, #FB923C 150%)'

/** TrendingVM.id = "NETFLIX:movie:12345", so a tap can ask the relay for the watch link. */
export function trendingId(service: ServiceId, type: TitleType, tmdbId: number): string {
  return `${service}:${type}:${tmdbId}`
}

export function parseTrendingId(id: string): { service: ServiceId; type: TitleType; tmdbId: number } | null {
  const [service, type, tmdb] = id.split(':')
  const tmdbId = Number(tmdb)
  if (!isServiceId(service) || (type !== 'movie' && type !== 'tv') || !Number.isInteger(tmdbId) || tmdbId <= 0) return null
  return { service, type, tmdbId }
}

/** One poster grid: the platforms' rows interleaved (#1 of each, then #2…), each title once. */
export function trendingVMs(rows: readonly TrendingRow[]): TrendingVM[] {
  const out: TrendingVM[] = []
  const seen = new Set<string>()
  const longest = Math.max(0, ...rows.map((r) => r.titles.length))
  for (let i = 0; i < longest; i++) {
    for (const row of rows) {
      const t = row.titles[i]
      if (!t || seen.has(`${t.type}:${t.tmdbId}`)) continue
      seen.add(`${t.type}:${t.tmdbId}`)
      out.push({
        id: trendingId(row.service, t.type, t.tmdbId),
        title: t.title,
        service: SERVICES[row.service].name,
        rating: t.rating ?? undefined,
        posterUrl: t.posterUrl,
        gradient: GRADIENTS[row.service] ?? FALLBACK_GRADIENT
      })
    }
  }
  return out
}

const NETFLIX = /^https:\/\/www\.netflix\.com\/watch\/\d{1,12}$/
const HOTSTAR = /^https:\/\/www\.hotstar\.com\/in\/movies\/x\/\d{6,12}\/watch$/
const PRIME = /^https:\/\/(?:www\.|app\.)?primevideo\.com\/(?:(?:[a-z-]+\/)?(?:detail|dp)\/[A-Za-z0-9._-]{1,80}\/?|detail\?gti=amzn1\.dv\.gti\.[0-9a-f-]{36})$/

/** Links we'll open, per service (the relay already filters; this is our own check). */
export function isSafeWatchLink(service: ServiceId, url: unknown): url is string {
  if (typeof url !== 'string' || url.length > 300) return false
  if (service === 'NETFLIX') return NETFLIX.test(url)
  if (service === 'JIOHOTSTAR') return HOTSTAR.test(url)
  if (service === 'PRIME_VIDEO') return PRIME.test(url)
  return false
}
