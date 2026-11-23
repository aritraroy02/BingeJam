import { RELAY_URL } from '@/data/relay'
import { VoiceActivity, rms } from './activity.ts'
import { schedule, type Slot } from './playout.ts'
import { cityOf, smoothPing } from './regions.ts'
import { decodeAudio, encodeAudio, parseControl, signalMessage, stateMessage, type WirePeer } from './wire.ts'

// Voice chat on the PC, through the same relay as the phones (push-relay/src/voice.js): one
// WebSocket per voice chat, Opus in both directions (WebCodecs, built into Electron's Chromium), so
// a PC and phones hear each other. Mirrors voice/.../VoiceEngine.kt:
//   mic → echo cancel / noise suppression (getUserMedia) → 48 kHz → voice activity → Opus 20 ms
//   packets, two per message, only while talking → relay
//   relay → per-person Opus decoder → scheduled on the audio clock with an 80 ms cushion → speakers
// Timing here is audio and network plumbing, not sync decisions: performance.now() and the
// AudioContext clock are used, never the wall clock.

export type VoiceTarget = { kind: 'channel'; gid: string; cid: string } | { kind: 'room'; code: string }

export type VoiceStatus = 'connecting' | 'connected' | 'reconnecting' | 'failed'

export interface VoicePeerState {
  slot: number
  uid: string
  name: string
  muted: boolean
  deafened: boolean
  speaking: boolean
  /** Sharing their screen. */
  live: boolean
}

export interface VoiceEngineState {
  status: VoiceStatus
  error: string | null
  muted: boolean
  deafened: boolean
  /** We're being heard right now (mic on, voice detected or push-to-talk held). */
  speaking: boolean
  peers: VoicePeerState[]
  /** Round trip to the voice server, smoothed; null until measured. */
  pingMs: number | null
  /** Where the voice chat runs ("BOM") and its city ("Mumbai"); null until the relay says. */
  server: string | null
  serverCity: string | null
  /** The data centre we connected through. */
  edge: string | null
}

export interface VoiceEngineOptions {
  target: VoiceTarget
  /** A fresh Firebase ID token (force = after the relay refused the last one). */
  idToken: (force: boolean) => Promise<string | null>
  inputId: string | null
  outputId: string | null
  /** KeyboardEvent.code; while set, we send only while it's held (in this window). */
  pushToTalkKey: string | null
  muted?: boolean
  deafened?: boolean
  onState: (state: VoiceEngineState) => void
  /** Screen share set-up from [from] (a slot); see stream.ts. */
  onSignal?: (from: number, data: unknown) => void
  /** Someone left the voice chat (or we reconnected and they're gone). */
  onPeerLeft?: (slot: number) => void
}

const SAMPLE_RATE = 48_000
const BITRATE = 32_000
const FRAME_US = 20_000
const PACKETS_PER_MESSAGE = 2
const SPEAKING_MS = 350
const PING_EVERY_MS = 4_000
const PROTOCOL = 'bingejam-voice'

export function voiceUrl(target: VoiceTarget): string {
  const base = RELAY_URL.replace(/^http/, 'ws')
  return target.kind === 'room' ? `${base}/voice/${target.code}` : `${base}/voice/g/${encodeURIComponent(target.gid)}/${encodeURIComponent(target.cid)}`
}

/** Electron's Chromium has these; an old one wouldn't, and then voice says so instead of failing oddly. */
export function voiceSupported(): boolean {
  return typeof AudioEncoder === 'function' && typeof AudioDecoder === 'function' && typeof AudioContext === 'function' && !!navigator.mediaDevices?.getUserMedia
}

interface Speaker {
  decoder: AudioDecoder
  gain: GainNode
  slot: Slot
  /** Sources queued on the audio clock (stopped when we jump back to the cushion). */
  queued: Set<AudioBufferSourceNode>
  timestampUs: number
  lastHeard: number
}

