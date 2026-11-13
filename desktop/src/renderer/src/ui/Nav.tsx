import { useEffect, useRef, useState, type CSSProperties, type JSX } from 'react'
import { BRAND } from '@shared/brand'
import { Icon, LogoMark, type IconName } from './Icon'
import { Avatar, Badge, LiveDot, Switch } from './primitives'

export type Tab = 'home' | 'chats' | 'watchlist' | 'friends' | 'streams' | 'settings'

const ITEMS: { tab: Tab; label: string; icon: IconName }[] = [
  { tab: 'home', label: 'Home', icon: 'home' },
  { tab: 'chats', label: 'Chats', icon: 'chat' },
  { tab: 'watchlist', label: 'Watchlist', icon: 'bookmark' },
  { tab: 'friends', label: 'Friends', icon: 'friends' },
  { tab: 'streams', label: 'Streams', icon: 'streams' },
  { tab: 'settings', label: 'Settings', icon: 'settings' }
]

export interface NavProps {
  current: Tab
  onNavigate: (tab: Tab) => void
  badges?: Partial<Record<Tab, number>>
  me?: {
    uid: string
    name: string
    username: string
    photoUrl?: string | null
    status?: 'online' | 'dnd' | 'invisible'
    activity?: string | null
    shareActivity?: boolean
  }
  extension?: { connected: boolean; detail: string }
  onStatus?: (status: 'online' | 'dnd' | 'invisible') => void
  onShareActivity?: (on: boolean) => void
}

const STATUS_LABEL = { online: 'Online', dnd: 'Do not disturb', invisible: 'Invisible' } as const

/** Wide sidebar with labels: the Home layout in the design. */
export function SideNav({ current, onNavigate, badges = {}, me, extension, onStatus, onShareActivity }: NavProps): JSX.Element {
  return (
    <nav aria-label="Main" className="nav nav--wide glass glass--strong rise" style={{ borderRadius: 20 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '8px 8px 24px' }}>
        <div className="logo-tile">
          <LogoMark />
        </div>
        <span className="wordmark">{BRAND.name}</span>
      </div>
      {ITEMS.map((it) => (
        <button
          key={it.tab}
          type="button"
          className="nav-item pressable"
          aria-current={current === it.tab ? 'page' : undefined}
          onClick={() => onNavigate(it.tab)}
        >
          <Icon name={it.icon} />
          <span className="label">{it.label}</span>
          <Badge count={badges[it.tab] ?? 0} />
        </button>
      ))}
      <div style={{ flex: 1, minHeight: 24 }} />
      {extension ? (
        <div
          style={{
            padding: 16,
            borderRadius: 16,
            background: extension.connected ? 'var(--raised)' : 'rgba(var(--ink), 0.06)',
            border: `1px solid ${extension.connected ? 'rgba(var(--ink), 0.08)' : 'rgba(var(--ink), 0.12)'}`,
            display: 'flex',
            flexDirection: 'column',
            gap: 4
          }}
        >
          <span style={{ display: 'flex', alignItems: 'center', gap: 8, fontWeight: 600, fontSize: 13 }}>
            {extension.connected ? <LiveDot /> : null}
            {extension.connected ? 'Extension connected' : 'Browser extension not added'}
          </span>
          <span className="subtle">{extension.detail}</span>
        </div>
      ) : null}
      {me ? <AccountCard me={me} onStatus={onStatus} onShareActivity={onShareActivity} /> : null}
    </nav>
  )
}

/** Your card at the bottom of the sidebar: status dot, what you're doing, and the status menu. */
function AccountCard({
  me,
  onStatus,
  onShareActivity
}: {
  me: NonNullable<NavProps['me']>
  onStatus?: NavProps['onStatus']
  onShareActivity?: NavProps['onShareActivity']
}): JSX.Element {
  const [open, setOpen] = useState(false)
  const box = useRef<HTMLDivElement>(null)
  const status = me.status ?? 'online'
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent): void => {
      if (!box.current?.contains(e.target as Node)) setOpen(false)
    }
    const esc = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('mousedown', close)
    window.addEventListener('keydown', esc)
    return () => {
      window.removeEventListener('mousedown', close)
      window.removeEventListener('keydown', esc)
    }
  }, [open])
  const line = me.activity ?? STATUS_LABEL[status]
  return (
    <div ref={box} style={{ position: 'relative', marginTop: 12 }}>
      {open && onStatus ? (
        <div className="status-menu" role="menu" aria-label="Your status">
          {(['online', 'dnd', 'invisible'] as const).map((s) => (
            <button
              key={s}
              type="button"
              role="menuitemradio"
              aria-checked={status === s}
              className="status-menu__item"
              onClick={() => {
                onStatus(s)
                setOpen(false)
              }}
            >
              <span className={`status-dot status-dot--${s}`} />
              <span style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
                <span style={{ fontWeight: 600 }}>{STATUS_LABEL[s]}</span>
                <span className="subtle small">
                  {s === 'online' ? 'Friends see you and what you do' : s === 'dnd' ? 'No pop-ups; friends see a red dot' : "You'll look offline to friends"}
                </span>
              </span>
            </button>
          ))}
          {onShareActivity ? (
            <div className="status-menu__share">
              <span style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
                <span style={{ fontWeight: 600 }}>Share my activity</span>
                <span className="subtle small">What you watch and play</span>
              </span>
              <Switch checked={me.shareActivity !== false} onChange={onShareActivity} label="Share my activity" />
            </div>
          ) : null}
        </div>
      ) : null}
      <button
        type="button"
        className="nav-account pressable"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`${me.name}, ${STATUS_LABEL[status]}. Change status`}
        onClick={() => setOpen((v) => !v)}
        disabled={!onStatus}
        style={{
          boxSizing: 'border-box',
          width: '100%',
          font: 'inherit',
          color: 'inherit',
          textAlign: 'left',
          cursor: onStatus ? 'pointer' : 'default',
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          padding: 12,
          borderRadius: 16,
          background: 'var(--raised)',
          border: '1px solid rgba(var(--ink), 0.08)',
          // The sidebar drags the window; the card must stay clickable.
          WebkitAppRegion: 'no-drag'
        } as CSSProperties}
      >
        <Avatar name={me.name} id={me.uid} photoUrl={me.photoUrl} size={38} online={status !== 'invisible'} dnd={status === 'dnd'} />
        <span style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
          <span className="truncate" style={{ fontWeight: 600, fontSize: 13 }}>
            {me.name}
          </span>
          <span className="subtle truncate" title={line}>
            {me.activity && me.shareActivity === false ? `${line} (hidden)` : line}
          </span>
        </span>
      </button>
    </div>
  )
}

/** Slim icon rail: chats, rooms, streams and settings screens in the design. */
export function RailNav({ current, onNavigate, badges = {} }: NavProps): JSX.Element {
  return (
    <nav aria-label="Main" className="nav nav--rail glass glass--strong rise">
      <button type="button" className="logo-tile pressable" aria-label={`${BRAND.name} home`} onClick={() => onNavigate('home')} style={{ border: 'none' }}>
        <LogoMark />
      </button>
      {ITEMS.map((it) => (
        <button
          key={it.tab}
          type="button"
          className="rail-item pressable"
          aria-label={it.label}
          title={it.label}
          aria-current={current === it.tab ? 'page' : undefined}
          onClick={() => onNavigate(it.tab)}
        >
          <Icon name={it.icon} />
          <Badge count={badges[it.tab] ?? 0} />
        </button>
      ))}
    </nav>
  )
}
