import { useEffect, useId, useRef, useState, type JSX, type KeyboardEvent } from 'react'
import { Icon, type IconName } from './Icon'

export interface MenuItem {
  label: string
  icon?: IconName
  danger?: boolean
  onSelect: () => void
}

/**
 * The ⋮ button with a small glass menu under it. Arrow keys move, Escape closes and returns focus,
 * a click outside closes.
 */
export function MenuButton({ label, items }: { label: string; items: MenuItem[] }): JSX.Element {
  const [open, setOpen] = useState(false)
  const id = useId()
  const root = useRef<HTMLDivElement>(null)
  const button = useRef<HTMLButtonElement>(null)
  const list = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    list.current?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
    const onDown = (e: MouseEvent): void => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDown)
    return () => document.removeEventListener('mousedown', onDown)
  }, [open])

  function close(): void {
    setOpen(false)
    button.current?.focus()
  }

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>): void {
    const options = Array.from(list.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') ?? [])
    const at = options.indexOf(document.activeElement as HTMLButtonElement)
    if (e.key === 'Escape') {
      e.preventDefault()
      close()
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault()
      const next = (at + (e.key === 'ArrowDown' ? 1 : -1) + options.length) % options.length
      options[next]?.focus()
    } else if (e.key === 'Tab') {
      setOpen(false)
    }
  }

  return (
    <div ref={root} style={{ position: 'relative' }}>
      <button
        ref={button}
        type="button"
        className="btn btn--icon pressable"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? id : undefined}
        onClick={() => setOpen((v) => !v)}
      >
        <Icon name="more" size={20} />
      </button>
      {open ? (
        <div ref={list} id={id} role="menu" aria-label={label} className="menu" onKeyDown={onKeyDown}>
          {items.map((item) => (
            <button
              key={item.label}
              type="button"
              role="menuitem"
              className={item.danger ? 'menu__item menu__item--danger' : 'menu__item'}
              onClick={() => {
                setOpen(false)
                item.onSelect()
              }}
            >
              {item.icon ? <Icon name={item.icon} size={18} /> : null}
              {item.label}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
