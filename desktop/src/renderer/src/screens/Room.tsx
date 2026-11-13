import { useEffect, useState, type CSSProperties, type JSX, type KeyboardEvent, type PointerEvent } from 'react'
import { Icon } from '@/ui/Icon'
import { Avatar, Button, LiveDot, SyncDots } from '@/ui/primitives'
import { Composer, MessageList } from '@/ui/chat'
import { firstName, formatClock } from '@/ui/format'
import type { MessageVM, RoomMemberVM, RoomVM, SyncHealth, VoiceMemberVM } from '@/ui/models'
import { MemberRow } from './Chats'

// Inside a room (design/4-room): what's playing and where, the shared transport, who's here and in
// sync, voice, and the chat it belongs to. Everyone plays on their own account; only play / pause /
// position are shared.

export interface RoomProps {
  /** null while joining. */
  room: RoomVM | null
  /** The chat this room belongs to: "Movie Night" → "Movie Night chat". */
  chatName: string
  messages: MessageVM[] | null
  onSendChat: (text: string) => void
  onTogglePlay: () => void
  /** −10 s / +10 s. */
  onSeekBy: (deltaMs: number) => void
  /** Click or arrow keys on the progress bar. */
  onSeekTo: (positionMs: number) => void
  onSubtitles: () => void
  onWaitForBuffering: (on: boolean) => void
  onSyncAllToMe: () => void
  onInvite: () => void
  /** "Show the Netflix tab": bring the browser tab the extension controls to the front (or open it). */
  onShowPlayer: () => void
  onPing: (uid: string) => void
  onToggleMute: () => void
  onJoinVoice: () => void
  onGoLive: () => void
  /** Leave the room (it ends when the last person leaves). */
  onLeave: () => void
  /** The always-on-top mini window (real accounts). */
  onToggleMini?: () => void
  miniOpen?: boolean
  /** Emoji reactions everyone in the room sees (phones too). */
  onReact?: (emoji: string) => void
}

/** Same set as the phone's reaction bar. */
export const ROOM_REACTIONS = ['😂', '😮', '😍', '😢', '🔥', '👏'] as const

/**
 * Display-only clock: moves the shown position forward between updates while playing.
 * Uses performance.now() (monotonic); the engine owns the real position and every sync decision.
 */
function useShownPosition(positionMs: number, playing: boolean, durationMs: number | null): number {
  const [now, setNow] = useState(() => performance.now())
  const [anchor, setAnchor] = useState(() => ({ positionMs, at: performance.now() }))
  useEffect(() => setAnchor({ positionMs, at: performance.now() }), [positionMs, playing])
  useEffect(() => {
    if (!playing) return
    const id = window.setInterval(() => setNow(performance.now()), 250)
    return () => window.clearInterval(id)
  }, [playing])
  const shown = playing ? anchor.positionMs + Math.max(0, now - anchor.at) : anchor.positionMs
  return durationMs ? Math.min(shown, durationMs) : shown
}

export function Room(p: RoomProps): JSX.Element {
  if (!p.room) {
    return (
      <div className="screen" style={{ alignItems: 'center', justifyContent: 'center' }}>
        <p className="muted" aria-busy="true">
          Joining the room…
        </p>
      </div>
    )
  }
  return <RoomView {...p} room={p.room} />
}

function RoomView(p: RoomProps & { room: RoomVM }): JSX.Element {
  const r = p.room
  return (
    <div className="screen">
      <main className="scroll room-main" style={{ flex: '999 1 560px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20 }}>
        <Hero {...p} />
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 20 }}>
          <PeoplePanel room={r} onPing={p.onPing} />
          <VoicePanel room={r} onToggleMute={p.onToggleMute} onJoinVoice={p.onJoinVoice} onGoLive={p.onGoLive} onLeave={p.onLeave} />
        </div>
      </main>
      <aside aria-labelledby="h-room-chat" className="glass glass--strong side-panel rise" style={{ '--i': 4, flex: '1 1 320px' } as CSSProperties}>
        <h2 id="h-room-chat" className="h3">
          Room chat
        </h2>
        <MessageList
          messages={p.messages}
          resetKey={r.code}
          showSenders
          compact
          label="Room chat"
          empty={<span className="muted small">Messages here go to everyone in the room, phones too.</span>}
        />
        {p.onReact ? (
          <div className="reaction-bar" role="group" aria-label="React">
            {ROOM_REACTIONS.map((e) => (
              <button key={e} type="button" className="reaction-btn pressable" aria-label={`React ${e}`} onClick={() => p.onReact?.(e)}>
                {e}
              </button>
            ))}
          </div>
        ) : null}
        <Composer label="Message the room" placeholder="Message the room" variant="panel" onSend={p.onSendChat} />
      </aside>
    </div>
  )
}