export class VoiceEngine {
  private readonly opts: VoiceEngineOptions
  private stopped = false
  private socket: WebSocket | null = null
  private generation = 0
  private attempts = 0
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private ticker: ReturnType<typeof setInterval> | null = null
  private pingTimer: ReturnType<typeof setInterval> | null = null
  private pingSentAt: number | null = null
  private pings: number[] = []
  private mySlot = -1
  private peers = new Map<number, WirePeer>()
  private speakers = new Map<number, Speaker>()
  private volumes = new Map<string, number>()
  private state: VoiceEngineState

  // Playback
  private playCtx: AudioContext | null = null
  private master: GainNode | null = null
  private analyser: AnalyserNode | null = null
  private analyserBuf = new Float32Array(1024)

  // Capture
  private captureGen = 0
  private mic: MediaStream | null = null
  private captureCtx: AudioContext | null = null
  private encoder: AudioEncoder | null = null
  private reader: ReadableStreamDefaultReader<AudioData> | null = null
  private processor: ScriptProcessorNode | null = null
  private vad = new VoiceActivity()
  private voiced = false
  private lastVoicedAt = -Infinity
  private pending: Uint8Array[] = []
  private seq = 0
  private pttDown = false
  private captureTimestampUs = 0
  private live = false

  constructor(opts: VoiceEngineOptions) {
    this.opts = opts
    this.state = {
      status: 'connecting',
      error: null,
      muted: opts.muted ?? false,
      deafened: opts.deafened ?? false,
      speaking: false,
      peers: [],
      pingMs: null,
      server: null,
      serverCity: null,
      edge: null
    }
  }

  get target(): VoiceTarget {
    return this.opts.target
  }

  start(): void {
    if (!voiceSupported()) {
      this.fail("This PC can't do voice chat")
      return
    }
    this.startPlayback()
    void this.connect(false)
    void this.restartCapture()
    this.ticker = setInterval(() => this.publish(), 200)
    window.addEventListener('keydown', this.onKeyDown)
    window.addEventListener('keyup', this.onKeyUp)
    window.addEventListener('blur', this.onBlur)
  }

  stop(): void {
    if (this.stopped) return
    this.stopped = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (this.ticker) clearInterval(this.ticker)
    if (this.pingTimer) clearInterval(this.pingTimer)
    window.removeEventListener('keydown', this.onKeyDown)
    window.removeEventListener('keyup', this.onKeyUp)
    window.removeEventListener('blur', this.onBlur)
    try {
      this.socket?.close(1000, 'bye')
    } catch {
      // Already closed.
    }
    this.socket = null
    this.stopCapture()
    for (const s of this.speakers.values()) this.closeSpeaker(s)
    this.speakers.clear()
    void this.playCtx?.close().catch(() => undefined)
    this.playCtx = null
  }

  setMuted(muted: boolean): void {
    if (this.state.muted === muted) return
    this.state = { ...this.state, muted }
    this.sendState()
    void this.restartCapture()
    this.publish()
  }

  /** Discord-style: deafened hears nobody and isn't heard either. */
  setDeafened(deafened: boolean): void {
    if (this.state.deafened === deafened) return
    this.state = { ...this.state, deafened }
    if (this.master) this.master.gain.value = deafened ? 0 : 1
    this.sendState()
    void this.restartCapture()
    this.publish()
  }

  setInput(deviceId: string | null): void {
    this.opts.inputId = deviceId
    void this.restartCapture()
  }

  setOutput(deviceId: string | null): void {
    this.opts.outputId = deviceId
    void this.applyOutput()
  }

  /** We started or stopped sharing our screen: the others see it in our state. */
  setLive(live: boolean): void {
    if (this.live === live) return
    this.live = live
    this.sendState()
  }

  /** A screen share set-up message for one other person (by slot). False when not connected. */
  sendSignal(to: number, data: unknown): boolean {
    if (this.socket?.readyState !== WebSocket.OPEN) return false
    this.socket.send(signalMessage(to, data))
    return true
  }

  /** The relay's slot for [uid] in this voice chat, or null. */
  slotOf(uid: string): number | null {
    for (const p of this.peers.values()) if (p.uid === uid) return p.slot
    return null
  }

  setPushToTalk(code: string | null): void {
    this.opts.pushToTalkKey = code
    this.pttDown = false
  }

