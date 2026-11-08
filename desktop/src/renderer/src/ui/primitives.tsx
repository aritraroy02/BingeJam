import type { ButtonHTMLAttributes, CSSProperties, JSX, ReactNode } from 'react'

// Avatar colours from the design, picked from a stable hash of the user id so a friend
// always gets the same colour (like the Android app's initials avatars).
const AVATAR_GRADIENTS = [
  ['#34D399', '#059669'],
  ['#FB923C', '#EA580C'],
  ['#EC4899', '#BE185D'],
  ['#818CF8', '#4F46E5'],
  ['#F472B6', '#DB2777'],
  ['#38BDF8', '#0284C7'],
  ['#FBBF24', '#D97706'],
  ['#A78BFA', '#7C3AED']
] as const

function hash(text: string): number {
  let h = 0
  for (let i = 0; i < text.length; i++) h = (h * 31 + text.charCodeAt(i)) | 0
  return Math.abs(h)
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
  group = false
}: {
  name: string
  id?: string
  photoUrl?: string | null
  size?: number
  online?: boolean
  /** Gradient ring for someone watching or listening right now. */
  ring?: boolean
  /** Group chats show two initials. */
  group?: boolean
}): JSX.Element {
  const [from, to] = AVATAR_GRADIENTS[hash(id ?? name) % AVATAR_GRADIENTS.length]
  const style = {
    '--size': `${size}px`,
    backgroundImage: photoUrl ? `url("${photoUrl}")` : `linear-gradient(140deg, ${from}, ${to})`
  } as CSSProperties
  const avatar = (
    <span className="avatar" style={style} aria-hidden="true">
      {photoUrl ? null : initials(name, group ? 2 : 1)}
      {online ? <span className="avatar__status" /> : null}
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
  size?: 'sm' | 'lg'
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

export function LiveDot({ red = false }: { red?: boolean }): JSX.Element {
  return <span className={red ? 'live-dot live-dot--red' : 'live-dot'} aria-hidden="true" />
}

export function Segmented<T extends string>({
  label,
  options,
  value,
  onChange
}: {
  label: string
  options: { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}): JSX.Element {
  return (
    <div role="group" aria-label={label} className="segmented">
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Switch({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }): JSX.Element {
  return <button type="button" role="switch" aria-checked={checked} aria-label={label} className="switch" onClick={() => onChange(!checked)} />
}
