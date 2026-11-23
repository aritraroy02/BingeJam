import { useEffect, useRef, useState, type JSX } from 'react'
import { Icon } from '@/ui/Icon'
import { Button, Segmented, Switch } from '@/ui/primitives'
import { Dialog } from '@/ui/Dialog'
import type { ShareSourceVM } from '@/ui/models'

// Go Live in a voice channel (2026-11-22): the picker (a screen or an app window, quality, sound),
// the video element for a share, and the LIVE badge.

export type ShareQuality = '720p30' | '1080p60' | '1440p60' | '4k60'

const QUALITIES: { value: ShareQuality; label: string; mbps: number }[] = [
  { value: '720p30', label: '720p · 30', mbps: 2.5 },
  { value: '1080p60', label: '1080p · 60', mbps: 8 },
  { value: '1440p60', label: '1440p · 60', mbps: 14 },
  { value: '4k60', label: '4K · 60', mbps: 25 }
]

export function qualityLabel(q: ShareQuality): string {
  return QUALITIES.find((x) => x.value === q)?.label ?? q
}

export function LiveBadge(): JSX.Element {
  return <span className="live-badge">Live</span>
}

/** A MediaStream in a <video>; muted for our own preview and while deafened. */
export function StreamVideo({ stream, muted, className }: { stream: MediaStream; muted: boolean; className?: string }): JSX.Element {
  const ref = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (el.srcObject !== stream) el.srcObject = stream
    void el.play().catch(() => undefined)
  }, [stream])
  useEffect(() => {
    if (ref.current) ref.current.muted = muted
  }, [muted])
  return <video ref={ref} className={className} autoPlay playsInline muted={muted} />
}

/** The share being watched, filling the stage, with fullscreen and Stop watching. */
export function StreamView({
  name,
  stream,
  deafened,
  onStop
}: {
  name: string
  stream: MediaStream | null
  deafened: boolean
  onStop: () => void
}): JSX.Element {
  const box = useRef<HTMLDivElement>(null)
  return (
    <div className="stream-view" ref={box}>
      {stream ? <StreamVideo stream={stream} muted={deafened} className="stream-view__video" /> : <p className="stream-view__wait">Connecting to {name}’s screen…</p>}
      <div className="stream-view__bar">
        <span className="stream-view__who">
          <LiveBadge />
          {name}
        </span>
        <span style={{ flex: 1 }} />
        <button type="button" className="dock-btn stream-view__btn pressable" aria-label="Full screen" title="Full screen" onClick={() => void box.current?.requestFullscreen().catch(() => undefined)}>
          <Icon name="fullscreen" size={16} />
        </button>
        <button type="button" className="dock-btn stream-view__btn pressable" onClick={onStop}>
          Stop watching
        </button>
      </div>
    </div>
  )
}

/** Pick what to share, Discord style: screens or app windows, quality, and the PC's sound. */
export function ShareDialog({
  open,
  onClose,
  listSources,
  onStart
}: {
  open: boolean
  onClose: () => void
  listSources: () => Promise<ShareSourceVM[]>
  onStart: (sourceId: string, quality: ShareQuality, sound: boolean) => void
}): JSX.Element {
  const [sources, setSources] = useState<ShareSourceVM[] | null>(null)
  const [tab, setTab] = useState<'window' | 'screen'>('window')
  const [picked, setPicked] = useState<string | null>(null)
  const [quality, setQuality] = useState<ShareQuality>('1080p60')
  const [sound, setSound] = useState(true)

  useEffect(() => {
    if (!open) return
    setSources(null)
    setPicked(null)
    let live = true
    void listSources().then(
      (list) => {
        if (!live) return
        setSources(list)
        // No app windows to offer: start on Screens.
        if (!list.some((s) => s.kind === 'window' && !s.blocked)) setTab('screen')
      },
      () => live && setSources([])
    )
    return () => {
      live = false
    }
  }, [open])

  const shown = (sources ?? []).filter((s) => s.kind === tab)
  const mbps = QUALITIES.find((q) => q.value === quality)?.mbps ?? 8

  return (
    <Dialog open={open} title="Share your screen" description="Everyone in this voice channel on a PC can watch. It goes straight from your PC to theirs." onClose={onClose} width={720}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <Segmented
          label="What to share"
          options={[
            { value: 'window', label: 'Applications' },
            { value: 'screen', label: 'Screens' }
          ]}
          value={tab}
          onChange={(v) => {
            setTab(v)
            setPicked(null)
          }}
        />
        {sources === null ? (
          <p className="muted small">Looking for screens and windows…</p>
        ) : shown.length === 0 ? (
          <p className="muted small">{tab === 'window' ? 'No app windows open.' : 'No screens found.'}</p>
        ) : (
          <ul className="share-grid" aria-label={tab === 'window' ? 'Applications' : 'Screens'}>
            {shown.map((s) => (
              <li key={s.id}>
                <button
                  type="button"
                  className="share-card pressable"
                  aria-pressed={picked === s.id}
                  disabled={s.blocked}
                  title={s.blocked ? 'Netflix, Prime Video and JioHotstar show black when shared. Start a room to watch together instead.' : s.name}
                  onClick={() => setPicked(s.id)}
                  onDoubleClick={() => {
                    if (s.blocked) return
                    onStart(s.id, quality, sound)
                    onClose()
                  }}
                >
                  <img className="share-card__thumb" src={s.thumbnail} alt="" />
                  <span className="share-card__name">
                    {s.icon ? <img src={s.icon} alt="" width={16} height={16} /> : <Icon name={s.kind === 'screen' ? 'screen' : 'window'} size={16} />}
                    <span className="truncate">{s.name}</span>
                  </span>
                  {s.blocked ? <span className="share-card__note">Shows black when shared. Watch it in a room</span> : null}
                </button>
              </li>
            ))}
          </ul>
        )}
        <div className="share-options">
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8, minWidth: 0 }}>
            <span className="section-label">Quality</span>
            <Segmented label="Quality" options={QUALITIES.map((q) => ({ value: q.value, label: q.label }))} value={quality} onChange={setQuality} />
            <span className="subtle">About {mbps} Mbps of upload for each person watching</span>
          </div>
          <label className="setting-switch share-sound">
            <span style={{ display: 'flex', flexDirection: 'column' }}>
              <span>Share sound</span>
              <span className="subtle">Your PC’s sound, without the call</span>
            </span>
            <Switch label="Share sound" checked={sound} onChange={setSound} />
          </label>
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button
            variant="primary"
            disabled={!picked}
            onClick={() => {
              if (!picked) return
              onStart(picked, quality, sound)
              onClose()
            }}
          >
            <Icon name="screen" />
            Go Live
          </Button>
        </div>
      </div>
    </Dialog>
  )
}