  /** How loud [uid] plays for us, 0–2. */
  setVolume(uid: string, volume: number): void {
    this.volumes.set(uid, volume)
    for (const [slot, s] of this.speakers) {
      if (this.peers.get(slot)?.uid === uid) s.gain.gain.value = volume
    }
  }

  // ---------- Relay connection ----------

  private async connect(forceToken: boolean): Promise<void> {
    if (this.stopped) return
    const gen = ++this.generation
    const token = await this.opts.idToken(forceToken).catch(() => null)
    if (this.stopped || gen !== this.generation) return
    if (!token) {
      this.fail('Sign in again to use voice')
      return
    }
    let opened = false
    let ws: WebSocket
    try {
      ws = new WebSocket(voiceUrl(this.opts.target), [PROTOCOL, token])
    } catch {
      this.dropped(false)
      return
    }
    ws.binaryType = 'arraybuffer'
    this.socket = ws
    const current = (): boolean => !this.stopped && gen === this.generation
    ws.onopen = () => {
      if (!current()) return
      opened = true
      this.attempts = 0
      this.sendState()
      this.set({ status: 'connected', error: null })
      this.startPings()
    }
    ws.onmessage = (e) => {
      if (!current()) return
      if (typeof e.data === 'string') this.onControl(e.data)
      else this.onAudio(new Uint8Array(e.data as ArrayBuffer))
    }
    ws.onclose = (e) => {
      if (!current()) return
      // A refused handshake (401/403/404/429) closes before opening, with no status we can read.
      if (!opened) this.dropped(true)
      else if (e.code === 4000) this.fail('Voice opened on another device')
      else this.dropped(false)
    }
  }

  private dropped(beforeOpen: boolean): void {
    if (this.stopped) return
    this.socket = null
    this.mySlot = -1
    for (const slot of this.peers.keys()) this.opts.onPeerLeft?.(slot)
    this.peers.clear()
    if (this.pingTimer) clearInterval(this.pingTimer)
    this.pingTimer = null
    this.attempts++
    // Refused several times in a row: the token, the room or the group is the problem, not the network.
    if (beforeOpen && this.attempts >= 4) {
      this.fail(this.opts.target.kind === 'room' ? "Couldn't join the room's voice chat" : "Couldn't join this voice channel")
      return
    }
    this.set({ status: 'reconnecting' })
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    const delay = beforeOpen && this.attempts === 1 ? 0 : Math.min(10_000, 1_000 * 2 ** Math.min(4, this.attempts - 1))
    // The first refusal may be an expired token: retry at once with a fresh one.
    this.reconnectTimer = setTimeout(() => void this.connect(beforeOpen && this.attempts === 1), delay)
  }

  private fail(message: string): void {
    if (this.stopped) return
    this.set({ status: 'failed', error: message })
    this.stop()
  }

  private sendState(): void {
    if (this.socket?.readyState !== WebSocket.OPEN) return
    this.socket.send(stateMessage(this.state.muted || this.state.deafened, this.state.deafened, this.live))
  }

  private startPings(): void {
    if (this.pingTimer) clearInterval(this.pingTimer)
    const ping = (): void => {
      if (this.socket?.readyState !== WebSocket.OPEN) return
      // One at a time: a pong answers the oldest ping, and an unanswered one is given up after 10 s.
      if (this.pingSentAt !== null && performance.now() - this.pingSentAt < 10_000) return
      this.pingSentAt = performance.now()
      this.socket.send('ping')
    }
    ping()
    this.pingTimer = setInterval(ping, PING_EVERY_MS)
  }