function Hero(p: RoomProps & { room: RoomVM }): JSX.Element {
  const r = p.room
  const host = r.members.find((m) => m.isHost) ?? null
  const position = useShownPosition(r.positionMs, r.playing, r.durationMs)
  const service = r.service ?? 'the player'
  const nothing = !r.title
  return (
    <section aria-labelledby="h-room" className="room-hero rise" style={{ '--i': 1 } as CSSProperties}>
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'flex-start', gap: 12 }}>
        <div className="room-title-card">
          <span style={{ fontSize: 12, fontWeight: 600, color: 'rgba(var(--ink), 0.74)' }}>{r.context}</span>
          <h1 id="h-room" style={{ margin: 0, fontSize: 28, fontWeight: 500, letterSpacing: -0.4 }}>
            {nothing ? 'Nothing playing yet' : r.title}
          </h1>
        </div>
        <span className={r.allInSync ? 'hero-chip hero-chip--ok' : 'hero-chip hero-chip--warn'} role="status">
          {r.allInSync ? <SyncDots /> : <LiveDot />}
          {r.syncLabel}
        </span>
        <button type="button" className="hero-chip hero-chip--btn pressable" onClick={p.onInvite}>
          Invite
        </button>
        {p.onToggleMini ? (
          <button type="button" className="hero-chip hero-chip--btn pressable" aria-pressed={p.miniOpen === true} onClick={p.onToggleMini} title="Ctrl+Shift+B">
            {p.miniOpen ? 'Close mini window' : 'Mini window'}
          </button>
        ) : null}
      </div>

      <div style={{ alignSelf: 'center', textAlign: 'center', maxWidth: 520, display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
        <p style={{ margin: 0, fontSize: 20, fontWeight: 500 }}>{r.playerLabel}</p>
        <p style={{ margin: 0, color: 'rgba(var(--ink), 0.74)', lineHeight: 1.5 }}>
          Everyone watches on their own account. BingeJam lines up play, pause and position through the extension.
        </p>
        <Button className="glass-btn" onClick={p.onShowPlayer}>
          {r.playerAction ?? `Show the ${service} tab`}
        </Button>
      </div>

      <div className="transport">
        <div style={{ display: 'flex', alignItems: 'center', gap: 16, fontSize: 13, color: 'rgba(var(--ink), 0.8)' }}>
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatClock(nothing ? null : position)}</span>
          <Progress positionMs={position} durationMs={r.durationMs} host={host} onSeekTo={p.onSeekTo} />
          <span style={{ fontVariantNumeric: 'tabular-nums' }}>{formatClock(r.durationMs)}</span>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12 }}>
          <button type="button" className="transport__btn pressable" aria-label="Back 10 seconds" onClick={() => p.onSeekBy(-10000)} disabled={nothing}>
            <Icon name="back10" size={20} />
          </button>
          <button
            type="button"
            className="transport__play pressable"
            aria-label={r.playing ? 'Pause for everyone' : 'Play for everyone'}
            onClick={p.onTogglePlay}
            disabled={nothing}
          >
            <Icon name={r.playing ? 'pause' : 'play'} size={22} />
          </button>
          <button type="button" className="transport__btn pressable" aria-label="Forward 10 seconds" onClick={() => p.onSeekBy(10000)} disabled={nothing}>
            <Icon name="fwd10" size={20} />
          </button>
          <div style={{ flex: '1 1 20px' }} />
          <button type="button" className="transport__opt pressable" onClick={p.onSubtitles}>
            Subtitles · {r.settings.subtitles ?? 'Off'}
          </button>
          <button
            type="button"
            className="transport__opt pressable"
            aria-pressed={r.settings.waitForBuffering}
            onClick={() => p.onWaitForBuffering(!r.settings.waitForBuffering)}
          >
            Wait for buffering
          </button>
          <button type="button" className="transport__opt pressable" onClick={p.onSyncAllToMe} disabled={nothing}>
            Sync all to me
          </button>
        </div>
      </div>
    </section>
  )
}

/** Progress bar with the host's face where the room is. Click, or arrow keys (±10 s), seek everyone. */
function Progress({
  positionMs,
  durationMs,
  host,
  onSeekTo
}: {
  positionMs: number
  durationMs: number | null
  host: RoomMemberVM | null
  onSeekTo: (ms: number) => void
}): JSX.Element {
  const fraction = durationMs ? Math.min(1, Math.max(0, positionMs / durationMs)) : 0
  const pct = `${(fraction * 100).toFixed(2)}%`
  function onPointer(e: PointerEvent<HTMLDivElement>): void {
    if (!durationMs) return
    const box = e.currentTarget.getBoundingClientRect()
    onSeekTo(Math.round(((e.clientX - box.left) / box.width) * durationMs))
  }
  function onKey(e: KeyboardEvent<HTMLDivElement>): void {
    if (!durationMs) return
    const step = e.key === 'ArrowRight' ? 10000 : e.key === 'ArrowLeft' ? -10000 : 0
    if (step) {
      e.preventDefault()
      onSeekTo(Math.min(durationMs, Math.max(0, positionMs + step)))
    }
  }
  return (
    <div
      className="progress"
      role="slider"
      tabIndex={durationMs ? 0 : -1}
      aria-label="Room position"
      aria-valuemin={0}
      aria-valuemax={durationMs ?? 0}
      aria-valuenow={Math.round(positionMs)}
      aria-valuetext={`${formatClock(positionMs)} of ${formatClock(durationMs)}`}
      onPointerDown={onPointer}
      onKeyDown={onKey}
    >
      <div className="progress__track">
        <div className="progress__fill" style={{ transform: `scaleX(${fraction})` }} />
      </div>
      {host && durationMs ? (
        <span className="progress__host" style={{ left: pct }} title={`${firstName(host.name)} (host)`}>
          <Avatar name={host.name} id={host.id} photoUrl={host.photoUrl} size={24} />
        </span>
      ) : null}
    </div>
  )
}

