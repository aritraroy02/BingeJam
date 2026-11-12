import { useMemo, type CSSProperties, type JSX } from 'react'
import { PosterGrid } from '@/ui/Posters'
import type { TrendingVM } from '@/ui/models'

// Watchlist (2026-11-12): the titles you saved, on the phone or here; it's one list in your account.

export function Watchlist({
  items,
  onOpen,
  onToggleSave
}: {
  /** Newest first; null while loading. Each item's key is "movie_123" / "tv_456". */
  items: (TrendingVM & { key: string })[] | null
  onOpen: (t: TrendingVM) => void
  onToggleSave: (t: TrendingVM, saved: boolean) => void
}): JSX.Element {
  const saved = useMemo(() => new Set((items ?? []).map((i) => i.key)), [items])
  return (
    <div className="screen">
      <section
        aria-labelledby="h-watchlist"
        className="glass rise scroll"
        style={{ '--i': 1, flex: 1, minWidth: 0, padding: 24, display: 'flex', flexDirection: 'column', gap: 16 } as CSSProperties}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
          <h2 id="h-watchlist" className="h2" style={{ flex: 1 }}>
            Watchlist{items && items.length > 0 ? ` · ${items.length}` : ''}
          </h2>
          <span className="muted small">Shared with BingeJam on your phone</span>
        </div>
        <PosterGrid
          items={items}
          empty="Nothing saved yet. Hover a poster on Home and tap the bookmark. It waits here (and on your phone) for movie night."
          saved={saved}
          onOpen={onOpen}
          onToggleSave={onToggleSave}
        />
      </section>
    </div>
  )
}
