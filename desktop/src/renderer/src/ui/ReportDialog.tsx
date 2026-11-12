import { useEffect, useId, useState, type JSX } from 'react'
import { Dialog } from './Dialog'
import { Button } from './primitives'

export type ReportReasonValue = 'spam' | 'harassment' | 'inappropriate' | 'impersonation' | 'other'

const REASONS: { value: ReportReasonValue; label: string }[] = [
  { value: 'spam', label: 'Spam or scam' },
  { value: 'harassment', label: 'Harassment or bullying' },
  { value: 'inappropriate', label: 'Nudity or violent content' },
  { value: 'impersonation', label: 'Pretending to be someone else' },
  { value: 'other', label: 'Something else' }
]

/**
 * Report a person or a group (same as the phone, 2026-11-10): a reason, optional details and
 * "also block". [evidenceNote] says what's sent with it, so nothing leaves the PC unannounced.
 */
export function ReportDialog({
  open,
  title,
  evidenceNote,
  blockLabel,
  onClose,
  onSubmit
}: {
  open: boolean
  title: string
  evidenceNote: string
  blockLabel: string
  onClose: () => void
  onSubmit: (reason: ReportReasonValue, details: string | null, block: boolean) => void
}): JSX.Element {
  const [reason, setReason] = useState<ReportReasonValue | null>(null)
  const [details, setDetails] = useState('')
  const [block, setBlock] = useState(true)
  const name = useId()
  const detailsId = useId()

  // Fresh every time it opens.
  useEffect(() => {
    if (!open) return
    setReason(null)
    setDetails('')
    setBlock(true)
  }, [open])

  return (
    <Dialog open={open} title={title} description="Why are you reporting this?" onClose={onClose} width={460}>
      <div role="radiogroup" aria-label="Reason" style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        {REASONS.map((r) => (
          <label key={r.value} className="pick-row">
            <input type="radio" name={name} checked={reason === r.value} onChange={() => setReason(r.value)} />
            <span>{r.label}</span>
          </label>
        ))}
      </div>
      <label htmlFor={detailsId} className="sr-only">
        Details
      </label>
      <textarea
        id={detailsId}
        className="field"
        style={{ height: 88, padding: '12px 16px', resize: 'none' }}
        placeholder="Add details (optional)"
        maxLength={500}
        value={details}
        onChange={(e) => setDetails(e.target.value)}
      />
      <label className="pick-row">
        <input type="checkbox" checked={block} onChange={(e) => setBlock(e.target.checked)} />
        <span>{blockLabel}</span>
      </label>
      <p className="subtle" style={{ margin: 0, lineHeight: 1.5 }}>
        {evidenceNote}
      </p>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="danger"
          disabled={reason === null}
          onClick={() => {
            if (!reason) return
            onSubmit(reason, details.trim() || null, block)
            onClose()
          }}
        >
          Report
        </Button>
      </div>
    </Dialog>
  )
}