const HEALTH_CLASS: Record<SyncHealth, string> = {
  inSync: 'sync-pill',
  catchingUp: 'sync-pill sync-pill--warn',
  buffering: 'sync-pill sync-pill--warn',
  noPlayer: 'sync-pill sync-pill--off'
}

function PeoplePanel({ room, onPing }: { room: RoomVM; onPing: (uid: string) => void }): JSX.Element {
  return (
    <section aria-labelledby="h-people" className="glass side-panel rise" style={{ '--i': 2 } as CSSProperties}>
      <h2 id="h-people" className="h3">
        In this room
      </h2>
      {room.members.map((m) => (
        <div key={m.id} className="member-row">
          <Avatar name={m.name} id={m.id} photoUrl={m.photoUrl} size={36} />
          <span className="member-row__text">
            <span className="member-row__name truncate">{m.isMe ? 'You' : `${firstName(m.name)}${m.isHost ? ' · host' : ''}`}</span>
            <span className="member-row__detail truncate">{m.device}</span>
          </span>
          <span className={HEALTH_CLASS[m.health]}>{m.syncLabel}</span>
        </div>
      ))}
      {room.notHere.length > 0 ? (
        <>
          <div className="divider" style={{ margin: '2px 0' }} />
          <span className="section-label">Not here yet · they can join any time</span>
          {room.notHere.map((m) => (
            <MemberRow key={m.id} member={{ ...m, name: firstName(m.name) }} onPing={onPing} size={36} />
          ))}
        </>
      ) : null}
    </section>
  )
}

function VoicePanel({
  room,
  onToggleMute,
  onJoinVoice,
  onGoLive,
  onLeave
}: {
  room: RoomVM
  onToggleMute: () => void
  onJoinVoice: () => void
  onGoLive: () => void
  onLeave: () => void
}): JSX.Element {
  const v = room.voice
  const hint = [v.quality, v.pushToTalkKey ? `hold ${v.pushToTalkKey} to talk` : null].filter(Boolean).join(' · ')
  return (
    <section aria-labelledby="h-voice" className="glass side-panel rise" style={{ '--i': 3, gap: 16 } as CSSProperties}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
        <h2 id="h-voice" className="h3" style={{ flex: 1 }}>
          Voice
        </h2>
        <span style={{ fontSize: 12, color: 'rgba(var(--ink), 0.68)' }}>{hint}</span>
      </div>
      {v.members.length === 0 ? (
        <p className="muted small" style={{ margin: 0 }}>
          Nobody is talking yet.
        </p>
      ) : (
        <ul className="voice-people" aria-label="In voice">
          {v.members.map((m) => (
            <VoicePerson key={m.id} member={m} />
          ))}
        </ul>
      )}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
        {v.joined ? (
          <button
            type="button"
            className="voice-btn voice-btn--icon pressable"
            aria-label={v.muted ? 'Unmute' : 'Mute'}
            aria-pressed={v.muted}
            title={v.muted ? 'Unmute' : 'Mute'}
            onClick={onToggleMute}
          >
            <Icon name={v.muted ? 'micOff' : 'mic'} size={18} />
          </button>
        ) : (
          <button type="button" className="voice-btn pressable" onClick={onJoinVoice}>
            <Icon name="mic" size={18} />
            Join voice
          </button>
        )}
        <button type="button" className="voice-btn pressable" onClick={onGoLive}>
          <Icon name="screen" size={18} />
          Go Live
        </button>
        <button type="button" className="voice-btn voice-btn--leave pressable" aria-label="Leave the room" onClick={onLeave}>
          Leave
        </button>
      </div>
    </section>
  )
}

function VoicePerson({ member: m }: { member: VoiceMemberVM }): JSX.Element {
  const label = m.isMe ? 'You' : firstName(m.name)
  return (
    <li className={m.speaking && !m.muted ? 'voice-person voice-person--speaking' : 'voice-person'}>
      <span className="voice-person__face" style={{ opacity: m.muted ? 0.6 : 1 }}>
        <Avatar name={m.name} id={m.id} photoUrl={m.photoUrl} size={54} />
      </span>
      <span style={{ fontSize: 12, color: m.muted ? 'rgba(var(--ink), 0.68)' : '#fff' }}>{m.muted && !m.isMe ? 'Muted' : label}</span>
      <span className="sr-only">{m.muted ? `${label}, muted` : m.speaking ? `${label}, talking` : ''}</span>
    </li>
  )
}
