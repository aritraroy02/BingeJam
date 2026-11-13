// The room wire format: plain RTDB values (DataSnapshot.val()) ↔ the sync models. Port of
// data-firebase/.../RoomParser.kt plus the write payloads of FirebaseSyncTransport.kt, kept pure
// so the field names are unit-tested. Tolerant of missing fields; never throws.
// data-firebase/firebase/database.rules.json is the authority on what may be written.

import type { ServiceId } from '../player.ts'
import type { Countdown, Member, NowPlaying, PlaybackStatus, Room, RoomPreview, RoomState, RoomTitle, StateBroadcast } from './types.ts'

const SERVICES: readonly ServiceId[] = ['NETFLIX', 'JIOHOTSTAR', 'PRIME_VIDEO', 'SPOTIFY', 'YOUTUBE_MUSIC', 'AMAZON_MUSIC', 'YOUTUBE']

/** 6 characters from ABCDEFGHJKLMNPQRSTUVWXYZ23456789 (rules: rooms/$code). */
export const ROOM_CODE = /^[A-HJ-NP-Z2-9]{6}$/

/** Rules: previews title/subtitle ≤ 200 characters. */
export const MAX_META_LENGTH = 200
/** Rules: countdown leadMs between 1 and 10 s. */
export const COUNTDOWN_LEAD_RANGE = { min: 1_000, max: 10_000 } as const

type Obj = Record<string, unknown>

function obj(v: unknown): Obj | null {
  return typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Obj) : null
}
function str(v: unknown): string | null {
  return typeof v === 'string' ? v : null
}
function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}
export function serviceId(v: unknown): ServiceId | null {
  return typeof v === 'string' && (SERVICES as readonly string[]).includes(v) ? (v as ServiceId) : null
}
function status(v: unknown): PlaybackStatus | null {
  return v === 'playing' || v === 'paused' ? v : null
}

/** rooms/{code}; null if it doesn't exist (or has no host). */
export function parseRoom(code: string, value: unknown): Room | null {
  const v = obj(value)
  if (v === null) return null
  const hostUid = str(v.hostUid)
  if (hostUid === null) return null
  const members: Member[] = []
  const m = obj(v.members)
  if (m !== null) {
    for (const [uid, raw] of Object.entries(m)) {
      const member = parseMember(uid, raw)
      if (member !== null) members.push(member)
    }
  }
  return {
    code,
    hostUid,
    createdAt: num(v.createdAt) ?? 0,
    title: parseTitle(v.title),
    state: parseState(v.state),
    members,
    countdown: parseCountdown(v.countdown)
  }
}

export function parseState(value: unknown): RoomState | null {
  const v = obj(value)
  if (v === null) return null
  const s = status(v.status)
  if (s === null) return null
  return {
    status: s,
    positionMs: num(v.positionMs) ?? 0,
    updatedAt: num(v.updatedAt) ?? 0,
    updatedBy: str(v.updatedBy) ?? '',
    seq: num(v.seq) ?? 0
  }
}

export function parseMember(uid: string, value: unknown): Member | null {
  const v = obj(value)
  if (v === null) return null
  const name = str(v.name)
  if (name === null) return null
  return {
    uid,
    name,
    online: v.online === true,
    positionMs: num(v.positionMs),
    reportedAt: num(v.reportedAt),
    lastSeen: num(v.lastSeen),
    inVoice: v.voice === true
  }
}

function parseTitle(value: unknown): RoomTitle | null {
  const v = obj(value)
  if (v === null) return null
  const service = serviceId(v.service)
  const deepLink = str(v.deepLink)
  if (service === null || deepLink === null) return null
  return { service, deepLink, setBy: str(v.setBy) ?? '' }
}

function parseCountdown(value: unknown): Countdown | null {
  const v = obj(value)
  if (v === null) return null
  const requestedAt = num(v.requestedAt)
  const leadMs = num(v.leadMs)
  if (requestedAt === null || leadMs === null) return null
  return { requestedAt, leadMs, startedBy: str(v.startedBy) ?? '' }
}

