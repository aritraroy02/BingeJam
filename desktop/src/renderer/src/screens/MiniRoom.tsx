import { useEffect, useRef, useState, type FormEvent, type JSX } from 'react'
import type { MiniRoomState } from '@shared/desktop'
import { useAppearance } from '@/live/appearance'
import { Icon } from '@/ui/Icon'
import { ROOM_REACTIONS } from './Room'

// The always-on-top mini room window (src/main/mini.ts): the room's sync line, play/pause, who's
// behind, reactions and room chat. It holds no room state of its own: the main window sends
// MiniRoomState on every change and gets button presses back.

function clock(ms: number | null): string {
  if (ms === null || !Number.isFinite(ms)) return '--:--'
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

export function MiniRoom(): JSX.Element {
  useAppearance() // follow theme changes made in the main window
  const [state, setState] = useState<MiniRoomState | null>(null)
  const [draft, setDraft] = useState('')
  const list = useRef<HTMLDivElement>(null)
  const act = window.bingejam.mini.action

  useEffect(() => window.bingejam.mini.onState(setState), [])
  useEffect(() => {
    list.current?.scrollTo({ top: list.current.scrollHeight })
  }, [state?.chat.length])

  function send(e: FormEvent): void {
    e.preventDefault()
    const text = draft.trim()
    if (!text) return
    act({ kind: 'send', text })
    setDraft('')
  }

  return (
    <div className="mini">
      <header className="mini-head">
        <div style={{ minWidth: 0, flex: 1 }}>
          <div className="mini-title truncate">{state?.title ?? 'Room'}</div>
          <div className={state?.allInSync ? 'mini-sync mini-sync--ok' : 'mini-sync'}>{state?.syncLabel || 'Waiting for the room'}</div>
        </div>
        <button type="button" className="mini-icon" aria-label="Open BingeJam" title="Open BingeJam" onClick={() => act({ kind: 'open' })}>
          <Icon name="external" size={16} />
        </button>
        <button type="button" className="mini-icon" aria-label="Close mini window" title="Close" onClick={() => window.bingejam.mini.open(false)}>
          <Icon name="close" size={16} />
        </button>
      </header>

      <div className="mini-transport">
        <button type="button" className="mini-icon" aria-label="Back 10 seconds" onClick={() => act({ kind: 'seekBy', deltaMs: -10_000 })} disabled={!state?.hasTab}>
          <Icon name="back10" size={18} />
        </button>
        <button type="button" className="mini-play" aria-label={state?.playing ? 'Pause for everyone' : 'Play for everyone'} onClick={() => act({ kind: 'togglePlay' })}>
          <Icon name={state?.playing ? 'pause' : 'play'} size={18} />
        </button>
        <button type="button" className="mini-icon" aria-label="Forward 10 seconds" onClick={() => act({ kind: 'seekBy', deltaMs: 10_000 })} disabled={!state?.hasTab}>
          <Icon name="fwd10" size={18} />
        </button>
        <span className="mini-time">
          {clock(state?.positionMs ?? null)} / {clock(state?.durationMs ?? null)}
        </span>
        {state?.inVoice ? (
          <button type="button" className="mini-icon" aria-label={state.muted ? 'Unmute' : 'Mute'} aria-pressed={state.muted} onClick={() => act({ kind: 'toggleMute' })}>
            <Icon name={state.muted ? 'micOff' : 'mic'} size={16} />
          </button>
        ) : null}
        <button type="button" className="mini-text-btn" onClick={() => act({ kind: 'syncToMe' })} disabled={!state?.hasTab}>
          Sync to me
        </button>
      </div>

      <ul className="mini-members" aria-label="In the room">
        {(state?.members ?? []).map((m) => (
          <li key={m.id} className={`mini-member mini-member--${m.health}`}>
            <span className="truncate">{m.isMe ? 'You' : m.name}</span>
            <span className="mini-member-label">{m.label}</span>
          </li>
        ))}
      </ul>

      <div className="mini-chat" ref={list} aria-label="Room chat" role="log">
        {(state?.chat ?? []).length === 0 ? <p className="muted small" style={{ margin: 0 }}>No messages yet.</p> : null}
        {(state?.chat ?? []).map((c) => (
          <p key={c.id} className={c.fromMe ? 'mini-msg mini-msg--me' : 'mini-msg'}>
            {c.fromMe ? null : <b>{c.name} </b>}
            {c.text}
          </p>
        ))}
      </div>

      <div className="mini-reactions" role="group" aria-label="React">
        {ROOM_REACTIONS.map((e) => (
          <button key={e} type="button" className="reaction-btn" onClick={() => act({ kind: 'react', emoji: e })} aria-label={`React ${e}`}>
            {e}
          </button>
        ))}
      </div>
      <form className="mini-compose" onSubmit={send}>
        <input className="field" placeholder="Message the room" value={draft} onChange={(e) => setDraft(e.target.value)} maxLength={500} aria-label="Message the room" />
      </form>
    </div>
  )
}
