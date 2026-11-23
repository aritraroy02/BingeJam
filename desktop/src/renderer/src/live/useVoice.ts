import { useEffect, useMemo, useRef, useState } from 'react'
import { auth } from '@/lib/firebase'
import { joinVoiceChannel, leaveVoiceChannel, setRoomVoiceFlag, setVoiceLive, updateVoiceChannel } from '@/data/channels'
import { fetchIceServers } from '@/data/relay'
import { VoiceEngine, voiceSupported, type VoiceEngineState, type VoiceTarget } from '@/voice/engine'
import { QUALITY, StreamHost, StreamViewer, parseSignal, type StreamQuality, type ViewerStatus } from '@/voice/stream'

// The one voice chat this PC is in (a group's voice channel or a room), app-wide like Discord's
// "Voice connected" panel: the engine, plus the database entries that show the others we're there.
// Mute and deafen are remembered on this PC.

const KEYS = { muted: 'bj.voice.muted', deafened: 'bj.voice.deafened' } as const

function loadFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

function saveFlag(key: string, on: boolean): void {
  try {
    localStorage.setItem(key, on ? '1' : '0')
  } catch {
    // Not remembered; still applies now.
  }
}

export interface VoiceSession {
  target: VoiceTarget | null
  state: VoiceEngineState | null
  muted: boolean
  deafened: boolean
  /** uids heard talking right now (us included), for the speaking rings. */
  speaking: ReadonlySet<string>
  join: (target: VoiceTarget) => void
  leave: () => void
  toggleMute: () => void
  toggleDeafen: () => void
  /** Our screen share (Go Live); null when not sharing. preview = what we send, for our own tile. */
  share: { quality: StreamQuality; viewers: number; preview: MediaStream } | null
  /** The share we're watching; stream is null until it arrives. */
  watching: { uid: string; status: ViewerStatus; stream: MediaStream | null } | null
  startShare: (sourceId: string, quality: StreamQuality, sound: boolean) => Promise<void>
  stopShare: () => void
  watch: (uid: string) => void
  stopWatching: () => void
}

function same(a: VoiceTarget | null, b: VoiceTarget | null): boolean {
  if (!a || !b) return a === b
  if (a.kind === 'room') return b.kind === 'room' && a.code === b.code
  return b.kind === 'channel' && a.gid === b.gid && a.cid === b.cid
}