  private onControl(text: string): void {
    const m = parseControl(text)
    if (!m) return
    switch (m.t) {
      case 'pong':
        if (this.pingSentAt !== null) {
          this.pings = [...this.pings, performance.now() - this.pingSentAt].slice(-5)
          this.pingSentAt = null
          this.set({ pingMs: smoothPing(this.pings) })
        }
        return
      case 'signal':
        this.opts.onSignal?.(m.from, m.data)
        return
      case 'hello':
        for (const slot of this.peers.keys()) if (!m.peers.some((p) => p.slot === slot)) this.opts.onPeerLeft?.(slot)
        this.mySlot = m.slot
        this.peers = new Map(m.peers.map((p) => [p.slot, p]))
        for (const slot of [...this.speakers.keys()]) if (!this.peers.has(slot)) this.forget(slot)
        this.set({ server: m.server, serverCity: cityOf(m.server), edge: m.edge })
        break
      case 'join':
        for (const [slot, p] of this.peers) if (p.uid === m.peer.uid) this.peers.delete(slot)
        this.peers.set(m.peer.slot, m.peer)
        break
      case 'leave':
        this.peers.delete(m.slot)
        this.forget(m.slot)
        this.opts.onPeerLeft?.(m.slot)
        break
      case 'state': {
        const p = this.peers.get(m.slot)
        if (p) this.peers.set(m.slot, { ...p, muted: m.muted, deafened: m.deafened, live: m.live })
        break
      }
    }
    this.publish()
  }

  // ---------- Receiving ----------

  private startPlayback(): void {
    const ctx = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' })
    this.playCtx = ctx
    this.master = ctx.createGain()
    this.master.gain.value = this.state.deafened ? 0 : 1
    this.analyser = ctx.createAnalyser()
    this.analyser.fftSize = 1024
    this.master.connect(this.analyser)
    this.analyser.connect(ctx.destination)
    void this.applyOutput()
    // Autoplay rules don't apply to a click-started app, but resume in case it starts suspended.
    void ctx.resume().catch(() => undefined)
  }

  private async applyOutput(): Promise<void> {
    const ctx = this.playCtx as (AudioContext & { setSinkId?: (id: string) => Promise<void> }) | null
    if (!ctx?.setSinkId) return
    try {
      await ctx.setSinkId(this.opts.outputId ?? '')
    } catch {
      // The picked speaker is gone: keep the default.
      await ctx.setSinkId('').catch(() => undefined)
    }
  }

  private onAudio(data: Uint8Array): void {
    const m = decodeAudio(data)
    if (!m || m.slot === this.mySlot || this.state.deafened) return
    const s = this.speaker(m.slot)
    if (!s) return
    s.lastHeard = performance.now()
    for (const packet of m.packets) {
      try {
        s.decoder.decode(new EncodedAudioChunk({ type: 'key', timestamp: s.timestampUs, data: packet }))
      } catch {
        // A packet the decoder can't take; the next one starts clean.
      }
      s.timestampUs += FRAME_US
    }
  }

  private speaker(slot: number): Speaker | null {
    const existing = this.speakers.get(slot)
    if (existing) return existing
    const ctx = this.playCtx
    const master = this.master
    if (!ctx || !master) return null
    const gain = ctx.createGain()
    gain.gain.value = this.volumes.get(this.peers.get(slot)?.uid ?? '') ?? 1
    gain.connect(master)
    const speaker: Speaker = {
      gain,
      slot: { playhead: 0 },
      queued: new Set(),
      timestampUs: 0,
      lastHeard: 0,
      decoder: new AudioDecoder({
        output: (audio) => this.play(speaker, audio),
        error: () => {
          // Rebuilt on the next packet.
          this.forget(slot)
        }
      })
    }
    // Raw Opus packets (no Ogg); mono needs no description.
    speaker.decoder.configure({ codec: 'opus', sampleRate: SAMPLE_RATE, numberOfChannels: 1 })
    this.speakers.set(slot, speaker)
    return speaker
  }

  private play(s: Speaker, audio: AudioData): void {
    const ctx = this.playCtx
    try {
      if (!ctx || this.stopped) return
      const frames = audio.numberOfFrames
      const buffer = ctx.createBuffer(1, frames, audio.sampleRate)
      const pcm = new Float32Array(frames)
      // Chromium's Opus decoder hands out interleaved f32; for mono that is the same layout as planar.
      audio.copyTo(pcm, audio.format === 'f32' || audio.format === 'f32-planar' ? { planeIndex: 0 } : { planeIndex: 0, format: 'f32-planar' })
      buffer.copyToChannel(pcm, 0)
      const { at, reset } = schedule(s.slot, ctx.currentTime, frames / audio.sampleRate)
      if (reset) {
        for (const q of s.queued) {
          try {
            q.stop()
          } catch {
            // Already ended.
          }
        }
        s.queued.clear()
      }
      const src = ctx.createBufferSource()
      src.buffer = buffer
      src.connect(s.gain)
      src.onended = () => s.queued.delete(src)
      s.queued.add(src)
      src.start(at)
    } finally {
      audio.close()
    }
  }

