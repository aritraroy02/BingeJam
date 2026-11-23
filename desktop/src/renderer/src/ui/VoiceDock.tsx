import type { JSX } from 'react'
import { Icon } from './Icon'
import type { VoiceConnectionVM } from './models'

// "Voice connected" at the bottom of the sidebar on every screen, like Discord: where you're
// talking, the voice server's city with your ping, and mute / deafen / hang up.

const STATUS_LABEL: Record<VoiceConnectionVM['status'], string> = {
  connecting: 'Connecting…',
  connected: 'Voice connected',
  reconnecting: 'Reconnecting…',
  failed: 'Voice disconnected'
}

/** "Mumbai · 28 ms" (what the user asked for), or what we know so far. */
export function serverLine(v: VoiceConnectionVM): string {
  if (v.status !== 'connected') return v.server ? v.server : 'Finding a voice server'
  if (v.server && v.pingMs !== null) return `${v.server} · ${v.pingMs} ms`
  if (v.server) return v.server
  return v.pingMs !== null ? `${v.pingMs} ms` : 'Measuring ping'
}

/** The tooltip: every detail we have. */
export function serverDetail(v: VoiceConnectionVM): string {
  const lines = [
    v.server ? `Voice server: ${v.server}${v.serverCode && v.serverCode !== v.server ? ` (${v.serverCode})` : ''}` : 'Voice server: not known yet',
    v.edge ? `You connect through ${v.edge}` : null,
    v.pingMs !== null ? `Round trip ${v.pingMs} ms` : null
  ]
  return lines.filter(Boolean).join('\n')
}

export function SignalBars({ quality, size = 16 }: { quality: VoiceConnectionVM['quality']; size?: number }): JSX.Element {
  return (
    <span className={`signal signal--${quality ?? 'none'}`} aria-hidden="true">
      <Icon name="signal" size={size} strokeWidth={2} />
    </span>
  )
}

export function VoiceDock({
  voice: v,
  onOpen,
  onToggleMute,
  onToggleDeafen,
  onLeave,
  compact = false
}: {
  voice: VoiceConnectionVM
  onOpen: () => void
  onToggleMute: () => void
  onToggleDeafen: () => void
  onLeave: () => void
  /** The icon rail: a column of icon buttons. */
  compact?: boolean
}): JSX.Element {
  const where = `${v.channelName} / ${v.placeName}`
  const controls = (
    <>
      <button
        type="button"
        className="dock-btn pressable"
        aria-pressed={v.muted}
        aria-label={v.muted ? 'Unmute' : 'Mute'}
        title={v.muted ? 'Unmute' : 'Mute'}
        onClick={onToggleMute}
      >
        <Icon name={v.muted ? 'micOff' : 'mic'} size={16} />
      </button>
      <button
        type="button"
        className="dock-btn pressable"
        aria-pressed={v.deafened}
        aria-label={v.deafened ? 'Undeafen' : 'Deafen'}
        title={v.deafened ? 'Undeafen' : 'Deafen'}
        onClick={onToggleDeafen}
      >
        <Icon name={v.deafened ? 'headphonesOff' : 'headphones'} size={16} />
      </button>
      <button type="button" className="dock-btn dock-btn--leave pressable" aria-label="Disconnect" title="Disconnect" onClick={onLeave}>
        <Icon name="hangUp" size={16} />
      </button>
    </>
  )

  if (compact) {
    return (
      <div className="voice-dock voice-dock--rail" role="region" aria-label={`${STATUS_LABEL[v.status]}: ${where}`}>
        <button type="button" className="dock-btn pressable" title={`${STATUS_LABEL[v.status]}\n${where}\n${serverDetail(v)}`} aria-label={`Open ${where}`} onClick={onOpen}>
          <SignalBars quality={v.status === 'connected' ? v.quality : null} />
        </button>
        {controls}
      </div>
    )
  }

  return (
    <div className="voice-dock" role="region" aria-label="Voice">
      <div className="voice-dock__top">
        <span className="voice-dock__status" title={serverDetail(v)}>
          <SignalBars quality={v.status === 'connected' ? v.quality : null} />
          <span className={v.status === 'connected' ? 'voice-dock__label voice-dock__label--on' : 'voice-dock__label'}>{STATUS_LABEL[v.status]}</span>
        </span>
      </div>
      <button type="button" className="voice-dock__where truncate" title={`Open ${where}`} onClick={onOpen}>
        {where}
      </button>
      <span className="voice-dock__server" title={serverDetail(v)}>
        {serverLine(v)}
      </span>
      <div className="voice-dock__controls">{controls}</div>
    </div>
  )
}
