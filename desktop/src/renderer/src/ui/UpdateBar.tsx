import { useState, type JSX } from 'react'
import type { UpdateState } from '@shared/update'
import { Icon } from './Icon'
import { Button } from './primitives'

// "A new version of BingeJam is available" at the top of every screen as soon as the app finds one
// (it downloads in the background meanwhile). Update restarts into it, at once if the download is done,
// else as soon as it is. Later hides the bar for that version until the next start.

export function UpdateBar({ state, onInstall }: { state: UpdateState; onInstall: () => void }): JSX.Element | null {
  const [later, setLater] = useState<string | null>(null)
  const [pressed, setPressed] = useState<string | null>(null)
  if (state.status !== 'downloading' && state.status !== 'ready') return null
  if (later === state.version) return null
  const updating = pressed === state.version
  const percent = state.status === 'downloading' ? Math.round(state.progress * 100) : 100
  return (
    <div className="update-bar" role="status" aria-live="polite">
      <Icon name="download" size={16} />
      <span className="update-bar__text">
        A new version of BingeJam is available <span className="update-bar__version">{state.version}</span>
      </span>
      {state.status === 'downloading' && !updating ? <span className="update-bar__progress tabular">{percent}%</span> : null}
      <Button
        size="sm"
        variant="primary"
        disabled={updating}
        onClick={() => {
          setPressed(state.version)
          onInstall()
        }}
      >
        {updating ? (state.status === 'ready' ? 'Restarting…' : `Updating… ${percent}%`) : 'Update'}
      </Button>
      {updating ? null : (
        <Button size="sm" variant="ghost" onClick={() => setLater(state.version)}>
          Later
        </Button>
      )}
    </div>
  )
}

/** Settings → About: the version row's right side. */
export function UpdateStatus({ state, onCheck, onInstall }: { state: UpdateState; onCheck: () => void; onInstall: () => void }): JSX.Element {
  switch (state.status) {
    case 'checking':
      return <span className="muted small">Checking…</span>
    case 'downloading':
      return (
        <span style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span className="muted small tabular">
            {state.version} available · {Math.round(state.progress * 100)}%
          </span>
          <Button size="sm" variant="primary" onClick={onInstall}>
            Update
          </Button>
        </span>
      )
    case 'ready':
      return (
        <Button size="sm" variant="primary" onClick={onInstall}>
          Update to {state.version}
        </Button>
      )
    case 'current':
      return (
        <span style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span className="muted small">Up to date</span>
          <Button size="sm" onClick={onCheck}>
            Check again
          </Button>
        </span>
      )
    case 'error':
      return (
        <span style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <span className="muted small">{state.message}</span>
          <Button size="sm" onClick={onCheck}>
            Try again
          </Button>
        </span>
      )
    default:
      return (
        <Button size="sm" onClick={onCheck}>
          Check for updates
        </Button>
      )
  }
}
