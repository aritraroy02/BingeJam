import type { JSX, ReactNode } from 'react'
import type { FriendVM } from './models'
import { Icon } from './Icon'
import { Avatar, Button, LiveDot } from './primitives'

/** Friend rows from the Home design (also the Friends tab): a card with Join / Watch / Jam for friends doing something. */
export function FriendList({
  friends,
  onAction,
  onMessage,
  onOpen,
  empty = 'Add friends by their username to watch together.'
}: {
  friends: FriendVM[] | null
  onAction: (f: FriendVM) => void
  /** Friends tab: a message button on every row. */
  onMessage?: (f: FriendVM) => void
  /** Click a friend's picture or name: their profile. */
  onOpen?: (f: FriendVM) => void
  empty?: ReactNode
}): JSX.Element {
  if (friends === null) return <p className="muted small">Loading friends…</p>
  if (friends.length === 0) return <p className="muted small">{empty}</p>
  return (
    <div className="scroll" style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      {friends.map((f) => (
        <FriendRow key={f.id} friend={f} onAction={onAction} onMessage={onMessage} onOpen={onOpen} />
      ))}
    </div>
  )
}

export function FriendRow({
  friend: f,
  onAction,
  onMessage,
  onOpen
}: {
  friend: FriendVM
  onAction: (f: FriendVM) => void
  onMessage?: (f: FriendVM) => void
  onOpen?: (f: FriendVM) => void
}): JSX.Element {
  return (
    <div className={f.action ? 'friend-row friend-row--card' : 'friend-row'}>
      <button
        type="button"
        className="person-open"
        disabled={!onOpen}
        aria-label={onOpen ? `${f.name}'s profile` : undefined}
        onClick={() => onOpen?.(f)}
      >
        <Avatar name={f.name} id={f.id} photoUrl={f.photoUrl} size={f.action ? 40 : 44} ring={f.action !== null} />
      </button>
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <span className="truncate" style={{ fontWeight: 500 }}>
          {f.name}
        </span>
        <span style={{ fontSize: 12, display: 'flex', alignItems: 'center', gap: 8, color: f.streaming ? 'var(--accent-text)' : 'var(--text-2)' }}>
          {f.streaming ? <LiveDot red /> : null}
          <span style={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{f.status}</span>
        </span>
      </div>
      {onMessage ? (
        <Button size="sm" icon aria-label={`Message ${f.name}`} title="Message" onClick={() => onMessage(f)}>
          <Icon name="chat" size={18} />
        </Button>
      ) : null}
      {f.action ? (
        <Button variant={f.action === 'join' ? 'primary' : 'default'} size="sm" onClick={() => onAction(f)}>
          {f.action === 'join' ? 'Join' : f.action === 'watch' ? 'Watch' : 'Jam'}
        </Button>
      ) : null}
    </div>
  )
}
