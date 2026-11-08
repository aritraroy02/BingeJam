import { useEffect, useId, useRef, type JSX, type ReactNode } from 'react'
import { Button } from './primitives'

/**
 * Glass dialog on the native <dialog> element: it traps focus, Escape closes it and the rest of the
 * window becomes inert while it's open. A click on the dimmed backdrop closes it too.
 */
export function Dialog({
  open,
  title,
  description,
  onClose,
  children,
  width = 440
}: {
  open: boolean
  title: string
  description?: ReactNode
  onClose: () => void
  children?: ReactNode
  width?: number
}): JSX.Element {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  const descId = useId()

  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (open && !el.open) el.showModal()
    if (!open && el.open) el.close()
  }, [open])

  return (
    <dialog
      ref={ref}
      className="dialog"
      style={{ width: `min(${width}px, calc(100vw - 40px))` }}
      aria-labelledby={titleId}
      aria-describedby={description ? descId : undefined}
      onCancel={(e) => {
        e.preventDefault()
        onClose()
      }}
      onMouseDown={(e) => {
        if (e.target === ref.current) onClose()
      }}
    >
      {open ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <h2 id={titleId} className="h3" style={{ fontSize: 19 }}>
            {title}
          </h2>
          {description ? (
            <p id={descId} className="muted" style={{ margin: 0, lineHeight: 1.5 }}>
              {description}
            </p>
          ) : null}
          {children}
        </div>
      ) : null}
    </dialog>
  )
}

/** "Delete this chat?" and other yes/no questions. */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  danger = false,
  onConfirm,
  onClose
}: {
  open: boolean
  title: string
  description: ReactNode
  confirmLabel: string
  danger?: boolean
  onConfirm: () => void
  onClose: () => void
}): JSX.Element {
  return (
    <Dialog open={open} title={title} description={description} onClose={onClose}>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 6 }}>
        <Button onClick={onClose} autoFocus>
          Cancel
        </Button>
        <Button
          variant={danger ? 'danger' : 'primary'}
          onClick={() => {
            onConfirm()
            onClose()
          }}
        >
          {confirmLabel}
        </Button>
      </div>
    </Dialog>
  )
}
