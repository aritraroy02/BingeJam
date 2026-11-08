import { useState, type CSSProperties, type JSX } from 'react'
import { Icon } from '@/ui/Icon'
import { Avatar, Button, LiveDot, Segmented } from '@/ui/primitives'
import { FriendList } from '@/ui/FriendList'
import type { FriendVM, HomeVM, TrendingVM } from '@/ui/models'

export type TrendingKind = 'movies' | 'series'

export interface HomeProps {
  data: HomeVM
  trendingKind: TrendingKind
  onTrendingKind: (kind: TrendingKind) => void
  onNewRoom: () => void
  onJoinLiveRoom: () => void
  onResume: () => void
  onInvite: () => void
  onOpenTitle: (item: TrendingVM) => void
  onFriendAction: (friend: FriendVM) => void
}

export function Home(p: HomeProps): JSX.Element {
  const [query, setQuery] = useState('')
  return (
    <div className="screen" style={{ flexDirection: 'column' }}>
      <header className="glass glass--bar rise" style={{ '--i': 0, display: 'flex', alignItems: 'center', gap: 12, padding: 10 } as CSSProperties}>
        <div className="field-wrap" style={{ flex: '1 1 300px' }}>
          <Icon name="search" size={18} />
          <label htmlFor="search" className="sr-only">
            Search titles, friends and rooms
          </label>
          <input
            id="search"
            type="search"
            className="field"
            placeholder="Search titles, friends and rooms"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
        {p.data.liveRoom ? (
          <Button variant="live" onClick={p.onJoinLiveRoom}>
            <LiveDot />
            {p.data.liveRoom.chatName} room is live · Join
          </Button>
        ) : null}
        <Button variant="primary" onClick={p.onNewRoom}>
          <Icon name="plus" size={18} strokeWidth={2.4} />
          New room
        </Button>
      </header>

      <div className="scroll" style={{ display: 'flex', flexDirection: 'column', gap: 20, flex: 1, margin: '-6px', padding: '6px' }}>
        {p.data.continueWatching ? <ContinueCard {...p} /> : null}

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 20 }}>
          <section
            aria-labelledby="h-trend"
            className="glass rise"
            style={{ '--i': 2, flex: '999 1 480px', minWidth: 0, padding: 24, display: 'flex', flexDirection: 'column', gap: 18 } as CSSProperties}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <h2 id="h-trend" className="h2" style={{ flex: 1 }}>
                Trending now
              </h2>
              <Segmented
                label="Type"
                value={p.trendingKind}
                onChange={p.onTrendingKind}
                options={[
                  { value: 'movies', label: 'Movies' },
                  { value: 'series', label: 'Web series' }
                ]}
              />
            </div>
            <TrendingGrid items={p.data.trending} onOpen={p.onOpenTitle} />
          </section>

          <aside
            aria-labelledby="h-friends"
            className="glass rise"
            style={{ '--i': 3, flex: '1 1 300px', minWidth: 0, padding: 22, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}
          >
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
              <h2 id="h-friends" className="h2" style={{ flex: 1 }}>
                Friends
              </h2>
              <span className="muted small">{p.data.onlineCount} online</span>
            </div>
            <FriendList friends={p.data.friends} onAction={p.onFriendAction} />
          </aside>
        </div>
      </div>
    </div>
  )
}

function ContinueCard(p: HomeProps): JSX.Element {
  const c = p.data.continueWatching!
  return (
    <section
      aria-labelledby="h-continue"
      className="glass glass--hero glass--raised rise"
      style={{ '--i': 1, overflow: 'hidden', padding: 34, display: 'flex', flexWrap: 'wrap', gap: 30, alignItems: 'center' } as CSSProperties}
    >
      <div style={{ flex: '1 1 340px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
        <span className="chip">Continue together</span>
        <h1 id="h-continue" className="h1">
          {c.title}
        </h1>
        <p className="muted" style={{ margin: 0, fontSize: 15 }}>
          {c.subtitle}
        </p>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, maxWidth: 420 }}>
          <div style={{ flex: 1, height: 6, borderRadius: 3, background: 'rgba(255,255,255,0.14)', overflow: 'hidden' }}>
            <div style={{ width: `${Math.round(c.progress * 100)}%`, height: '100%', borderRadius: 3, background: 'var(--progress-grad)' }} />
          </div>
          <span className="subtle">{c.leftLabel}</span>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10, marginTop: 8 }}>
          <Button variant="primary" size="lg" onClick={p.onResume}>
            <Icon name="play" size={16} />
            Resume together
          </Button>
          <Button size="lg" style={{ padding: '0 20px' }} onClick={p.onInvite}>
            Invite friends
          </Button>
        </div>
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, flex: '0 0 auto' }}>
        <div
          style={{
            width: 150,
            height: 214,
            borderRadius: 20,
            background: c.posterUrl ? `center/cover url("${c.posterUrl}")` : c.gradient,
            boxShadow: '0 24px 50px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.25)',
            border: '1px solid rgba(255,255,255,0.18)',
            display: 'flex',
            alignItems: 'flex-end',
            padding: 14,
            fontWeight: 800,
            fontSize: 16,
            lineHeight: 1.15
          }}
        >
          {c.posterUrl ? null : c.title}
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {c.people.map((person) => (
            <div
              key={person.id}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '6px 12px 6px 6px',
                borderRadius: 999,
                background: 'rgba(255,255,255,0.10)',
                border: '1px solid rgba(255,255,255,0.16)'
              }}
            >
              <Avatar name={person.name} id={person.id} photoUrl={person.photoUrl} size={26} />
              <span className="small">{person.name.split(' ')[0]}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}

function TrendingGrid({ items, onOpen }: { items: TrendingVM[] | null; onOpen: (t: TrendingVM) => void }): JSX.Element {
  if (items === null) {
    return (
      <div className="poster-grid" aria-busy="true">
        {Array.from({ length: 4 }, (_, i) => (
          <div key={i} className="poster poster--loading" />
        ))}
      </div>
    )
  }
  if (items.length === 0) return <p className="muted">Nothing trending right now.</p>
  return (
    <div className="poster-grid">
      {items.map((t) => (
        <button
          key={t.id}
          type="button"
          className="poster pressable"
          style={{ background: t.posterUrl ? `center/cover url("${t.posterUrl}")` : t.gradient }}
          onClick={() => onOpen(t)}
        >
          {t.rating ? <span className="poster__rating">{t.rating.toFixed(1)}</span> : null}
          <span className="poster__caption">
            <span className="truncate" style={{ fontWeight: 700, fontSize: 13 }}>
              {t.title}
            </span>
            <span style={{ fontSize: 11, color: 'rgba(255,255,255,0.8)' }}>{t.service}</span>
          </span>
        </button>
      ))}
    </div>
  )
}
