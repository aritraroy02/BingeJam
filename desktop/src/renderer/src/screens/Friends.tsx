import { useId, useState, type CSSProperties, type FormEvent, type JSX } from 'react'
import { Icon } from '@/ui/Icon'
import { Avatar, Button } from '@/ui/primitives'
import { FriendList } from '@/ui/FriendList'
import { normalizeUsername, usernameProblem } from '@/data/usernames'
import type { AddFriendResult, FriendRequestVM, FriendVM, FriendsVM, InviteVM } from '@/ui/models'
import { QrCode } from '@/ui/QrCode'
import { ConfirmDialog } from '@/ui/Dialog'
import { parseInviteLink } from '@/data/invites'

// Friends (not in the mock-ups): your friends with what they're doing, requests, and "Add friend by
// exact username". Usernames are private, so there's no search: only an exact match finds someone.

export interface FriendsProps {
  data: FriendsVM
  /** Our invite link and its QR. */
  invite?: InviteVM
  onCopyInvite?: () => void
  onResetInvite?: () => void
  onFriendAction: (friend: FriendVM) => void
  onMessage: (friend: FriendVM) => void
  /** The relay / database answer, shown under the field. */
  onAddFriend: (username: string) => Promise<AddFriendResult>
  onAccept: (uid: string) => void
  onDecline: (uid: string) => void
  onCancelRequest: (uid: string) => void
  onUnblockUser?: (uid: string) => void
  onUnblockGroup?: (gid: string) => void
  onOpenProfile?: (uid: string) => void
}

export function Friends(p: FriendsProps): JSX.Element {
  const friends = p.data.friends
  const online = (friends ?? []).filter((f) => f.online || f.action).length
  return (
    <div className="screen">
      <section
        aria-labelledby="h-friends-tab"
        className="glass rise"
        style={{ '--i': 1, flex: '999 1 520px', minWidth: 0, padding: 24, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}
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
          onOpen={p.onOpenProfile ? (f) => p.onOpenProfile?.(f.id) : undefined}
          empty="No friends yet. Ask friends for their exact username and add them on the right."
        />
      </section>

      <div className="scroll" style={{ flex: '1 1 400px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20, margin: -8, padding: 8 }}>
        {p.invite ? <InvitePanel invite={p.invite} onCopy={p.onCopyInvite} onReset={p.onResetInvite} /> : null}
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
        {p.data.blockedPeople && p.data.blockedPeople.length > 0 ? (
          <BlockedPanel
            title="Blocked"
            i={5}
            rows={p.data.blockedPeople.map((b) => ({ id: b.id, name: b.name, detail: b.username ? `@${b.username}` : '', photoUrl: b.photoUrl }))}
            onUnblock={(id) => p.onUnblockUser?.(id)}
          />
        ) : null}
        {p.data.blockedGroups && p.data.blockedGroups.length > 0 ? (
          <BlockedPanel
            title="Blocked groups"
            i={6}
            group
            rows={p.data.blockedGroups.map((g) => ({ id: g.id, name: g.name, detail: 'Nobody can add you to it', photoUrl: null }))}
            onUnblock={(id) => p.onUnblockGroup?.(id)}
          />
        ) : null}
      </div>
    </div>
  )
}

/** People or groups you blocked, each with Unblock (2026-11-10, same as the phone's Friends screen). */
function BlockedPanel({
  title,
  i,
  rows,
  group = false,
  onUnblock
}: {
  title: string
  i: number
  rows: { id: string; name: string; detail: string; photoUrl: string | null }[]
  group?: boolean
  onUnblock: (id: string) => void
}): JSX.Element {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="glass rise" style={{ '--i': i, padding: 24, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}>
      <h2 id={headingId} className="h3">
        {title} · {rows.length}
      </h2>
      {rows.map((r) => (
        <div key={r.id} className="member-row">
          <Avatar name={r.name} id={r.id} photoUrl={r.photoUrl} size={40} group={group} />
          <span className="member-row__text">
            <span className="member-row__name truncate">{r.name}</span>
            {r.detail ? <span className="member-row__detail">{r.detail}</span> : null}
          </span>
          <Button size="xs" aria-label={`Unblock ${r.name}`} onClick={() => onUnblock(r.id)}>
            Unblock
          </Button>
        </div>
      ))}
    </section>
  )
}

/** Our personal link and QR (2026-11-12): whoever opens it on their phone becomes our friend. */
function InvitePanel({ invite, onCopy, onReset }: { invite: InviteVM; onCopy?: () => void; onReset?: () => void }): JSX.Element {
  const headingId = useId()
  const [confirmReset, setConfirmReset] = useState(false)
  return (
    <section aria-labelledby={headingId} className="glass rise" style={{ '--i': 2, padding: 24, display: 'flex', gap: 20, alignItems: 'center', flexWrap: 'wrap' } as CSSProperties}>
      {invite.link ? <QrCode text={invite.link} size={152} label="QR code for your invite link" /> : <div className="tile" style={{ width: 152, height: 152 }} />}
      <div style={{ flex: '1 1 200px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
        <h2 id={headingId} className="h3">
          Invite friends
        </h2>
        <p className="muted small" style={{ margin: 0, lineHeight: 1.5 }}>
          Scan with a phone camera or send the link. Whoever opens it becomes your friend straight away.
        </p>
        <span className="subtle selectable truncate">{invite.link ?? 'Loading your link…'}</span>
        <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
          <Button size="sm" variant="primary" disabled={!invite.link} onClick={onCopy}>
            Copy link
          </Button>
          <Button size="sm" variant="ghost" disabled={!invite.link} onClick={() => setConfirmReset(true)}>
            Reset link
          </Button>
        </div>
      </div>
      <ConfirmDialog
        open={confirmReset}
        title="Reset your invite link?"
        description="Links and QR codes you've shared stop adding friends automatically. People who open them can still send you a request."
        confirmLabel="Reset"
        danger
        onConfirm={() => onReset?.()}
        onClose={() => setConfirmReset(false)}
      />
    </section>
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
    // A pasted invite link works too: "add/<username>/<token>" (the token makes it instant).
    const link = parseInviteLink(value)
    const name = link ? `${link.username}#${link.token}` : normalizeUsername(value)
    const problem = link ? null : usernameProblem(name)
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
    <section aria-labelledby="h-add-friend" className="glass glass--raised rise" style={{ '--i': 2, padding: 24, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}>
      <h2 id="h-add-friend" className="h3">
        Add friend
      </h2>
      <p id={hintId} className="muted small" style={{ margin: 0, lineHeight: 1.5 }}>
        Usernames are private, so type their exact username. They get a request and can accept it on their phone or PC.
      </p>
      <form onSubmit={submit} style={{ display: 'flex', gap: 12 }}>
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
            style={{ paddingLeft: 32 }}
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
        <p role={result.ok ? 'status' : 'alert'} style={{ margin: 0, fontSize: 13, color: result.ok ? 'var(--live-soft)' : 'var(--accent-text)' }}>
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
    <section aria-labelledby={headingId} className="glass rise" style={{ '--i': i, padding: 24, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}>
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
          <span style={{ display: 'flex', gap: 8 }}>{actions(r)}</span>
        </div>
      ))}
    </section>
  )
}
