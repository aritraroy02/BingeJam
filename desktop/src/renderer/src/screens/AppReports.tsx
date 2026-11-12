import { useMemo, useState, type CSSProperties, type JSX } from 'react'
import {
  addDeveloper,
  OWNER_EMAIL,
  removeDeveloper,
  useAppUsers,
  useCrashCount,
  useDevelopers,
  useStaffRole,
  type AppUserVM
} from '@/data/appReports'
import { ageOn, parseBirthDate, todayLocal } from '@/data/birthDate'
import { agoShort } from '@/data/format'
import { broadcastNotification } from '@/data/relay'
import { ConfirmDialog, Dialog } from '@/ui/Dialog'
import { Avatar, Button, Segmented } from '@/ui/primitives'

// App reports on the PC (2026-11-12): the owner and the developers the owner adds, checked by the
// database rules and the relay from the signed-in account's verified email. Crash reports are
// end-to-end encrypted for staff phones, so they're read on the phone; here you see how many.
// Unlike the other screens this one reads its own data: it only exists for real staff accounts.

type Tab = 'users' | 'notify' | 'team'

const MAX_TITLE = 65
const MAX_BODY = 240
const MAX_PICK = 20

export function AppReports({ onBack, onNotice }: { onBack: () => void; onNotice: (text: string) => void }): JSX.Element {
  const role = useStaffRole()
  const owner = role === 'owner'
  const [tab, setTab] = useState<Tab>(owner ? 'users' : 'notify')
  const users = useAppUsers(owner)
  const crashes = useCrashCount(role === 'owner' || role === 'developer')
  const tabs: { value: Tab; label: string }[] = owner
    ? [
        { value: 'users', label: 'Users' },
        { value: 'notify', label: 'Notify' },
        { value: 'team', label: 'Team' }
      ]
    : [{ value: 'notify', label: 'Notify' }]

  if (role === undefined) return <div className="screen" />
  if (role === null) {
    return (
      <div className="screen">
        <section className="glass" style={{ flex: 1, padding: 32 }}>
          <p className="muted">App reports are only for the app&apos;s owner and developers.</p>
          <Button onClick={onBack}>Back</Button>
        </section>
      </div>
    )
  }

  return (
    <div className="screen" style={{ flexDirection: 'column' }}>
      <header className="glass glass--bar rise" style={{ display: 'flex', alignItems: 'center', gap: 12, padding: 12 } as CSSProperties}>
        <Button size="sm" variant="ghost" onClick={onBack}>
          Back
        </Button>
        <h1 className="h2" style={{ flex: 1 }}>
          App reports
        </h1>
        <span className="muted small">{crashes === null ? '' : `${crashes} crash ${crashes === 1 ? 'report' : 'reports'} this week · read them on your phone`}</span>
        <Segmented label="Section" options={tabs} value={tab} onChange={setTab} />
      </header>
      <div className="scroll" style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 20, margin: -8, padding: 8 }}>
        {tab === 'users' && owner ? <UsersPanel users={users} /> : null}
        {tab === 'notify' ? <NotifyPanel owner={owner} users={users} onNotice={onNotice} /> : null}
        {tab === 'team' && owner ? <TeamPanel onNotice={onNotice} /> : null}
      </div>
    </div>
  )
}

// ---------- Users (owner) ----------

function UsersPanel({ users }: { users: AppUserVM[] | null }): JSX.Element {
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState<AppUserVM | null>(null)
  const today = todayLocal()
  const q = query.trim().replace(/^@/, '').toLowerCase()
  const shown = (users ?? []).filter((u) => !q || u.name.toLowerCase().includes(q) || u.username.includes(q) || (u.email ?? '').toLowerCase().includes(q))
  const soon = useMemo(
    () =>
      (users ?? [])
        .flatMap((u) => {
          const d = parseBirthDate(u.birthDate)
          if (!d) return []
          const days = daysToBirthday(d.m, d.d)
          return days <= 30 ? [{ u, days, turns: ageOn(d, today) + (days === 0 ? 0 : 1) }] : []
        })
        .sort((a, b) => a.days - b.days),
    [users]
  )
  const online = (users ?? []).filter((u) => u.online).length

  return (
    <>
      {soon.length > 0 ? (
        <section className="glass" style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <h2 className="h3">Birthdays soon</h2>
          {soon.map(({ u, days, turns }) => (
            <button key={u.uid} type="button" className="member-row pick-row" onClick={() => setOpen(u)}>
              <Avatar name={u.name || '?'} id={u.uid} photoUrl={u.photoUrl} size={32} />
              <span className="member-row__text">
                <span className="member-row__name truncate">{u.name}</span>
              </span>
              <span className="subtle" style={days === 0 ? { color: 'var(--accent-text)' } : undefined}>
                turns {turns} {days === 0 ? 'today' : days === 1 ? 'tomorrow' : `in ${days} days`}
              </span>
            </button>
          ))}
        </section>
      ) : null}
      <section className="glass" style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 12 }}>
          <h2 className="h3" style={{ flex: 1 }}>
            {users ? `${users.length} ${users.length === 1 ? 'person' : 'people'} joined · ${online} online now` : 'Loading people…'}
          </h2>
          <input className="field" style={{ maxWidth: 320 }} placeholder="Search name, username or email" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {shown.map((u) => (
          <button key={u.uid} type="button" className="member-row pick-row" onClick={() => setOpen(u)}>
            <Avatar name={u.name || '?'} id={u.uid} photoUrl={u.photoUrl} size={40} online={u.online} />
            <span className="member-row__text">
              <span className="member-row__name truncate">{u.name}</span>
              <span className="member-row__detail truncate">{[`@${u.username}`, u.email].filter(Boolean).join(' · ')}</span>
              <span className="member-row__detail truncate">
                {[
                  u.joinedAt ? `Joined ${agoShort(Date.now() - u.joinedAt)} ago` : null,
                  u.online ? 'online' : u.lastSeen ? `seen ${agoShort(Date.now() - u.lastSeen)} ago` : null,
                  u.appVersion ? `v${u.appVersion}` : null,
                  u.device
                ]
                  .filter(Boolean)
                  .join(' · ')}
              </span>
            </span>
          </button>
        ))}
      </section>
      <UserDialog user={open} onClose={() => setOpen(null)} />
    </>
  )
}

