import { useEffect, useId, useState, type CSSProperties, type JSX, type ReactNode } from 'react'
import { BRAND, LINKS } from '@shared/brand'
import { Icon } from '@/ui/Icon'
import { Avatar, Button, LiveDot, Switch } from '@/ui/primitives'
import type { SettingsVM } from '@/ui/models'

// Settings (not in the mock-ups; same glass language as the other screens).

export interface SettingsProps {
  settings: SettingsVM
  onSignOut: () => void
  onOpenBrowserSetup: () => void
  /** null = system default device. */
  onInputDevice: (id: string | null) => void
  onOutputDevice: (id: string | null) => void
  /** The key's label ("V", "F13"); null = open mic (no push-to-talk). */
  onPushToTalkKey: (key: string | null) => void
  onStartWithWindows: (on: boolean) => void
  onEditProfile?: () => void
  onViewPhoto?: () => void
}

/** The OS's "Animation effects" switch, live (motion in the app follows it; see global.css). */
function useReducedMotion(): boolean {
  const query = '(prefers-reduced-motion: reduce)'
  const [reduced, setReduced] = useState(() => window.matchMedia(query).matches)
  useEffect(() => {
    const mq = window.matchMedia(query)
    const on = (): void => setReduced(mq.matches)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [])
  return reduced
}

export function Settings(p: SettingsProps): JSX.Element {
  const s = p.settings
  const reduced = useReducedMotion()
  return (
    <div className="screen" style={{ flexDirection: 'column' }}>
      <div className="scroll" style={{ flex: 1, margin: -6, padding: 8, display: 'flex', flexWrap: 'wrap', alignContent: 'flex-start', gap: 20 }}>
        <div style={{ flex: '999 1 480px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20 }}>
          <section aria-labelledby="h-account" className="glass glass--hero glass--raised rise settings-account" style={{ '--i': 1 } as CSSProperties}>
            <button
              type="button"
              className="person-open"
              disabled={!s.account.photoUrl || !p.onViewPhoto}
              aria-label={s.account.photoUrl ? 'View your profile picture' : undefined}
              onClick={p.onViewPhoto}
            >
              <Avatar name={s.account.name} id={s.account.uid} photoUrl={s.account.photoUrl} size={72} online />
            </button>
            <div style={{ flex: '1 1 220px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span className="chip" style={{ marginBottom: 8 }}>
                Account
              </span>
              <h1 id="h-account" className="h2 truncate" style={{ fontSize: 28, letterSpacing: -0.4 }}>
                {s.account.name}
              </h1>
              <span className="muted selectable">
                {s.account.username ? `@${s.account.username}` : 'No username yet'}
                {s.account.email ? ` · ${s.account.email}` : ''}
              </span>
            </div>
            {p.onEditProfile ? <Button onClick={p.onEditProfile}>Edit profile</Button> : null}
            <Button variant="danger" onClick={p.onSignOut}>
              <Icon name="logout" size={18} />
              Sign out
            </Button>
          </section>

          <Panel id="h-voice-set" title="Voice" i={2}>
            <DeviceRow label="Microphone" options={s.voice.inputs} value={s.voice.inputId} onChange={p.onInputDevice} />
            <DeviceRow label="Speakers" options={s.voice.outputs} value={s.voice.outputId} onChange={p.onOutputDevice} />
            <Row
              title="Push to talk"
              detail={s.voice.pushToTalkKey ? `Hold ${s.voice.pushToTalkKey} to talk in rooms.` : 'Off: your mic is open while you are unmuted.'}
            >
              <KeyRecorder value={s.voice.pushToTalkKey} onChange={p.onPushToTalkKey} />
            </Row>
          </Panel>
        </div>

        <div style={{ flex: '1 1 380px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20 }}>
          <Panel id="h-ext" title="Browser extension" i={2}>
            <Row
              title={
                <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  {s.extension.connected ? <LiveDot /> : null}
                  {s.extension.connected ? 'Connected' : 'Not added yet'}
                </span>
              }
              detail={s.extension.connected ? s.extension.detail : 'Precise movie sync for Netflix, Prime Video and JioHotstar in your browser.'}
              first
            >
              <Button size="sm" onClick={p.onOpenBrowserSetup}>
                {s.extension.connected ? 'Manage' : 'Add to browser'}
                <Icon name="chevronRight" size={16} />
              </Button>
            </Row>
          </Panel>

          <Panel id="h-app" title="App" i={3}>
            <Row title="Start with Windows" detail="Opens in the tray when you sign in to Windows, so friends can reach you." first>
              <Switch
                checked={s.startWithWindows === true}
                onChange={p.onStartWithWindows}
                label="Start with Windows"
                disabled={s.startWithWindows === null}
              />
            </Row>
            <Row title="Reduced motion" detail="Follows Windows: Settings → Accessibility → Visual effects → Animation effects.">
              <span className="tag">{reduced ? 'On' : 'Off'}</span>
            </Row>
          </Panel>

          <Panel id="h-about" title="About" i={4}>
            <Row title={BRAND.name} detail={`Version ${s.version}`} first>
              <span className="muted small">{BRAND.tagline}</span>
            </Row>
            <div style={{ display: 'flex', gap: 16, paddingTop: 4 }}>
              <a href={LINKS.terms} target="_blank" rel="noreferrer">
                Terms
              </a>
              <a href={LINKS.privacy} target="_blank" rel="noreferrer">
                Privacy Policy
              </a>
            </div>
            <p className="subtle" style={{ margin: 0, lineHeight: 1.5 }}>
              {BRAND.name} isn't affiliated with Netflix, JioHotstar, Amazon, Spotify or YouTube.
            </p>
          </Panel>
        </div>
      </div>
    </div>
  )
}

function Panel({ id, title, i, children }: { id: string; title: string; i: number; children: ReactNode }): JSX.Element {
  return (
    <section aria-labelledby={id} className="glass rise" style={{ '--i': i, padding: 24, display: 'flex', flexDirection: 'column', gap: 12 } as CSSProperties}>
      <h2 id={id} className="h3" style={{ marginBottom: 4 }}>
        {title}
      </h2>
      {children}
    </section>
  )
}

function Row({ title, detail, children, first = false }: { title: ReactNode; detail?: ReactNode; children?: ReactNode; first?: boolean }): JSX.Element {
  return (
    <div className={first ? 'setting-row setting-row--first' : 'setting-row'}>
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontWeight: 600 }}>{title}</span>
        {detail ? <span className="muted small" style={{ lineHeight: 1.45 }}>{detail}</span> : null}
      </div>
      {children}
    </div>
  )
}

function DeviceRow({
  label,
  options,
  value,
  onChange
}: {
  label: string
  options: { id: string; label: string }[]
  value: string | null
  onChange: (id: string | null) => void
}): JSX.Element {
  const id = useId()
  return (
    <div className="setting-row setting-row--first" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 8, paddingTop: 0 }}>
      <label htmlFor={id} className="section-label">
        {label}
      </label>
      <select id={id} className="field field--select" value={value ?? ''} onChange={(e) => onChange(e.target.value || null)}>
        <option value="">System default</option>
        {options.map((o) => (
          <option key={o.id} value={o.id}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  )
}

/** Click, then press the key to use. Escape cancels, Backspace / Delete turns push-to-talk off. */
function KeyRecorder({ value, onChange }: { value: string | null; onChange: (key: string | null) => void }): JSX.Element {
  const [listening, setListening] = useState(false)
  return (
    <button
      type="button"
      className={listening ? 'btn btn--sm pressable key-btn key-btn--listening' : 'btn btn--sm pressable key-btn'}
      aria-label={listening ? 'Press a key for push to talk' : `Push to talk key: ${value ?? 'off'}. Change`}
      onClick={() => setListening((v) => !v)}
      onBlur={() => setListening(false)}
      onKeyDown={(e) => {
        if (!listening) return
        e.preventDefault()
        if (e.key === 'Escape') setListening(false)
        else if (e.key === 'Backspace' || e.key === 'Delete') {
          onChange(null)
          setListening(false)
        } else if (!['Tab', 'Shift', 'Control', 'Alt', 'Meta'].includes(e.key)) {
          onChange(e.key.length === 1 ? e.key.toUpperCase() : e.code)
          setListening(false)
        }
      }}
    >
      {listening ? 'Press a key…' : (value ?? 'Off')}
    </button>
  )
}