  private forget(slot: number): void {
    const s = this.speakers.get(slot)
    if (!s) return
    this.speakers.delete(slot)
    this.closeSpeaker(s)
  }

  private closeSpeaker(s: Speaker): void {
    try {
      if (s.decoder.state !== 'closed') s.decoder.close()
    } catch {
      // Closed already.
    }
    s.gain.disconnect()
  }

  /** RMS of what we're playing right now, so friends' voices from the speakers aren't sent back. */
  private playbackLevel(): number {
    if (!this.analyser) return 0
    this.analyser.getFloatTimeDomainData(this.analyserBuf)
    return rms(this.analyserBuf)
  }

  // ---------- Sending ----------

  private stopCapture(): void {
    this.captureGen++
    void this.reader?.cancel().catch(() => undefined)
    this.reader = null
    if (this.processor) {
      this.processor.onaudioprocess = null
      this.processor.disconnect()
      this.processor = null
    }
    try {
      if (this.encoder && this.encoder.state !== 'closed') this.encoder.close()
    } catch {
      // Closed already.
    }
    this.encoder = null
    this.mic?.getTracks().forEach((t) => t.stop())
    this.mic = null
    void this.captureCtx?.close().catch(() => undefined)
    this.captureCtx = null
    this.pending = []
    this.voiced = false
  }

  private async restartCapture(): Promise<void> {
    this.stopCapture()
    const gen = this.captureGen
    if (this.stopped || this.state.muted || this.state.deafened) return
    let mic: MediaStream
    try {
      mic = await navigator.mediaDevices.getUserMedia({
        audio: {
          deviceId: this.opts.inputId ? { exact: this.opts.inputId } : undefined,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true
        }
      })
    } catch (e) {
      if (gen !== this.captureGen) return
      // The picked mic is gone: fall back to the default once.
      if (this.opts.inputId && (e as Error).name === 'OverconstrainedError') {
        this.opts.inputId = null
        void this.restartCapture()
        return
      }
      this.fail((e as Error).name === 'NotAllowedError' ? 'Allow the microphone to talk in voice' : "Couldn't open the microphone")
      return
    }
    if (gen !== this.captureGen || this.stopped) {
      mic.getTracks().forEach((t) => t.stop())
      return
    }
    this.mic = mic
    this.vad.reset()

    const encoder = new AudioEncoder({
      output: (chunk) => this.onPacket(chunk, gen),
      error: () => {
        if (gen === this.captureGen) void this.restartCapture()
      }
    })
    encoder.configure({ codec: 'opus', sampleRate: SAMPLE_RATE, numberOfChannels: 1, bitrate: BITRATE, opus: { frameDuration: FRAME_US, format: 'opus' } } as AudioEncoderConfig)
    this.encoder = encoder

    // Bring the mic to exactly 48 kHz mono (some mics run at 44.1 kHz), then read it in small frames.
    const ctx = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' })
    this.captureCtx = ctx
    const source = ctx.createMediaStreamSource(mic)
    const Processor = (globalThis as { MediaStreamTrackProcessor?: new (init: { track: MediaStreamTrack }) => { readable: ReadableStream<AudioData> } })
      .MediaStreamTrackProcessor
    if (Processor) {
      const dest = ctx.createMediaStreamDestination()
      dest.channelCount = 1
      source.connect(dest)
      const track = dest.stream.getAudioTracks()[0]
      const reader = new Processor({ track }).readable.getReader()
      this.reader = reader
      void (async () => {
        const scratch = new Float32Array(4096)
        while (gen === this.captureGen) {
          const { value, done } = await reader.read().catch(() => ({ value: undefined, done: true }))
          if (done || !value) break
          try {
            if (gen !== this.captureGen) break
            const n = Math.min(value.numberOfFrames, scratch.length)
            const frame = scratch.subarray(0, n)
            value.copyTo(frame, { planeIndex: 0, format: 'f32-planar', frameCount: n })
            this.onFrame(frame, value)
          } catch {
            // One odd frame; carry on.
          } finally {
            value.close()
          }
        }
      })()
    } else {
      // Older Chromium: a ScriptProcessor hands us the samples instead.
      const processor = ctx.createScriptProcessor(1024, 1, 1)
      this.processor = processor
      processor.onaudioprocess = (e) => {
        if (gen !== this.captureGen) return
        const samples = new Float32Array(e.inputBuffer.getChannelData(0))
        const audio = new AudioData({
          format: 'f32-planar',
          sampleRate: SAMPLE_RATE,
          numberOfFrames: samples.length,
          numberOfChannels: 1,
          timestamp: this.captureTimestampUs,
          data: samples
        })
        this.captureTimestampUs += Math.round((samples.length / SAMPLE_RATE) * 1_000_000)
        try {
          this.onFrame(samples, audio)
        } finally {
          audio.close()
        }
      }
      // Keep the processor running without playing the mic back.
      const sink = ctx.createGain()
      sink.gain.value = 0
      source.connect(processor)
      processor.connect(sink)
      sink.connect(ctx.destination)
    }
  }