function daysToBirthday(month: number, day: number): number {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const leap = (y: number): boolean => (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0
  const on = (y: number): Date => (month === 2 && day === 29 && !leap(y) ? new Date(y, 1, 28) : new Date(y, month - 1, day))
  let next = on(today.getFullYear())
  if (next < today) next = on(today.getFullYear() + 1)
  return Math.round((next.getTime() - today.getTime()) / 86_400_000)
}

function UserDialog({ user: u, onClose }: { user: AppUserVM | null; onClose: () => void }): JSX.Element {
  const birth = parseBirthDate(u?.birthDate)
  const today = todayLocal()
  const rows: [string, string][] = u
    ? [
        ['Email', u.email ?? 'Not recorded'],
        [
          'Birthday',
          birth
            ? `${new Date(birth.y, birth.m - 1, birth.d).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' })} · ${ageOn(birth, today)} years old`
            : 'Not given yet (asked next time they open the app)'
        ],
        ['Signed in with', u.provider ?? 'Unknown'],
        ['Joined', u.joinedAt ? new Date(u.joinedAt).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' }) : 'Unknown'],
        ['Last online', u.online ? 'Online now' : u.lastSeen ? `${agoShort(Date.now() - u.lastSeen)} ago` : 'Unknown'],
        ['Phone', [u.device, u.android ? `Android ${u.android}` : null].filter(Boolean).join(' · ') || 'Not recorded yet'],
        ['App', u.appVersion ? `v${u.appVersion}` : 'Not recorded yet'],
        ['User ID', u.uid]
      ]
    : []
  return (
    <Dialog open={u !== null} title={u?.name ?? ''} onClose={onClose} width={480}>
      {u ? (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Avatar name={u.name || '?'} id={u.uid} photoUrl={u.photoUrl} size={72} online={u.online} />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span className="muted">@{u.username}</span>
              {u.bio ? <span className="small">{u.bio}</span> : null}
            </div>
          </div>
          <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: '120px 1fr', gap: '8px 16px' }}>
            {rows.map(([k, v]) => (
              <div key={k} style={{ display: 'contents' }}>
                <dt className="subtle">{k}</dt>
                <dd className="selectable" style={{ margin: 0 }}>
                  {v}
                </dd>
              </div>
            ))}
          </dl>
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
            <Button
              onClick={() =>
                void navigator.clipboard.writeText(
                  [`${u.name} (@${u.username})`, u.email ? `Email: ${u.email}` : null, u.birthDate ? `Birthday: ${u.birthDate}` : null, `User ID: ${u.uid}`]
                    .filter(Boolean)
                    .join('\n')
                )
              }
            >
              Copy details
            </Button>
            <Button variant="primary" onClick={onClose}>
              Close
            </Button>
          </div>
        </>
      ) : null}
    </Dialog>
  )
}

// ---------- Notify ----------

