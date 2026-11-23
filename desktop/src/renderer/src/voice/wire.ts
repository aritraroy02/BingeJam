// The voice relay's wire format (push-relay/src/voice.js, core/.../voice/VoiceWire.kt on the phone).
//
// Binary, app → relay:  [1][seq u16][flags u8][count u8] then count × [len u16][opus packet]
//         relay → apps: [1][slot u8][seq u16][flags u8][count u8] … (the relay inserts the slot)
// flags bit 0: sent in HD. Numbers are big-endian.

export const AUDIO = 1
export const FLAG_HD = 1
/** The relay drops bigger messages (MAX_AUDIO_BYTES). */
export const MAX_MESSAGE_BYTES = 2048
const MAX_PACKET = 1500

export interface AudioMessage {
  slot: number
  seq: number
  hd: boolean
  packets: Uint8Array[]
}

/** What we send: seq wraps at 16 bits. Returns null when the packets would not fit one message. */
export function encodeAudio(seq: number, hd: boolean, packets: readonly Uint8Array[]): Uint8Array | null {
  if (packets.length === 0 || packets.length > 255) return null
  let size = 5
  for (const p of packets) {
    if (p.length === 0 || p.length > MAX_PACKET) return null
    size += 2 + p.length
  }
  if (size > MAX_MESSAGE_BYTES) return null
  const out = new Uint8Array(size)
  const view = new DataView(out.buffer)
  out[0] = AUDIO
  view.setUint16(1, seq & 0xffff)
  out[3] = hd ? FLAG_HD : 0
  out[4] = packets.length
  let at = 5
  for (const p of packets) {
    view.setUint16(at, p.length)
    out.set(p, at + 2)
    at += 2 + p.length
  }
  return out
}

/** What the relay sends us; null for anything malformed. */
export function decodeAudio(data: Uint8Array): AudioMessage | null {
  if (data.length < 6 || data[0] !== AUDIO) return null
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength)
  const slot = data[1]
  const seq = view.getUint16(2)
  const hd = (data[4] & FLAG_HD) !== 0
  const count = data[5]
  const packets: Uint8Array[] = []
  let at = 6
  for (let i = 0; i < count; i++) {
    if (at + 2 > data.length) return null
    const len = view.getUint16(at)
    at += 2
    if (len === 0 || len > MAX_PACKET || at + len > data.length) return null
    packets.push(data.subarray(at, at + len))
    at += len
  }
  return packets.length > 0 ? { slot, seq, hd, packets } : null
}

// ---------- Control messages (JSON text) ----------

export interface WirePeer {
  slot: number
  uid: string
  name: string
  muted: boolean
  deafened: boolean
  /** Sharing their screen (PC); phones never are. */
  live: boolean
}

export type Control =
  | { t: 'hello'; slot: number; peers: WirePeer[]; server: string | null; edge: string | null }
  | { t: 'join'; peer: WirePeer }
  | { t: 'leave'; slot: number }
  | { t: 'state'; slot: number; muted: boolean; deafened: boolean; live: boolean }
  | { t: 'signal'; from: number; data: unknown }
  | { t: 'pong' }

function peer(v: unknown): WirePeer | null {
  if (!v || typeof v !== 'object') return null
  const o = v as Record<string, unknown>
  if (typeof o.slot !== 'number' || o.slot < 0 || typeof o.uid !== 'string') return null
  return {
    slot: o.slot,
    uid: o.uid,
    name: typeof o.name === 'string' && o.name.trim() ? o.name : 'Friend',
    muted: o.muted === true,
    deafened: o.deafened === true,
    live: o.live === true
  }
}

const COLO = /^[A-Z]{3}$/

/** A text frame from the relay; null for anything we don't know. */
export function parseControl(text: string): Control | null {
  if (text === 'pong') return { t: 'pong' }
  let m: unknown
  try {
    m = JSON.parse(text)
  } catch {
    return null
  }
  if (!m || typeof m !== 'object') return null
  const o = m as Record<string, unknown>
  switch (o.t) {
    case 'hello':
      if (typeof o.slot !== 'number') return null
      return {
        t: 'hello',
        slot: o.slot,
        peers: Array.isArray(o.peers) ? o.peers.flatMap((p) => peer(p) ?? []) : [],
        server: typeof o.server === 'string' && COLO.test(o.server) ? o.server : null,
        edge: typeof o.edge === 'string' && COLO.test(o.edge) ? o.edge : null
      }
    case 'join': {
      const p = peer(o.peer)
      return p ? { t: 'join', peer: p } : null
    }
    case 'leave':
      return typeof o.slot === 'number' ? { t: 'leave', slot: o.slot } : null
    case 'state':
      return typeof o.slot === 'number' ? { t: 'state', slot: o.slot, muted: o.muted === true, deafened: o.deafened === true, live: o.live === true } : null
    case 'signal':
      return typeof o.from === 'number' && o.data && typeof o.data === 'object' ? { t: 'signal', from: o.from, data: o.data } : null
    default:
      return null
  }
}

/** Our own state, as the phone sends it (plus live: sharing our screen). The PC always sends HD (48 kHz Opus). */
export function stateMessage(muted: boolean, deafened: boolean, live = false): string {
  return JSON.stringify({ t: 'state', muted, deafened, quality: 'hd', live })
}

/** Screen share set-up for one other person in the voice chat (the relay adds our slot). */
export function signalMessage(to: number, data: unknown): string {
  return JSON.stringify({ t: 'signal', to, data })
}