export function useVoiceSession(opts: {
  uid: string
  name: string
  inputId: string | null
  outputId: string | null
  pushToTalkKey: string | null
  onError: (message: string) => void
}): VoiceSession {
  const [target, setTarget] = useState<VoiceTarget | null>(null)
  const [state, setState] = useState<VoiceEngineState | null>(null)
  const [muted, setMuted] = useState(() => loadFlag(KEYS.muted))
  const [deafened, setDeafened] = useState(() => loadFlag(KEYS.deafened))
  const engine = useRef<VoiceEngine | null>(null)
  const latest = useRef(opts)
  latest.current = opts
  const [share, setShare] = useState<VoiceSession['share']>(null)
  const [watching, setWatching] = useState<VoiceSession['watching']>(null)
  const host = useRef<StreamHost | null>(null)
  const viewer = useRef<StreamViewer | null>(null)
  const ice = useRef<RTCIceServer[] | null>(null)
  const iceServers = async (): Promise<RTCIceServer[]> => (ice.current ??= await fetchIceServers())

  function endShare(): void {
    const h = host.current
    host.current = null
    if (h) h.stop()
    engine.current?.setLive(false)
    setShare(null)
  }

  function endWatching(): void {
    const v = viewer.current
    viewer.current = null
    v?.stop()
    setWatching(null)
  }

  // One engine per target; the database entry follows it.
  useEffect(() => {
    if (!target) return
    if (!voiceSupported()) {
      latest.current.onError("This PC can't do voice chat")
      setTarget(null)
      return
    }
    const e = new VoiceEngine({
      target,
      idToken: async (force) => {
        const u = auth.currentUser
        return u && !u.isAnonymous ? u.getIdToken(force) : null
      },
      inputId: latest.current.inputId,
      outputId: latest.current.outputId,
      pushToTalkKey: latest.current.pushToTalkKey,
      muted: loadFlag(KEYS.muted),
      deafened: loadFlag(KEYS.deafened),
      onState: (s) => {
        setState(s)
        if (s.status === 'failed') {
          latest.current.onError(s.error ?? 'Voice disconnected')
          setTarget((t) => (same(t, target) ? null : t))
        }
      },
      // Screen share set-up messages: the sharer side takes watch / unwatch / answer, the viewer side
      // takes what its sharer sends; ICE goes to whichever has a connection with that slot.
      onSignal: (from, raw) => {
        const data = parseSignal(raw)
        if (!data) return
        const h = host.current
        const v = viewer.current
        if (data.kind === 'watch' || data.kind === 'unwatch' || data.kind === 'answer') h?.onSignal(from, data)
        else if (data.kind === 'ice') {
          if (h?.hasViewer(from)) h.onSignal(from, data)
          else if (v?.hostSlot === from) v.onSignal(data)
        } else if (v?.hostSlot === from) v.onSignal(data)
      },
      onPeerLeft: (slot) => {
        host.current?.viewerLeft(slot)
        if (viewer.current?.hostSlot === slot) viewer.current.hostGone()
      }
    })
    engine.current = e
    e.start()
    const m = loadFlag(KEYS.muted)
    const d = loadFlag(KEYS.deafened)
    const listed =
      target.kind === 'channel'
        ? joinVoiceChannel(target.gid, target.cid, latest.current.name, m || d, d)
        : setRoomVoiceFlag(target.code, true)
    listed.catch((err: unknown) => console.warn('Voice presence failed', err))
    return () => {
      endShare()
      endWatching()
      e.stop()
      if (engine.current === e) engine.current = null
      setState(null)
      const gone = target.kind === 'channel' ? leaveVoiceChannel(target.gid, target.cid) : setRoomVoiceFlag(target.code, false)
      gone.catch(() => undefined)
    }
  }, [target])

  // Settings changes apply at once.
  useEffect(() => engine.current?.setInput(opts.inputId), [opts.inputId])
  useEffect(() => engine.current?.setOutput(opts.outputId), [opts.outputId])
  useEffect(() => engine.current?.setPushToTalk(opts.pushToTalkKey), [opts.pushToTalkKey])

  // Mute / deafen: the engine, the remembered flags and (channels) what the others see.
  useEffect(() => {
    saveFlag(KEYS.muted, muted)
    saveFlag(KEYS.deafened, deafened)
    engine.current?.setMuted(muted)
    engine.current?.setDeafened(deafened)
    if (target?.kind === 'channel') void updateVoiceChannel(target.gid, target.cid, muted || deafened, deafened).catch(() => undefined)
  }, [muted, deafened])

  // The sharer we watch stopped sharing (or left): stop watching.
  useEffect(() => {
    if (!watching || !state) return
    if (!state.peers.some((p) => p.uid === watching.uid && p.live)) viewer.current?.hostGone()
  }, [state?.peers, watching?.uid])

  // Others see who's live in the channel list (the database), not only in the call.
  const sharing = share !== null
  useEffect(() => {
    if (target?.kind === 'channel') void setVoiceLive(target.gid, target.cid, sharing).catch(() => undefined)
  }, [sharing, target])

  async function startShare(sourceId: string, quality: StreamQuality, sound: boolean): Promise<void> {
    const e = engine.current
    if (!e || state?.status !== 'connected') {
      latest.current.onError('Join the voice channel first')
      return
    }
    endShare()
    const q = QUALITY[quality]
    let stream: MediaStream
    try {
      await window.bingejam.screen.choose(sourceId, sound)
      // restrictOwnAudio: the PC's sound without this app's own (the call), so viewers don't hear echo.
      stream = await navigator.mediaDevices.getDisplayMedia({
        video: { width: { ideal: q.width, max: q.width }, height: { ideal: q.height, max: q.height }, frameRate: { ideal: q.fps, max: q.fps } },
        audio: sound ? ({ restrictOwnAudio: true, echoCancellation: false, noiseSuppression: false, autoGainControl: false } as MediaTrackConstraints) : false
      })
    } catch (err) {
      console.warn('Screen capture failed', err)
      latest.current.onError("Couldn't capture that screen")
      return
    }
    if (engine.current !== e) {
      stream.getTracks().forEach((t) => t.stop())
      return
    }
    const servers = await iceServers()
    const h = new StreamHost(stream, quality, servers, (to, data) => void engine.current?.sendSignal(to, data), (viewers) =>
      setShare((cur) => (cur && host.current === h ? { ...cur, viewers } : cur))
    )
    host.current = h
    // Closing the shared window (or Windows' own "Stop sharing") ends it.
    stream.getVideoTracks()[0]?.addEventListener('ended', () => {
      if (host.current === h) endShare()
    })
    e.setLive(true)
    setShare({ quality, viewers: 0, preview: stream })
  }

  function watch(uid: string): void {
    const e = engine.current
    const slot = e?.slotOf(uid) ?? null
    if (!e || slot === null) {
      latest.current.onError("They're not sharing right now")
      return
    }
    endWatching()
    setWatching({ uid, status: 'connecting', stream: null })
    void iceServers().then((servers) => {
      if (engine.current !== e) return
      const v = new StreamViewer(
        slot,
        servers,
        (to, data) => void engine.current?.sendSignal(to, data),
        (stream) => setWatching((cur) => (cur && viewer.current === v ? { ...cur, stream } : cur)),
        (status) => {
          if (viewer.current !== v) return
          if (status === 'full' || status === 'ended' || status === 'failed') {
            viewer.current = null
            setWatching(null)
            latest.current.onError(
              status === 'full'
                ? 'That stream is full'
                : status === 'failed'
                  ? "Couldn't connect to the stream. One of your networks may block direct connections."
                  : 'The stream ended'
            )
          } else setWatching((cur) => (cur ? { ...cur, status } : cur))
        }
      )
      viewer.current = v
      v.start()
    })
  }

  const speaking = useMemo(() => {
    const out = new Set<string>()
    if (!state) return out
    if (state.speaking) out.add(opts.uid)
    for (const p of state.peers) if (p.speaking) out.add(p.uid)
    return out
  }, [state, opts.uid])

  return {
    target,
    state,
    muted,
    deafened,
    speaking,
    join: (t) => setTarget((cur) => (same(cur, t) ? cur : t)),
    leave: () => setTarget(null),
    toggleMute: () => {
      // Unmuting while deafened undeafens too, like Discord.
      if (deafened) {
        setDeafened(false)
        setMuted(false)
      } else setMuted((v) => !v)
    },
    toggleDeafen: () => setDeafened((v) => !v),
    share,
    watching,
    startShare,
    stopShare: endShare,
    watch,
    stopWatching: endWatching
  }
}
