import { useEffect, useRef, useState, type CSSProperties, type JSX } from 'react'
import { Icon } from '@/ui/Icon'
import { Avatar, Button, Segmented } from '@/ui/primitives'
import { Composer, MessageList } from '@/ui/chat'
import { firstName } from '@/ui/format'
import type { MessageVM, StreamQuality, StreamSource, StreamVM } from '@/ui/models'

// Streams / Go Live (design/5-go-live): someone's game or screen, peer to peer, plus the Go Live
// panel and the stream chat. DRM services (Netflix, Prime Video, JioHotstar) are never shared.

export interface StreamsProps {
  stream: StreamVM
  /** Your uid: your watcher chip says "You". */
  meId: string
  /** The live video once WebRTC delivers it; null shows the design's placeholder art. */
  media: MediaStream | null
  messages: MessageVM[] | null
  onSource: (source: StreamSource) => void
  onQuality: (quality: StreamQuality) => void
  onStartStreaming: () => void
  onStopStreaming: () => void
  onSend: (text: string) => void
  /** 0..1, for the live video (also applied to the <video> element here). */
  onVolume?: (volume: number) => void
}

const SOURCES: { value: StreamSource; label: string }[] = [
  { value: 'game', label: 'Game' },
  { value: 'screen', label: 'Screen' },
  { value: 'window', label: 'App window' }
]
const QUALITIES: { value: StreamQuality; label: string }[] = [
  { value: '1080p60', label: '1080p · 60' },
  { value: '1440p60', label: '1440p · 60' },
  { value: '2160p60', label: '4K · 60' }
]

