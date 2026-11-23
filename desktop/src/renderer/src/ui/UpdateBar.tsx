import { useState, type JSX } from 'react'
import type { UpdateState } from '@shared/update'
import { Icon } from './Icon'
import { Button } from './primitives'

// "BingeJam 0.1.3 is ready": shown on every screen once an update has downloaded in the background.
// Restart installs it silently and reopens the app; Later hides it until the next start.

export function UpdateBar({ state, onInstall }: { state: UpdateState; onInstall: () => void }): JSX.Element | null {
  const [later, setLater] = useState<string | null>(null)
  const [installing, setInstalling] = useState(false)
  if (state.status !== 'ready' || later === state.version) return null
  return (
    <div className="update-bar" role="status" aria-live="polite">
      <Icon name="check" size={16} />
      <span className="update-bar__text">
        <b>BingeJam {state.version}</b> is ready
      </span>
      <Button
        size="sm"
        variant="primary"
        disabled={installing}
        onClick={() => {
          setInstalling(true)
          onInstall()
        }}
      >
        {installing ? 'Restarting…' : 'Restart to update'}
      </Button>
      <Button size="sm" variant="ghost" onClick={() => setLater(state.version)}>
        Later
      </Button>
    </div>
  )
}

/** Settings → About: the version row's right side. */
export function UpdateStatus({ state, onCheck, onInstall }: { state: UpdateState; onCheck: () => void; onInstall: () => void }): JSX.Element {
  switch (state.status) {
    case 'checking':
      return <span className="muted small">Checking…</span>
    case 'downloading':
      return <span className="muted small tabular">Downloading {state.version} · {Math.round(state.progress * 100)}%</span>
    case 'ready':
      return (
        <Button size="sm" variant="primary" onClick={onInstall}>
          Restart to update to {state.version}
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