function NotifyPanel({ owner, users, onNotice }: { owner: boolean; users: AppUserVM[] | null; onNotice: (t: string) => void }): JSX.Element {
  const [title, setTitle] = useState('')
  const [body, setBody] = useState('')
  const [pick, setPick] = useState(false)
  const [picked, setPicked] = useState<string[]>([])
  const [confirm, setConfirm] = useState(false)
  const [sending, setSending] = useState(false)
  const toPicked = owner && pick
  const ready = title.trim() && body.trim() && (!toPicked || picked.length > 0) && !sending

  async function send(): Promise<void> {
    setSending(true)
    try {
      const reached = await broadcastNotification(title.trim(), body.trim(), toPicked ? picked : null)
      onNotice(toPicked ? `Sent to ${picked.length} ${picked.length === 1 ? 'person' : 'people'}${reached !== null ? ` (${reached} phones)` : ''}` : 'Sent to everyone')
      setTitle('')
      setBody('')
      setPicked([])
    } catch (e) {
      onNotice(e instanceof Error ? e.message : "Couldn't send")
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="glass" style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 720 }}>
      <h2 className="h3">Send a notification</h2>
      <p className="muted small" style={{ margin: 0 }}>
        {owner ? 'To everyone, or to people you pick.' : 'To everyone who has BingeJam.'} It shows on their phones right away.
      </p>
      <input className="field" placeholder="Title" maxLength={MAX_TITLE} value={title} onChange={(e) => setTitle(e.target.value)} />
      <textarea
        className="field"
        style={{ height: 96, padding: '12px 16px', resize: 'none' }}
        placeholder="Message"
        maxLength={MAX_BODY}
        value={body}
        onChange={(e) => setBody(e.target.value)}
      />
      <span className="subtle">
        {body.length}/{MAX_BODY}
      </span>
      {owner ? (
        <Segmented
          label="Who"
          options={[
            { value: 'all', label: 'Everyone' },
            { value: 'pick', label: 'Pick people' }
          ]}
          value={pick ? 'pick' : 'all'}
          onChange={(v) => setPick(v === 'pick')}
        />
      ) : null}
      {toPicked ? (
        <ul className="pick-list" style={{ maxHeight: 320 }}>
          {(users ?? []).map((u) => (
            <li key={u.uid}>
              <label className="pick-row">
                <Avatar name={u.name || '?'} id={u.uid} photoUrl={u.photoUrl} size={32} />
                <span style={{ flex: 1, minWidth: 0 }} className="truncate">
                  {u.name} <span className="subtle">@{u.username}</span>
                </span>
                <input
                  type="checkbox"
                  checked={picked.includes(u.uid)}
                  onChange={(e) => setPicked((cur) => (e.target.checked ? (cur.length < MAX_PICK ? [...cur, u.uid] : cur) : cur.filter((x) => x !== u.uid)))}
                />
              </label>
            </li>
          ))}
        </ul>
      ) : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button variant="primary" disabled={!ready} onClick={() => setConfirm(true)}>
          {sending ? 'Sending…' : toPicked ? `Send to ${picked.length} ${picked.length === 1 ? 'person' : 'people'}` : 'Send to everyone'}
        </Button>
      </div>
      <ConfirmDialog
        open={confirm}
        title={toPicked ? `Send to ${picked.length} ${picked.length === 1 ? 'person' : 'people'}?` : 'Send to everyone?'}
        description={`"${title.trim()}" will show as a notification on their phones right away.`}
        confirmLabel="Send"
        onConfirm={() => void send()}
        onClose={() => setConfirm(false)}
      />
    </section>
  )
}

// ---------- Team (owner) ----------

function TeamPanel({ onNotice }: { onNotice: (t: string) => void }): JSX.Element {
  const developers = useDevelopers(true)
  const [email, setEmail] = useState('')
  const [removing, setRemoving] = useState<string | null>(null)
  return (
    <section className="glass" style={{ padding: 24, display: 'flex', flexDirection: 'column', gap: 12, maxWidth: 720 }}>
      <h2 className="h3">Owner</h2>
      <span>{OWNER_EMAIL}</span>
      <span className="subtle">Everything: crashes, users and their emails, notifications to anyone, the team.</span>
      <div className="divider" />
      <h2 className="h3">Developers</h2>
      {developers === null ? <p className="muted small">Loading…</p> : developers.length === 0 ? <p className="muted small">No developers yet.</p> : null}
      {(developers ?? []).map((d) => (
        <div key={d} className="member-row">
          <span className="member-row__text">
            <span className="member-row__name truncate">{d}</span>
          </span>
          <Button size="xs" onClick={() => setRemoving(d)}>
            Remove
          </Button>
        </div>
      ))}
      <span className="subtle">Developers read crash reports and send notifications to everyone. They need a BingeJam account with that email.</span>
      <form
        style={{ display: 'flex', gap: 12 }}
        onSubmit={(e) => {
          e.preventDefault()
          void addDeveloper(email).then(
            () => {
              onNotice('Added. They see App reports once they open it on their phone.')
              setEmail('')
            },
            (err: unknown) => onNotice(err instanceof Error ? err.message : "Couldn't add them")
          )
        }}
      >
        <input className="field" placeholder="Developer's email" value={email} onChange={(e) => setEmail(e.target.value)} />
        <Button type="submit" disabled={!email.includes('@')}>
          Add
        </Button>
      </form>
      <ConfirmDialog
        open={removing !== null}
        title={`Remove ${removing ?? ''}?`}
        description="They lose access to App reports, and new reports aren't encrypted for them any more."
        confirmLabel="Remove"
        danger
        onConfirm={() =>
          removing &&
          void removeDeveloper(removing).then(
            () => onNotice(`Removed ${removing}`),
            (err: unknown) => onNotice(err instanceof Error ? err.message : "Couldn't remove them")
          )
        }
        onClose={() => setRemoving(null)}
      />
    </section>
  )
}