  /** Every captured frame: decide whether we're talking, and feed the encoder either way (it keeps its state warm). */
  private onFrame(samples: Float32Array, audio: AudioData): void {
    const now = performance.now()
    const ptt = this.opts.pushToTalkKey
    this.voiced = ptt ? this.pttDown : this.vad.isVoiced(rms(samples), now, this.playbackLevel())
    if (this.voiced) this.lastVoicedAt = now
    if (this.encoder?.state === 'configured') this.encoder.encode(audio)
  }

  private onPacket(chunk: EncodedAudioChunk, gen: number): void {
    if (gen !== this.captureGen) return
    const packet = new Uint8Array(chunk.byteLength)
    chunk.copyTo(packet)
    if (this.voiced) this.pending.push(packet)
    if (this.pending.length >= PACKETS_PER_MESSAGE || (!this.voiced && this.pending.length > 0)) {
      const message = encodeAudio(this.seq, true, this.pending)
      if (message && this.socket?.readyState === WebSocket.OPEN) this.socket.send(message)
      this.seq = (this.seq + 1) & 0xffff
      this.pending = []
    }
  }

  private readonly onKeyDown = (e: KeyboardEvent): void => {
    if (this.opts.pushToTalkKey && e.code === this.opts.pushToTalkKey) this.pttDown = true
  }

  private readonly onKeyUp = (e: KeyboardEvent): void => {
    if (this.opts.pushToTalkKey && e.code === this.opts.pushToTalkKey) this.pttDown = false
  }

  private readonly onBlur = (): void => {
    this.pttDown = false
  }

  // ---------- State ----------

  private set(patch: Partial<VoiceEngineState>): void {
    this.state = { ...this.state, ...patch }
    this.opts.onState(this.state)
  }

  private publish(): void {
    if (this.stopped) return
    const now = performance.now()
    const speaking = this.state.status === 'connected' && !this.state.muted && !this.state.deafened && now - this.lastVoicedAt < SPEAKING_MS
    const peers = [...this.peers.values()]
      .filter((p) => p.slot !== this.mySlot)
      .sort((a, b) => a.slot - b.slot)
      .map((p) => ({ ...p, speaking: !p.muted && now - (this.speakers.get(p.slot)?.lastHeard ?? 0) < SPEAKING_MS }))
    const same =
      speaking === this.state.speaking &&
      peers.length === this.state.peers.length &&
      peers.every((p, i) => {
        const q = this.state.peers[i]
        return q.slot === p.slot && q.uid === p.uid && q.muted === p.muted && q.deafened === p.deafened && q.speaking === p.speaking && q.name === p.name && q.live === p.live
      })
    if (!same) this.set({ speaking, peers })
  }
}
