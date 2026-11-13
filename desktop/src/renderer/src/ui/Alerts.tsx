import type { JSX } from 'react'
import { Avatar, Button } from './primitives'

// Room invites and "asks to join" requests, over every screen (top right), like the phone's banners.

export interface InviteAlertVM {
  fromUid: string
  name: string
  photoUrl: string | null
  /** "Watch Dune together" / "Join their room" */
  detail: string
}

export interface JoinAlertVM {
  fromUid: string
  name: string
  photoUrl: string | null
  detail: string
}

export interface AlertsProps {
  invites: InviteAlertVM[]
  joins: JoinAlertVM[]
  onJoin: (fromUid: string) => void
  onDeclineInvite: (fromUid: string) => void
  onLetIn: (fromUid: string) => void
  onDeclineJoin: (fromUid: string) => void
}

export function Alerts(p: AlertsProps): JSX.Element | null {
  if (p.invites.length === 0 && p.joins.length === 0) return null
  return (
    <div className="alerts" role="region" aria-label="Invites and requests">
      {p.invites.map((v) => (
        <div key={`i:${v.fromUid}`} className="alert-card glass glass--strong" role="alert">
          <Avatar name={v.name} id={v.fromUid} photoUrl={v.photoUrl} size={40} />
          <div className="alert-text">
            <strong className="truncate">{v.name} invited you</strong>
            <span className="muted small truncate">{v.detail}</span>
          </div>
          <div className="alert-actions">
            <Button size="sm" variant="primary" onClick={() => p.onJoin(v.fromUid)}>
              Join
            </Button>
            <Button size="sm" variant="ghost" onClick={() => p.onDeclineInvite(v.fromUid)}>
              Not now
            </Button>
          </div>
        </div>
      ))}
      {p.joins.map((j) => (
        <div key={`j:${j.fromUid}`} className="alert-card glass glass--strong" role="alert">
          <Avatar name={j.name} id={j.fromUid} photoUrl={j.photoUrl} size={40} />
          <div className="alert-text">
            <strong className="truncate">{j.name} asks to join</strong>
            <span className="muted small truncate">{j.detail}</span>
          </div>
          <div className="alert-actions">
            <Button size="sm" variant="primary" onClick={() => p.onLetIn(j.fromUid)}>
              Let in
            </Button>
            <Button size="sm" variant="ghost" onClick={() => p.onDeclineJoin(j.fromUid)}>
              Not now
            </Button>
          </div>
        </div>
      ))}
    </div>
  )
}

/** "You're offline" while the database connection is down; "Back online" for a moment after. */
export function ConnectionBanner({ state }: { state: 'online' | 'offline' | 'back' }): JSX.Element | null {
  if (state === 'online') return null
  return (
    <div className={state === 'offline' ? 'conn-banner conn-banner--off' : 'conn-banner'} role="status" aria-live="polite">
      {state === 'offline' ? "You're offline. Reconnecting…" : 'Back online'}
    </div>
  )
}
