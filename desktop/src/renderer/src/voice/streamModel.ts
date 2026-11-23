// Screen share: the pure parts (presets, set-up message parsing, codec order), tested in voice.test.ts.

export type StreamQuality = '720p30' | '1080p60' | '1440p60' | '4k60'

export interface QualityPreset {
  label: string
  width: number
  height: number
  fps: number
  /** Per viewer, bits per second. */
  bitrate: number
}

export const QUALITY: Record<StreamQuality, QualityPreset> = {
  '720p30': { label: '720p · 30', width: 1280, height: 720, fps: 30, bitrate: 2_500_000 },
  '1080p60': { label: '1080p · 60', width: 1920, height: 1080, fps: 60, bitrate: 8_000_000 },
  '1440p60': { label: '1440p · 60', width: 2560, height: 1440, fps: 60, bitrate: 14_000_000 },
  '4k60': { label: '4K · 60', width: 3840, height: 2160, fps: 60, bitrate: 25_000_000 }
}

export const MAX_VIEWERS = 6

export type SignalData =
  | { kind: 'watch' }
  | { kind: 'unwatch' }
  | { kind: 'offer'; sdp: string }
  | { kind: 'answer'; sdp: string }
  | { kind: 'ice'; candidate: RTCIceCandidateInit }
  | { kind: 'full' }
  | { kind: 'ended' }

const MAX_SDP = 30_000

/** A relayed set-up message; null for anything malformed (it came from another app). */
export function parseSignal(v: unknown): SignalData | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  switch (o.kind) {
    case 'watch':
    case 'unwatch':
    case 'full':
    case 'ended':
      return { kind: o.kind }
    case 'offer':
    case 'answer':
      return typeof o.sdp === 'string' && o.sdp.length > 0 && o.sdp.length <= MAX_SDP ? { kind: o.kind, sdp: o.sdp } : null
    case 'ice': {
      const c = o.candidate as Record<string, unknown> | null
      if (!c || typeof c !== 'object' || typeof c.candidate !== 'string' || c.candidate.length > 2000) return null
      return {
        kind: 'ice',
        candidate: {
          candidate: c.candidate,
          sdpMid: typeof c.sdpMid === 'string' ? c.sdpMid : null,
          sdpMLineIndex: typeof c.sdpMLineIndex === 'number' ? c.sdpMLineIndex : null
        }
      }
    }
    default:
      return null
  }
}

/** Hardware-friendly first: H.264 (NVENC / AMF / Quick Sync through Windows), then VP9, AV1, VP8. */
export function preferredCodecs<T extends { mimeType: string }>(codecs: readonly T[]): T[] {
  const rank = (m: string): number => {
    const k = m.toLowerCase()
    if (k === 'video/h264') return 0
    if (k === 'video/vp9') return 1
    if (k === 'video/av1') return 2
    if (k === 'video/vp8') return 3
    return 4 // rtx, red, ulpfec keep their place after the codecs
  }
  return [...codecs].sort((a, b) => rank(a.mimeType) - rank(b.mimeType))
}
