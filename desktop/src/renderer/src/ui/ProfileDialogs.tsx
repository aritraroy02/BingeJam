import { useEffect, useId, useRef, useState, type JSX } from 'react'
import { birthDateProblem, parseBirthDate, todayLocal, wireBirthDate } from '@/data/birthDate'
import { Dialog } from './Dialog'
import { MenuButton, type MenuItem } from './Menu'
import { Avatar, Button } from './primitives'
import { ReportDialog, type ReportReasonValue } from './ReportDialog'
import { firstName } from './format'
import type { ProfileVM } from './models'

/** A profile picture as big as the window allows; Escape, the backdrop or ✕ closes it. */
export function PhotoViewer({ photoUrl, name, onClose }: { photoUrl: string | null; name: string; onClose: () => void }): JSX.Element {
  return (
    <Dialog open={photoUrl !== null} title={name} onClose={onClose} width={640}>
      {photoUrl ? (
        <img
          src={photoUrl}
          alt={`${name}'s profile picture`}
          style={{ width: '100%', maxHeight: '70vh', objectFit: 'contain', borderRadius: 12, background: 'var(--raised)' }}
        />
      ) : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={onClose}>Close</Button>
      </div>
    </Dialog>
  )
}

/**
 * Someone's profile (2026-11-12, same as the phone's profile page): picture (click for full size),
 * name, @username, bio, and what you can do with them.
 */
export function ProfileDialog({
  profile,
  onClose,
  onMessage,
  onAdd,
  onAccept,
  onCancelRequest,
  onUnblock,
  onUnfriend,
  onBlock,
  onReport
}: {
  profile: ProfileVM | null
  onClose: () => void
  onMessage: (uid: string) => void
  onAdd: (username: string) => void
  onAccept: (uid: string) => void
  onCancelRequest: (uid: string) => void
  onUnblock: (uid: string) => void
  onUnfriend: (uid: string) => void
  onBlock: (uid: string) => void
  onReport: (uid: string, name: string, reason: ReportReasonValue, details: string | null, block: boolean) => void
}): JSX.Element {
  const [viewing, setViewing] = useState(false)
  const [reporting, setReporting] = useState(false)
  const [confirm, setConfirm] = useState<'block' | 'unfriend' | null>(null)
  const p = profile
  const first = firstName(p?.name ?? '') || 'them'

  const menu: MenuItem[] = []
  if (p && p.relation === 'friend') menu.push({ label: 'Remove friend', icon: 'friends', onSelect: () => setConfirm('unfriend') })
  if (p && p.relation !== 'me' && p.relation !== 'blocked') menu.push({ label: `Block ${first}`, icon: 'block', danger: true, onSelect: () => setConfirm('block') })
  if (p && p.relation !== 'me') menu.push({ label: `Report ${first}`, icon: 'flag', danger: true, onSelect: () => setReporting(true) })

  return (
    <>
      <Dialog open={p !== null && !viewing && !reporting && confirm === null} title={p?.name ?? 'Profile'} onClose={onClose} width={420}>
        {p ? (
          p.missing ? (
            <p className="muted" style={{ margin: 0 }}>
              This account doesn&apos;t exist any more.
            </p>
          ) : (
            <>
              <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                <button
                  type="button"
                  className="pressable"
                  style={{ border: 'none', padding: 0, background: 'none', borderRadius: '50%', cursor: p.photoUrl ? 'zoom-in' : 'default' }}
                  disabled={!p.photoUrl}
                  aria-label={p.photoUrl ? `View ${p.name}'s profile picture` : undefined}
                  onClick={() => setViewing(true)}
                >
                  <Avatar name={p.name} id={p.id} photoUrl={p.photoUrl} size={88} />
                </button>
                <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span className="muted">@{p.username}</span>
                  {p.status ? <span className="subtle">{p.status}</span> : null}
                  {p.since ? <span className="subtle">{p.since}</span> : null}
                </div>
                {menu.length > 0 ? <MenuButton label="More options" items={menu} /> : null}
              </div>
              {p.bio ? (
                <p className="selectable" style={{ margin: 0, lineHeight: 1.5 }}>
                  {p.bio}
                </p>
              ) : null}
              {p.activity ? (
                <div className="tile" style={{ padding: 12, fontSize: 13 }}>
                  {p.activity}
                </div>
              ) : null}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
                <Button onClick={onClose}>Close</Button>
                {p.relation === 'friend' ? (
                  <Button variant="primary" onClick={() => onMessage(p.id)}>
                    Message {first}
                  </Button>
                ) : p.relation === 'incoming' ? (
                  <Button variant="primary" onClick={() => onAccept(p.id)}>
                    Accept request
                  </Button>
                ) : p.relation === 'requested' ? (
                  <Button onClick={() => onCancelRequest(p.id)}>Cancel request</Button>
                ) : p.relation === 'blocked' ? (
                  <Button onClick={() => onUnblock(p.id)}>Unblock</Button>
                ) : p.relation === 'none' ? (
                  <Button variant="primary" onClick={() => onAdd(p.username)}>
                    Add friend
                  </Button>
                ) : null}
              </div>
            </>
          )
        ) : null}
      </Dialog>
      <PhotoViewer photoUrl={viewing ? (p?.photoUrl ?? null) : null} name={p?.name ?? ''} onClose={() => setViewing(false)} />
      <ReportDialog
        open={reporting}
        title={`Report ${p?.name ?? ''}`.trim()}
        evidenceNote={
          p?.relation === 'friend'
            ? `${first}'s last 5 messages to you are sent with your report so it can be reviewed. ${first} isn't told.`
            : `${first} isn't told who reported them.`
        }
        blockLabel={`Also block ${first}`}
        onClose={() => setReporting(false)}
        onSubmit={(reason, details, block) => p && onReport(p.id, p.name, reason, details, block)}
      />
      <Dialog
        open={confirm !== null}
        title={confirm === 'block' ? `Block ${p?.name ?? ''}?` : `Remove ${p?.name ?? ''}?`}
        description={
          confirm === 'block'
            ? "You won't be friends any more. They can't message you, send you requests or see your activity. You can unblock them from Friends."
            : "You won't be friends any more, and your chat closes until you add each other again."
        }
        onClose={() => setConfirm(null)}
      >
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button onClick={() => setConfirm(null)}>Cancel</Button>
          <Button
            variant="danger"
            onClick={() => {
              if (p) (confirm === 'block' ? onBlock : onUnfriend)(p.id)
              setConfirm(null)
            }}
          >
            {confirm === 'block' ? 'Block' : 'Remove'}
          </Button>
        </div>
      </Dialog>
    </>
  )
}

export interface EditProfileValues {
  name: string
  username: string
  bio: string
  /** "YYYY-MM-DD" or empty. */
  birthDate: string
}

/**
 * Edit profile (2026-11-12, same fields as the phone): picture, name, username, bio and date of
 * birth (private, 18+). Save writes only what changed.
 */
export function EditProfileDialog({
  open,
  initial,
  photoUrl,
  busy,
  onClose,
  onSave,
  onPickPhoto,
  onRemovePhoto
}: {
  open: boolean
  initial: EditProfileValues
  photoUrl: string | null
  busy: boolean
  onClose: () => void
  onSave: (values: EditProfileValues) => void
  onPickPhoto: (file: File) => void
  onRemovePhoto: () => void
}): JSX.Element {
  const [v, setV] = useState(initial)
  const fileRef = useRef<HTMLInputElement>(null)
  const ids = { name: useId(), username: useId(), bio: useId(), dob: useId() }
  useEffect(() => {
    if (open) setV(initial)
  }, [open])
  const parsed = parseBirthDate(v.birthDate)
  const dobProblem = v.birthDate ? birthDateProblem(parsed, todayLocal()) : null
  const today = todayLocal()
  const canSave = v.name.trim().length > 0 && v.username.trim().length >= 3 && !dobProblem && !busy

  return (
    <Dialog open={open} title="Edit profile" onClose={onClose} width={460}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <Avatar name={v.name || '?'} photoUrl={photoUrl} size={64} />
        <div style={{ display: 'flex', gap: 8 }}>
          <Button size="sm" onClick={() => fileRef.current?.click()} disabled={busy}>
            Change picture
          </Button>
          {photoUrl ? (
            <Button size="sm" variant="ghost" onClick={onRemovePhoto} disabled={busy}>
              Remove
            </Button>
          ) : null}
        </div>
        <input
          ref={fileRef}
          type="file"
          accept="image/png,image/jpeg,image/webp,image/gif"
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) onPickPhoto(file)
            e.target.value = ''
          }}
        />
      </div>
      <Field id={ids.name} label="Display name">
        <input id={ids.name} className="field" maxLength={40} value={v.name} onChange={(e) => setV({ ...v, name: e.target.value })} />
      </Field>
      <Field id={ids.username} label="Username">
        <input
          id={ids.username}
          className="field"
          maxLength={20}
          value={v.username}
          onChange={(e) => setV({ ...v, username: e.target.value.toLowerCase().replace(/[^a-z0-9._]/g, '') })}
        />
      </Field>
      <Field id={ids.bio} label="Bio">
        <textarea
          id={ids.bio}
          className="field"
          style={{ height: 72, padding: '12px 16px', resize: 'none' }}
          maxLength={160}
          placeholder="A line about you (optional)"
          value={v.bio}
          onChange={(e) => setV({ ...v, bio: e.target.value })}
        />
      </Field>
      <Field id={ids.dob} label="Date of birth" hint={dobProblem ?? 'Private. Friends and other people never see it.'} problem={dobProblem !== null}>
        <input
          id={ids.dob}
          className="field"
          type="date"
          min="1920-01-01"
          max={wireBirthDate(today)}
          value={v.birthDate}
          onChange={(e) => setV({ ...v, birthDate: e.target.value })}
        />
      </Field>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button variant="primary" disabled={!canSave} onClick={() => onSave(v)}>
          {busy ? 'Saving…' : 'Save'}
        </Button>
      </div>
    </Dialog>
  )
}

function Field({ id, label, hint, problem = false, children }: { id: string; label: string; hint?: string; problem?: boolean; children: JSX.Element }): JSX.Element {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <label htmlFor={id} className="section-label">
        {label}
      </label>
      {children}
      {hint ? (
        <span className="subtle" style={problem ? { color: 'var(--accent-text)' } : undefined}>
          {hint}
        </span>
      ) : null}
    </div>
  )
}
