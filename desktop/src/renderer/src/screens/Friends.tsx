import { useId, useState, type CSSProperties, type FormEvent, type JSX } from 'react'
import { Icon } from '@/ui/Icon'
import { Avatar, Button } from '@/ui/primitives'
import { FriendList } from '@/ui/FriendList'
import { normalizeUsername, usernameProblem } from '@/data/usernames'
import type { AddFriendResult, FriendRequestVM, FriendVM, FriendsVM } from '@/ui/models'

// Friends (not in the mock-ups): your friends with what they're doing, requests, and "Add friend by
// exact username". Usernames are private, so there's no search: only an exact match finds someone.

export interface FriendsProps {
  data: FriendsVM
  onFriendAction: (friend: FriendVM) => void
  onMessage: (friend: FriendVM) => void
  /** The relay / database answer, shown under the field. */
  onAddFriend: (username: string) => Promise<AddFriendResult>
  onAccept: (uid: string) => void
  onDecline: (uid: string) => void
  onCancelRequest: (uid: string) => void
}

export function Friends(p: FriendsProps): JSX.Element {
  const friends = p.data.friends
  const online = (friends ?? []).filter((f) => f.online || f.action).length
  return (
    <div className="screen">
      <section
        aria-labelledby="h-friends-tab"
        className="glass rise"
        style={{ '--i': 1, flex: '999 1 520px', minWidth: 0, padding: 22, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}
      >
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
          <h2 id="h-friends-tab" className="h2" style={{ flex: 1 }}>
            Friends{friends ? ` · ${friends.length}` : ''}
          </h2>
          {friends ? <span className="muted small">{online} online</span> : null}
        </div>
        <FriendList
          friends={friends}
          onAction={p.onFriendAction}
          onMessage={p.onMessage}
          empty="No friends yet. Ask friends for their exact username and add them on the right."
        />
      </section>

      <div className="scroll" style={{ flex: '1 1 400px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20, margin: -6, padding: 6 }}>
        <AddFriend onAddFriend={p.onAddFriend} />
        <RequestsPanel
          title="Friend requests"
          i={3}
          requests={p.data.requests}
          empty="No requests right now."
          actions={(r) => (
            <>
              <Button variant="primary" size="xs" aria-label={`Accept ${r.name}`} onClick={() => p.onAccept(r.id)}>
                Accept
              </Button>
              <Button size="xs" aria-label={`Decline ${r.name}`} onClick={() => p.onDecline(r.id)}>
                Decline
              </Button>
            </>
          )}
        />
        {p.data.sent && p.data.sent.length > 0 ? (
          <RequestsPanel
            title="Sent"
            i={4}
            requests={p.data.sent}
            empty=""
            actions={(r) => (
              <Button size="xs" aria-label={`Cancel request to ${r.name}`} onClick={() => p.onCancelRequest(r.id)}>
                Cancel
              </Button>
            )}
          />
        ) : null}
      </div>
    </div>
  )
}

function AddFriend({ onAddFriend }: { onAddFriend: (username: string) => Promise<AddFriendResult> }): JSX.Element {
  const id = useId()
  const hintId = useId()
  const [value, setValue] = useState('')
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<AddFriendResult | null>(null)

  async function submit(e: FormEvent): Promise<void> {
    e.preventDefault()
    const name = normalizeUsername(value)
    const problem = usernameProblem(name)
    if (problem) {
      setResult({ ok: false, message: problem })
      return
    }
    setBusy(true)
    setResult(null)
    try {
      const r = await onAddFriend(name)
      setResult(r)
      if (r.ok) setValue('')
    } catch {
      setResult({ ok: false, message: "Couldn't send the request. Check your connection and try again." })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="h-add-friend" className="glass glass--raised rise" style={{ '--i': 2, padding: 22, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}>
      <h2 id="h-add-friend" className="h3">
        Add friend
      </h2>
      <p id={hintId} className="muted small" style={{ margin: 0, lineHeight: 1.5 }}>
        Usernames are private, so type their exact username. They get a request and can accept it on their phone or PC.
      </p>
      <form onSubmit={submit} style={{ display: 'flex', gap: 10 }}>
        <label htmlFor={id} className="sr-only">
          Exact username
        </label>
        <div className="field-wrap" style={{ flex: 1, minWidth: 0 }}>
          <span aria-hidden="true" style={{ position: 'absolute', left: 16, color: 'var(--text-2)', fontWeight: 600 }}>
            @
          </span>
          <input
            id={id}
            className="field"
            style={{ paddingLeft: 34 }}
            placeholder="exact.username"
            autoComplete="off"
            spellCheck={false}
            maxLength={21}
            aria-describedby={hintId}
            aria-invalid={result ? !result.ok : undefined}
            value={value}
            onChange={(e) => {
              setValue(e.target.value)
              setResult(null)
            }}
          />
        </div>
        <Button variant="primary" type="submit" disabled={busy || !value.trim()}>
          <Icon name="friends" size={18} />
          {busy ? 'Sending…' : 'Add'}
        </Button>
      </form>
      {result ? (
        <p role={result.ok ? 'status' : 'alert'} style={{ margin: 0, fontSize: 13, color: result.ok ? 'var(--live-soft)' : '#FECDD3' }}>
          {result.message}
        </p>
      ) : null}
    </section>
  )
}

function RequestsPanel({
  title,
  i,
  requests,
  empty,
  actions
}: {
  title: string
  i: number
  requests: FriendRequestVM[] | null
  empty: string
  actions: (r: FriendRequestVM) => JSX.Element
}): JSX.Element {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="glass rise" style={{ '--i': i, padding: 22, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}>
      <h2 id={headingId} className="h3">
        {title}
        {requests && requests.length > 0 ? ` · ${requests.length}` : ''}
      </h2>
      {requests === null ? <p className="muted small">Loading…</p> : null}
      {requests?.length === 0 ? (
        <p className="muted small" style={{ margin: 0 }}>
          {empty}
        </p>
      ) : null}
      {(requests ?? []).map((r) => (
        <div key={r.id} className="member-row">
          <Avatar name={r.name} id={r.id} photoUrl={r.photoUrl} size={40} />
          <span className="member-row__text">
            <span className="member-row__name truncate">{r.name}</span>
            <span className="member-row__detail">
              {r.username ? `@${r.username} · ` : ''}
              {r.sentLabel}
            </span>
          </span>
          <span style={{ display: 'flex', gap: 6 }}>{actions(r)}</span>
        </div>
      ))}
    </section>
  )
}
