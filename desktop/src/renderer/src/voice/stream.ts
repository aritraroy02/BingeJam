// Screen share in voice channels (Go Live, 2026-11-22), peer to peer like PLAN.md's "Completely free
// setup": the sharer's PC sends its screen (and, if asked, its sound) straight to each viewer's PC over
// WebRTC. The voice relay only passes the set-up messages (offer / answer / ICE) between the two, and
// GET /ice hands out STUN (plus Cloudflare TURN once configured) so most connections link directly.
// The sharer's upload is split between viewers, hence MAX_VIEWERS.

import { MAX_VIEWERS, QUALITY, preferredCodecs, type SignalData, type StreamQuality } from './streamModel.ts'

export { MAX_VIEWERS, QUALITY, parseSignal, type SignalData, type StreamQuality } from './streamModel.ts'

type Send = (to: number, data: SignalData) => void

function ice(candidate: RTCIceCandidate): RTCIceCandidateInit {
  return { candidate: candidate.candidate, sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex }
}

/** Our side when we share: one connection per viewer, each sending the same tracks. */
export class StreamHost {
  private readonly viewers = new Map<number, RTCPeerConnection>()
  private stopped = false

  constructor(
    readonly stream: MediaStream,
    private readonly quality: StreamQuality,
    private readonly iceServers: RTCIceServer[],
    private readonly send: Send,
    private readonly onViewers: (count: number) => void
  ) {
    const video = stream.getVideoTracks()[0]
    // Games and video want smooth motion; 720p30 is for slides and code, where sharp text matters.
    if (video) video.contentHint = quality === '720p30' ? 'detail' : 'motion'
  }

  get viewerCount(): number {
    return this.viewers.size
  }

  onSignal(from: number, data: SignalData): void {
    if (this.stopped) return
    if (data.kind === 'watch') void this.addViewer(from)
    else if (data.kind === 'unwatch') this.viewerLeft(from)
    else if (data.kind === 'answer') void this.viewers.get(from)?.setRemoteDescription({ type: 'answer', sdp: data.sdp }).catch(() => this.viewerLeft(from))
    else if (data.kind === 'ice') void this.viewers.get(from)?.addIceCandidate(data.candidate).catch(() => undefined)
  }

  hasViewer(slot: number): boolean {
    return this.viewers.has(slot)
  }

  viewerLeft(slot: number): void {
    const pc = this.viewers.get(slot)
    if (!pc) return
    this.viewers.delete(slot)
    pc.close()
    this.onViewers(this.viewers.size)
  }

  stop(): void {
    if (this.stopped) return
    this.stopped = true
    for (const [slot, pc] of this.viewers) {
      this.send(slot, { kind: 'ended' })
      pc.close()
    }
    this.viewers.clear()
    this.stream.getTracks().forEach((t) => t.stop())
    this.onViewers(0)
  }

  private async addViewer(slot: number): Promise<void> {
    this.viewerLeft(slot) // watching again restarts cleanly
    if (this.viewers.size >= MAX_VIEWERS) {
      this.send(slot, { kind: 'full' })
      return
    }
    const pc = new RTCPeerConnection({ iceServers: this.iceServers })
    this.viewers.set(slot, pc)
    this.onViewers(this.viewers.size)
    pc.onicecandidate = (e) => e.candidate && this.send(slot, { kind: 'ice', candidate: ice(e.candidate) })
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'closed') this.viewerLeft(slot)
    }
    const preset = QUALITY[this.quality]
    for (const track of this.stream.getTracks()) {
      const transceiver = pc.addTransceiver(track, { direction: 'sendonly', streams: [this.stream] })
      if (track.kind === 'video') {
        const caps = RTCRtpSender.getCapabilities('video')
        if (caps && 'setCodecPreferences' in transceiver) {
          try {
            transceiver.setCodecPreferences(preferredCodecs(caps.codecs))
          } catch {
            // Keep Chromium's own order.
          }
        }
      }
    }
    try {
      const offer = await pc.createOffer()
      await pc.setLocalDescription(offer)
      // Bitrate and frame rate per viewer, once the senders have their encodings.
      for (const sender of pc.getSenders()) {
        if (sender.track?.kind !== 'video') continue
        const params = sender.getParameters()
        if (!params.encodings?.length) params.encodings = [{}]
        params.encodings[0].maxBitrate = preset.bitrate
        params.encodings[0].maxFramerate = preset.fps
        ;(params as RTCRtpSendParameters & { degradationPreference?: string }).degradationPreference =
          this.quality === '720p30' ? 'maintain-resolution' : 'maintain-framerate'
        await sender.setParameters(params).catch(() => undefined)
      }
      if (this.viewers.get(slot) === pc && pc.localDescription) this.send(slot, { kind: 'offer', sdp: pc.localDescription.sdp })
    } catch {
      this.viewerLeft(slot)
    }
  }
}

export type ViewerStatus = 'connecting' | 'playing' | 'full' | 'ended' | 'failed'

/** Our side when we watch someone: one receive-only connection to their PC. */
export class StreamViewer {
  private pc: RTCPeerConnection | null = null
  private pending: RTCIceCandidateInit[] = []
  private stopped = false

  constructor(
    readonly hostSlot: number,
    private readonly iceServers: RTCIceServer[],
    private readonly send: Send,
    private readonly onStream: (stream: MediaStream | null) => void,
    private readonly onStatus: (status: ViewerStatus) => void
  ) {}

  start(): void {
    this.onStatus('connecting')
    this.send(this.hostSlot, { kind: 'watch' })
  }

  onSignal(data: SignalData): void {
    if (this.stopped) return
    if (data.kind === 'offer') void this.answer(data.sdp)
    else if (data.kind === 'ice') {
      if (this.pc?.remoteDescription) void this.pc.addIceCandidate(data.candidate).catch(() => undefined)
      else this.pending.push(data.candidate)
    } else if (data.kind === 'full') this.end('full')
    else if (data.kind === 'ended') this.end('ended')
  }

  /** We stop watching (or leave). */
  stop(): void {
    if (this.stopped) return
    this.send(this.hostSlot, { kind: 'unwatch' })
    this.end(null)
  }

  /** The sharer left the voice chat or stopped sharing. */
  hostGone(): void {
    this.end('ended')
  }

  private end(status: ViewerStatus | null): void {
    if (this.stopped) return
    this.stopped = true
    this.pc?.close()
    this.pc = null
    this.onStream(null)
    if (status) this.onStatus(status)
  }

  private async answer(sdp: string): Promise<void> {
    this.pc?.close()
    const pc = new RTCPeerConnection({ iceServers: this.iceServers })
    this.pc = pc
    pc.onicecandidate = (e) => e.candidate && this.send(this.hostSlot, { kind: 'ice', candidate: ice(e.candidate) })
    pc.ontrack = (e) => {
      const stream = e.streams[0] ?? new MediaStream([e.track])
      this.onStream(stream)
      this.onStatus('playing')
    }
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed') this.end('failed')
    }
    try {
      await pc.setRemoteDescription({ type: 'offer', sdp })
      for (const c of this.pending.splice(0)) await pc.addIceCandidate(c).catch(() => undefined)
      const answer = await pc.createAnswer()
      await pc.setLocalDescription(answer)
      if (pc.localDescription && this.pc === pc) this.send(this.hostSlot, { kind: 'answer', sdp: pc.localDescription.sdp })
    } catch {
      this.end('failed')
    }
  }
}