/** previews/{code}. An unknown service parses to null rather than failing (as on Android). */
export function parsePreview(code: string, value: unknown): RoomPreview | null {
  const v = obj(value)
  if (v === null) return null
  const hostName = str(v.hostName)
  if (hostName === null) return null
  return {
    code,
    hostName,
    service: serviceId(v.service),
    title: str(v.title),
    subtitle: str(v.subtitle),
    durationMs: num(v.durationMs),
    playableRef: str(v.playableRef),
    updatedAt: num(v.updatedAt) ?? 0
  }
}

// ---------------------------------------------------------------------------------------------
// Write payloads. Firebase's sentinels (serverTimestamp(), increment(1)) are passed in so this
// stays free of the SDK.
// ---------------------------------------------------------------------------------------------

export interface ServerSentinels {
  /** ServerValue.TIMESTAMP / serverTimestamp() */
  readonly timestamp: unknown
  /** ServerValue.increment(1) / increment(1) */
  readonly increment: unknown
}

/**
 * rooms/{code}/state, one atomic multi-field update. The server stamps updatedAt and increments
 * seq while applying it, so concurrent writers are ordered by arrival at the server: last write
 * wins, and seq/updatedAt agree on which one was last. Same fields as FirebaseSyncTransport.
 */
export function stateUpdate(broadcast: StateBroadcast, uid: string, sentinels: ServerSentinels): Record<string, unknown> {
  return {
    status: broadcast.status,
    positionMs: Math.max(0, Math.round(broadcast.positionMs)),
    updatedAt: sentinels.timestamp,
    updatedBy: uid,
    seq: sentinels.increment
  }
}

/** rooms/{code}/members/{uid}: our position for the drift overlay (reportedAt = server time). */
export function positionUpdate(positionMs: number, sentinels: ServerSentinels): Record<string, unknown> {
  return { positionMs: Math.max(0, Math.round(positionMs)), reportedAt: sentinels.timestamp }
}

/**
 * rooms/{code}/countdown. requestedAt is server time; every device counts down to
 * requestedAt + leadMs on its own estimate of server time, so they all hit "play" together.
 */
export function countdownValue(leadMs: number, uid: string, sentinels: ServerSentinels): Record<string, unknown> {
  const lead = Math.min(COUNTDOWN_LEAD_RANGE.max, Math.max(COUNTDOWN_LEAD_RANGE.min, Math.round(leadMs)))
  return { requestedAt: sentinels.timestamp, leadMs: lead, startedBy: uid }
}

/**
 * previews/{code} now-playing update (host only). Null deletes a field, so a title without a
 * subtitle clears the old one, and artwork / playableRef (which a browser tab doesn't give us)
 * are cleared rather than left from a previous title, as on Android.
 */
/**
 * The public link that opens a title, from the id the extension reads off the page URL. Only the
 * shapes the database rules accept for previews/{code}/playableRef (phones open them too).
 */
export function playableRefFor(service: string, itemId: string | null | undefined): string | null {
  if (!itemId) return null
  if (service === 'NETFLIX' && /^[0-9]{5,12}$/.test(itemId)) return `https://www.netflix.com/watch/${itemId}`
  if (service === 'JIOHOTSTAR' && /^[0-9]{6,12}$/.test(itemId)) return `https://www.hotstar.com/in/movies/x/${itemId}/watch`
  if (service === 'YOUTUBE' && /^[A-Za-z0-9_-]{11}$/.test(itemId)) return `https://www.youtube.com/watch?v=${itemId}`
  return null
}

export function nowPlayingUpdate(np: NowPlaying, sentinels: ServerSentinels): Record<string, unknown> {
  const clip = (s: string | null): string | null => (s === null ? null : s.slice(0, MAX_META_LENGTH))
  return {
    service: np.service,
    title: clip(np.title),
    subtitle: clip(np.subtitle),
    durationMs: np.durationMs !== null && Number.isFinite(np.durationMs) && np.durationMs >= 0 ? Math.round(np.durationMs) : null,
    playableRef: np.playableRef ?? null,
    artwork: null,
    updatedAt: sentinels.timestamp
  }
}
