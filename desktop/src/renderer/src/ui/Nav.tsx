import type { JSX } from 'react'
import { BRAND } from '@shared/brand'
import { Icon, LogoMark, type IconName } from './Icon'
import { Avatar, Badge, LiveDot } from './primitives'

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
  me?: { uid: string; name: string; username: string; photoUrl?: string | null }
  extension?: { connected: boolean; detail: string }
}

/** Wide sidebar with labels: the Home layout in the design. */
export function SideNav({ current, onNavigate, badges = {}, me, extension }: NavProps): JSX.Element {
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
            background: extension.connected ? 'var(--raised)' : 'rgba(239,233,222,0.06)',
            border: `1px solid ${extension.connected ? 'rgba(239,233,222,0.08)' : 'rgba(239,233,222,0.12)'}`,
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
      {me ? (
        <div
          className="nav-account"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            marginTop: 12,
            padding: 12,
            borderRadius: 16,
            background: 'var(--raised)',
            border: '1px solid rgba(239,233,222,0.08)'
          }}
        >
          <Avatar name={me.name} id={me.uid} photoUrl={me.photoUrl} size={38} online />
          <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
            <span className="truncate" style={{ fontWeight: 600, fontSize: 13 }}>
              {me.name}
            </span>
            <span className="subtle truncate">@{me.username}</span>
          </div>
        </div>
      ) : null}
    </nav>
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
