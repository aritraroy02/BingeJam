import type { ButtonHTMLAttributes, CSSProperties, JSX, ReactNode } from 'react'

// Muted warm tones for initials avatars (same five as the Android app), picked from a stable hash
// of the user id so a friend always gets the same one. Photos carry the colour; these stay quiet.
const AVATAR_TONES = ['#7A2E2A', '#7A5A24', '#4D5A2E', '#2F4F55', '#5B3A4A'] as const

// A lighter version of each tone, for a sender's name above their bubble in group chats.
const AVATAR_TINTS = ['#E3A39D', '#E2C28C', '#BCC896', '#9CC1C7', '#D3A9BC'] as const

function hash(text: string): number {
  let h = 0
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0
  return Math.abs(h)
}

/** The text colour that goes with a person's avatar (sender names in group chats). */
export function avatarTint(id: string): string {
  return AVATAR_TINTS[hash(id) % AVATAR_TINTS.length]
}

/** One letter for a person (like the design: "R"), two for a group ("College Gang" → "CG"). */
export function initials(name: string, letters: 1 | 2 = 1): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  if (letters === 1 || parts.length === 1) return parts[0].slice(0, 1).toUpperCase()
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
}

export function Avatar({
  name,
  id,
  photoUrl,
  size = 44,
  online = false,
  ring = false,
  group = false,
  muted = false,
  dnd = false
}: {
  name: string
  id?: string
  photoUrl?: string | null
  size?: number
  online?: boolean
  /** Accent ring for someone watching or listening right now. */
  ring?: boolean
  /** Group chats show two initials. */
  group?: boolean
  /** Offline people in member lists: plain grey with two initials (design: Ananya "AI"). */
  muted?: boolean
  /** Online but Do not disturb: the dot turns red. */
  dnd?: boolean
}): JSX.Element {
  const tone = AVATAR_TONES[hash(id ?? name) % AVATAR_TONES.length]
  const style = {
    '--size': `${size}px`,
    backgroundImage: photoUrl ? `url("${photoUrl}")` : 'none',
    backgroundColor: photoUrl ? undefined : muted ? 'var(--raised-2)' : tone
  } as CSSProperties
  const avatar = (
    <span className="avatar" style={style} aria-hidden="true">
      {photoUrl ? null : initials(name, group || muted ? 2 : 1)}
      {online ? <span className={dnd ? 'avatar__status avatar__status--dnd' : 'avatar__status'} /> : null}
    </span>
  )
  return ring ? <span className="avatar-ring">{avatar}</span> : avatar
}

type Variant = 'default' | 'primary' | 'live' | 'danger' | 'ghost'

export function Button({
  variant = 'default',
  size,
  icon = false,
  className = '',
  children,
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant
  /** xs = the design's 34px Ping buttons, sm = 36px, lg = 48px (default 46px). */
  size?: 'xs' | 'sm' | 'lg'
  icon?: boolean
  children?: ReactNode
}): JSX.Element {
  const classes = [
    'btn',
    'pressable',
    variant !== 'default' ? `btn--${variant}` : '',
    size ? `btn--${size}` : '',
    icon ? 'btn--icon' : '',
    className
  ]
  return (
    <button type="button" className={classes.filter(Boolean).join(' ')} {...rest}>
      {children}
    </button>
  )
}

export function Badge({ count }: { count: number }): JSX.Element | null {
  if (count <= 0) return null
  return <span className="badge">{count > 99 ? '99+' : count}</span>
}

/** Signature mark: two friend dots (red, orange) for anything live or in sync, like the phone app. */
export function SyncDots(): JSX.Element {
  return (
    <span className="sync-dots" aria-hidden="true">
      <i />
      <i />
    </span>
  )
}

/** One status dot: online (green), streaming (red) or, inside a warning chip, amber. */
export function LiveDot({ red = false, small = false }: { red?: boolean; small?: boolean }): JSX.Element {
  const classes = ['live-dot', red ? 'live-dot--red' : '', small ? 'live-dot--sm' : '']
  return <span className={classes.filter(Boolean).join(' ')} aria-hidden="true" />
}

export function Segmented<T extends string>({
  label,
  options,
  value,
  onChange,
  className = ''
}: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
  /** segmented--lg: the Go Live pickers (40px); segmented--accent: the chosen one in the accent. */
  className?: string
}): JSX.Element {
  return (
    <div role="group" aria-label={label} className={`segmented ${className}`.trim()}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Switch({
  checked,
  onChange,
  label,
  disabled = false
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
  disabled?: boolean
}): JSX.Element {
  return (
    <button type="button" role="switch" aria-checked={checked} aria-label={label} className="switch" disabled={disabled} onClick={() => onChange(!checked)} />
  )
}
