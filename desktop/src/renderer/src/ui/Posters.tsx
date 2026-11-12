import type { JSX } from 'react'
import { Icon } from './Icon'
import type { TrendingVM } from './models'

/**
 * Poster grid for trending titles and the watchlist. Each poster opens its title; the bookmark in
 * the corner saves it to (or removes it from) the watchlist shared with the phone (2026-11-12).
 */
export function PosterGrid({
  items,
  empty,
  saved,
  onOpen,
  onToggleSave
}: {
  items: TrendingVM[] | null
  empty: string
  /** Keys of saved titles ("movie_123"); with onToggleSave, each poster gets a bookmark. */
  saved?: ReadonlySet<string>
  onOpen: (t: TrendingVM) => void
  onToggleSave?: (t: TrendingVM, saved: boolean) => void
}): JSX.Element {
  if (items === null) {
    return (
      <div className="poster-grid" aria-busy="true">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="poster poster--loading" />
        ))}
      </div>
    )
  }
  if (items.length === 0) return <p className="muted">{empty}</p>
  return (
    <div className="poster-grid">
      {items.map((t) => {
        const key = posterKey(t)
        const isSaved = key !== null && saved?.has(key) === true
        return (
          <div key={t.id} className="poster-wrap">
            <button
              type="button"
              className="poster pressable"
              style={{ background: t.posterUrl ? `center/cover url("${t.posterUrl}")` : t.gradient, width: '100%' }}
              onClick={() => onOpen(t)}
            >
              {t.rating ? <span className="poster__rating">{t.rating.toFixed(1)}</span> : null}
              <span className="poster__caption">
                <span className="truncate" style={{ fontWeight: 500, fontSize: 13 }}>
                  {t.title}
                </span>
                <span style={{ fontSize: 11, color: 'rgba(239,233,222,0.8)' }}>{t.service}</span>
              </span>
            </button>
            {onToggleSave && key ? (
              <button
                type="button"
                className={isSaved ? 'poster__save poster__save--on' : 'poster__save'}
                aria-pressed={isSaved}
                aria-label={isSaved ? `Remove ${t.title} from your watchlist` : `Save ${t.title} to your watchlist`}
                title={isSaved ? 'On your watchlist' : 'Save to watchlist'}
                onClick={() => onToggleSave(t, isSaved)}
              >
                <Icon name={isSaved ? 'bookmarkFilled' : 'bookmark'} size={16} />
              </button>
            ) : null}
          </div>
        )
      })}
    </div>
  )
}

/** "SERVICE:type:id" → "type_id" (same key as the phone's watchlist). */
function posterKey(t: TrendingVM): string | null {
  const [, type, id] = t.id.split(':')
  return (type === 'movie' || type === 'tv') && id ? `${type}_${id}` : null
}
