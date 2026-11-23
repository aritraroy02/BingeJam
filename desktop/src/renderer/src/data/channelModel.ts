// Group channels, Discord style (PC app, 2026-11-22). Pure: parsing and ordering, no Firebase.
//
//   groups/{gid}/channels/{cid} {name, kind text|voice, order, createdBy, createdAt}  (admins)
//   groupChannelMessages/{gid}/{cid}/{msgId} {from, text, sentAt}   text channels other than #general
//   groupChannelReads/{uid}/{gid}/{cid}: server time                  read up to
//   groupVoice/{gid}/{cid}/{uid} {name, since, muted?, deafened?, live?} who's in a voice channel (live = sharing)
//   groupVoiceStatus/{gid}/{cid} {text, by, at}                       "Set a channel status"
//
// Every group has two channels nobody stored: #general (its original chat, groupMessages/{gid},
// which phones show) and the "General" voice channel (id "lounge").

export const GENERAL = 'general'
export const LOUNGE = 'lounge'
export const CHANNEL_NAME_MAX = 32
export const CHANNEL_STATUS_MAX = 60

export type ChannelKind = 'text' | 'voice'

export interface Channel {
  id: string
  name: string
  kind: ChannelKind
  order: number
  createdAt: number
  /** #general and the General voice channel: always there, can't be renamed or deleted. */
  builtIn: boolean
}

export interface VoiceOccupant {
  uid: string
  name: string
  since: number
  muted: boolean
  deafened: boolean
  /** Sharing their screen (Go Live). */
  live: boolean
}

const ID = /^[A-Za-z0-9_-]{1,40}$/

export const BUILT_IN: readonly Channel[] = [
  { id: GENERAL, name: 'general', kind: 'text', order: -1, createdAt: 0, builtIn: true },
  { id: LOUNGE, name: 'General', kind: 'voice', order: -1, createdAt: 0, builtIn: true }
]

/** groups/{gid}/channels → the stored channels (malformed ones skipped). */
export function parseChannels(val: unknown): Channel[] {
  if (!val || typeof val !== 'object') return []
  const out: Channel[] = []
  for (const [id, raw] of Object.entries(val as Record<string, unknown>)) {
    if (!ID.test(id) || id === GENERAL || id === LOUNGE || !raw || typeof raw !== 'object') continue
    const o = raw as Record<string, unknown>
    if ((o.kind !== 'text' && o.kind !== 'voice') || typeof o.name !== 'string' || !o.name.trim()) continue
    out.push({
      id,
      name: o.name.slice(0, CHANNEL_NAME_MAX),
      kind: o.kind,
      order: typeof o.order === 'number' ? o.order : 0,
      createdAt: typeof o.createdAt === 'number' ? o.createdAt : 0,
      builtIn: false
    })
  }
  return out
}

/** Built-in first, then by order, then oldest first: text channels, then voice channels. */
export function channelList(stored: readonly Channel[]): { text: Channel[]; voice: Channel[] } {
  const all = [...BUILT_IN, ...stored].sort((a, b) => a.order - b.order || a.createdAt - b.createdAt || a.id.localeCompare(b.id))
  return { text: all.filter((c) => c.kind === 'text'), voice: all.filter((c) => c.kind === 'voice') }
}

/** The order a new channel gets: after every channel of its kind. */
export function nextOrder(stored: readonly Channel[], kind: ChannelKind): number {
  return Math.min(1_000_000, Math.max(-1, ...stored.filter((c) => c.kind === kind).map((c) => c.order)) + 1)
}

/**
 * Text channels read like Discord's: lowercase words joined with dashes ("movie-picks"); voice
 * channels keep what was typed ("Gaming"). Empty when nothing usable is left.
 */
export function cleanChannelName(name: string, kind: ChannelKind): string {
  const collapsed = name.replace(/\s+/g, ' ').trim()
  if (kind === 'voice') return collapsed.slice(0, CHANNEL_NAME_MAX).trim()
  return collapsed
    .toLowerCase()
    .replace(/[^\p{L}\p{N}_ -]/gu, '')
    .trim()
    .replace(/[ -]+/g, '-')
    .slice(0, CHANNEL_NAME_MAX)
    .replace(/^-+|-+$/g, '')
}

/** groupVoice/{gid} → channel id → people in it, first to join first. */
export function parseVoiceOccupants(val: unknown): Map<string, VoiceOccupant[]> {
  const out = new Map<string, VoiceOccupant[]>()
  if (!val || typeof val !== 'object') return out
  for (const [cid, people] of Object.entries(val as Record<string, unknown>)) {
    if (!people || typeof people !== 'object') continue
    const list: VoiceOccupant[] = []
    for (const [uid, raw] of Object.entries(people as Record<string, unknown>)) {
      if (!raw || typeof raw !== 'object') continue
      const o = raw as Record<string, unknown>
      if (typeof o.since !== 'number') continue
      list.push({
        uid,
        name: typeof o.name === 'string' && o.name.trim() ? o.name : 'Friend',
        since: o.since,
        muted: o.muted === true,
        deafened: o.deafened === true,
        live: o.live === true
      })
    }
    if (list.length > 0) out.set(cid, list.sort((a, b) => a.since - b.since || a.uid.localeCompare(b.uid)))
  }
  return out
}

/** groupVoiceStatus/{gid} → channel id → status text. */
export function parseVoiceStatuses(val: unknown): Map<string, string> {
  const out = new Map<string, string>()
  if (!val || typeof val !== 'object') return out
  for (const [cid, raw] of Object.entries(val as Record<string, unknown>)) {
    const text = raw && typeof raw === 'object' ? (raw as Record<string, unknown>).text : null
    if (typeof text === 'string' && text.trim()) out.set(cid, text.slice(0, CHANNEL_STATUS_MAX))
  }
  return out
}

/**
 * How long the voice channel has had people in it, from the first one still there (server times):
 * "4:07", "1:15:05". Null when nobody's in.
 */
export function callElapsed(people: readonly VoiceOccupant[], nowMs: number): string | null {
  if (people.length === 0) return null
  const start = Math.min(...people.map((p) => p.since))
  const s = Math.max(0, Math.floor((nowMs - start) / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}

/** A text channel has something new: its newest message from someone else is after our last read. */
export function channelUnread(latest: { from: string; sentAt: number } | null, readAt: number, me: string): boolean {
  return !!latest && latest.from !== me && latest.sentAt > readAt
}