export function Streams(p: StreamsProps): JSX.Element {
  const s = p.stream
  return (
    <div className="screen">
      <main className="scroll" style={{ flex: '999 1 560px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20, margin: -6, padding: 6 }}>
        <Stage {...p} />
        <section aria-labelledby="h-golive" className="glass glass--strong rise" style={{ '--i': 2, padding: 22, display: 'flex', flexDirection: 'column', gap: 16, boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.14), 0 24px 60px rgba(0,0,0,0.3)' } as CSSProperties}>
          <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'baseline', gap: 10 }}>
            <h2 id="h-golive" className="h2" style={{ flex: '1 1 auto' }}>
              Go Live
            </h2>
            <span style={{ fontSize: 13, color: 'rgba(255,255,255,0.72)' }}>Free · straight between PCs</span>
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, alignItems: 'flex-start' }}>
            <div style={{ flex: '1 1 260px', display: 'flex', flexDirection: 'column', gap: 8 }}>
              <span className="picker-label">What to share</span>
              <Segmented label="What to share" options={SOURCES} value={s.source} onChange={p.onSource} className="segmented--lg" />
            </div>
            <div style={{ flex: '1 1 320px', display: 'flex', flexDirection: 'column', gap: 8 }}>
              <span className="picker-label">Quality</span>
              <Segmented label="Quality" options={QUALITIES} value={s.quality} onChange={p.onQuality} className="segmented--lg segmented--accent" />
            </div>
          </div>
          <div className="capacity">
            <div style={{ flex: '1 1 340px', display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span style={{ fontWeight: 600 }}>{s.capacityTitle}</span>
              <span style={{ fontSize: 13, color: 'rgba(255,255,255,0.72)' }}>{s.capacityDetail}</span>
            </div>
            {s.streaming ? (
              <Button variant="danger" size="lg" onClick={p.onStopStreaming}>
                Stop streaming
              </Button>
            ) : (
              <Button variant="primary" size="lg" onClick={p.onStartStreaming}>
                Start streaming
              </Button>
            )}
          </div>
          <p style={{ margin: 0, fontSize: 12, color: 'rgba(255,255,255,0.66)' }}>
            Netflix, Prime Video and JioHotstar are protected and show black if shared. Watch those together in a watch party instead.
          </p>
        </section>
      </main>

      <aside aria-labelledby="h-schat" className="glass glass--strong side-panel rise" style={{ '--i': 3, flex: '1 1 320px', gap: 14 } as CSSProperties}>
        <h2 id="h-schat" className="h3">
          Stream chat
        </h2>
        {s.watchers && s.watchers.length > 0 ? (
          <ul className="watcher-chips" aria-label="Watching">
            {s.watchers.map((w) => (
              <li key={w.id} className="watcher-chip">
                <Avatar name={w.name} id={w.id} photoUrl={w.photoUrl} size={26} />
                <span style={{ fontSize: 13 }}>{w.id === p.meId ? 'You' : firstName(w.name)}</span>
              </li>
            ))}
          </ul>
        ) : null}
        <MessageList
          messages={p.messages}
          resetKey="stream"
          showSenders
          compact
          label="Stream chat"
          empty={<span className="muted small">Say something to everyone watching.</span>}
        />
        <Composer label="Message the stream" placeholder="Message the stream" variant="panel" onSend={p.onSend} />
      </aside>
    </div>
  )
}

function Stage(p: StreamsProps): JSX.Element {
  const s = p.stream
  const stage = useRef<HTMLElement>(null)
  const video = useRef<HTMLVideoElement>(null)
  const [volume, setVolume] = useState(1)
  const [volumeOpen, setVolumeOpen] = useState(false)
  const host = s.liveHost

  useEffect(() => {
    if (video.current) video.current.srcObject = p.media
  }, [p.media])
  useEffect(() => {
    if (video.current) video.current.volume = volume
  }, [volume])

  const label = host ? `${firstName(host.name)}'s live stream` : 'Live stream'
  return (
    <section ref={stage} aria-label={label} className="stream-stage rise" style={{ '--i': 1 } as CSSProperties}>
      <div className="stream-video">
        {p.media ? <video ref={video} autoPlay playsInline aria-label={label} /> : <PlaceholderArt />}

        <div className="stream-chips">
          {host ? <span className="stream-chip stream-chip--live">LIVE</span> : null}
          {host && s.stats ? <span className="stream-chip">{s.stats}</span> : null}
          <span style={{ flex: 1 }} />
          {host ? <span className="stream-chip">{s.viewers} watching</span> : null}
        </div>

        <div className="host-bar">
          {host ? (
            <Avatar name={host.name} id={host.id} photoUrl={host.photoUrl} size={40} />
          ) : (
            <span className="host-bar__idle" aria-hidden="true">
              <Icon name="streams" size={20} />
            </span>
          )}
          <span style={{ flex: '1 1 200px', minWidth: 0, display: 'flex', flexDirection: 'column' }}>
            <span style={{ fontWeight: 700 }}>{host ? `${s.streaming ? 'You are' : `${firstName(host.name)} is`} live` : 'Nobody is live right now'}</span>
            <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.82)' }}>
              {host ? s.liveDetail : 'Stream a game, your screen or an app window below. Friends in your room can watch.'}
            </span>
          </span>
          {host ? (
            <div style={{ position: 'relative' }}>
              <button
                type="button"
                className="host-bar__btn pressable"
                aria-label="Volume"
                aria-expanded={volumeOpen}
                onClick={() => setVolumeOpen((v) => !v)}
              >
                <Icon name={volume === 0 ? 'volumeOff' : 'volume'} size={18} />
              </button>
              {volumeOpen ? (
                <div className="volume-pop">
                  <label className="sr-only" htmlFor="stream-volume">
                    Stream volume
                  </label>
                  <input
                    id="stream-volume"
                    type="range"
                    min={0}
                    max={100}
                    value={Math.round(volume * 100)}
                    autoFocus
                    onChange={(e) => {
                      const v = Number(e.target.value) / 100
                      setVolume(v)
                      p.onVolume?.(v)
                    }}
                    onKeyDown={(e) => {
                      if (e.key === 'Escape') setVolumeOpen(false)
                    }}
                  />
                </div>
              ) : null}
            </div>
          ) : null}
          <button
            type="button"
            className="host-bar__btn pressable"
            aria-label={document.fullscreenElement ? 'Exit full screen' : 'Full screen'}
            onClick={() => {
              if (document.fullscreenElement) void document.exitFullscreen()
              else void stage.current?.requestFullscreen()
            }}
          >
            <Icon name="fullscreen" size={18} />
          </button>
        </div>
      </div>
    </section>
  )
}

/** The design's night-racing scene, shown until real video arrives. */
function PlaceholderArt(): JSX.Element {
  return (
    <div role="img" aria-label="Placeholder: a night racing scene" className="stream-art">
      <div className="stream-art__road" />
      <div className="stream-art__lines" />
      <div style={{ position: 'absolute', left: '4%', bottom: '31.5%', width: '8%', height: '24%', background: '#312E81' }} />
      <div style={{ position: 'absolute', left: '13%', bottom: '31.5%', width: '5%', height: '34%', background: '#3730A3' }} />
      <div style={{ position: 'absolute', right: '7%', bottom: '31.5%', width: '7%', height: '28%', background: '#312E81' }} />
      <div style={{ position: 'absolute', right: '15%', bottom: '31.5%', width: '4%', height: '40%', background: '#4338CA' }} />
      <div className="stream-art__car" />
    </div>
  )
}
