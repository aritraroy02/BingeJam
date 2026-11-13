// Raw Realtime Database values → typed records, like Android's SocialParser / RoomParser /
// ChatRoomLinks.parse / PresenceStatus. Tolerant of missing or odd fields; never throws. Pure.
import { isAppId, isServiceId, type AppId, type ServiceId } from './catalog.ts'
import { isRoomCode } from './ids.ts'

/** A snapshot's children in the order Firebase gave them (query order). */
export type Children = ReadonlyArray<readonly [string, unknown]>

type Obj = Record<string, unknown>

function obj(v: unknown): Obj | null {
  return v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as Obj) : null
}
function str(v: unknown): string | null {
  return typeof v === 'string' ? v : null
}
function num(v: unknown): number | null {
  return typeof v === 'number' && Number.isFinite(v) ? v : null
}
function nonBlank(v: unknown): string | null {
  const s = str(v)?.trim()
  return s ? s : null
}

// ---------- Profiles ----------

/** users/{uid}. Readable by every signed-in user. */
export interface Profile {
  uid: string
  username: string
  displayName: string
  photoUrl: string | null
  bio: string | null
  createdAt: number
  /** Settings → Privacy: may friends add us to a group directly? Missing = "ask". */
  groupInvites: 'ask' | 'direct'
}

export function parseProfile(uid: string, v: unknown): Profile | null {
  const o = obj(v)
  const username = str(o?.username)
  const displayName = str(o?.displayName)
  if (!o || !username || !displayName) return null
  return {
    uid,
    username,
    displayName,
    photoUrl: str(o.photoUrl),
    bio: str(o.bio),
    createdAt: num(o.createdAt) ?? 0,
    groupInvites: o.groupInvites === 'direct' ? 'direct' : 'ask'
  }
}

// ---------- Presence ----------

export interface Activity {
  app: AppId
  title: string | null
  subtitle: string | null
  playing: boolean
  durationMs: number | null
  startedAt: number
  inWatchParty: boolean
  watchingWith: string | null
}

export interface Presence {
  /** Legacy flag (phones) OR any device entry online. */
  online: boolean
  /** Latest of the legacy lastSeen and every device's lastSeen (all server time). */
  lastSeen: number | null
  /** The phone's presence/{uid}/activity, else what an online PC is playing (devices/{id}/activity). */
  activity: Activity | null
  /** A game running on an online PC (devices/{id}/game): "Playing VALORANT". */
  game?: { name: string; startedAt: number } | null
  /** An online device set Do not disturb (devices/{id}/status = "dnd"). */
  dnd?: boolean
}

export function parseActivity(v: unknown): Activity | null {
  const o = obj(v)
  if (!o || !isAppId(o.app)) return null
  return {
    app: o.app,
    title: str(o.title),
    subtitle: str(o.subtitle),
    playing: o.playing === true,
    durationMs: num(o.durationMs),
    startedAt: num(o.startedAt) ?? 0,
    inWatchParty: o.inWatchParty === true,
    watchingWith: nonBlank(o.watchingWith)
  }
}

/**
 * Online / last seen across all of a user's devices: exactly PresenceStatus.of on Android.
 * presence/{uid}/online + lastSeen are the phone's; presence/{uid}/devices/{id} are per-device
 * entries (this desktop app). Online if any says so; last seen = the latest number of all.
 */
export function aggregatePresence(
  online: unknown,
  lastSeen: unknown,
  devices: readonly unknown[]
): { online: boolean; lastSeen: number | null } {
  let anyOnline = online === true
  let latest = num(lastSeen)
  for (const entry of devices) {
    const device = obj(entry)
    if (!device) continue
    if (device.online === true) anyOnline = true
    const seen = num(device.lastSeen)
    if (seen === null) continue
    if (latest === null || seen > latest) latest = seen
  }
  return { online: anyOnline, lastSeen: latest }
}

/** presence/{uid}; null when missing (or not readable: friends only). */
export function parsePresence(v: unknown): Presence | null {
  const o = obj(v)
  if (!o) return null
  const devices = Object.values(obj(o.devices) ?? {})
  return {
    ...aggregatePresence(o.online, o.lastSeen, devices),
    activity: parseActivity(o.activity) ?? deviceActivity(devices),
    game: deviceGame(devices),
    dnd: devices.some((d) => obj(d)?.online === true && obj(d)?.status === 'dnd')
  }
}

/** The first online device's game. */
export function deviceGame(devices: readonly unknown[]): { name: string; startedAt: number } | null {
  for (const entry of devices) {
    const device = obj(entry)
    if (device?.online !== true) continue
    const g = obj(device.game)
    const name = nonBlank(g?.name)
    if (name) return { name: name.slice(0, 60), startedAt: num(g?.startedAt) ?? 0 }
  }
  return null
}

/** The first online device's activity (the PC app's browser tab), for when the phone shows none. */
export function deviceActivity(devices: readonly unknown[]): Activity | null {
  for (const entry of devices) {
    const device = obj(entry)
    if (device?.online !== true) continue
    const a = parseActivity(device.activity)
    if (a) return a
  }
  return null
}

// ---------- Messages ----------

export interface TextMessage {
  kind: 'text'
  id: string
  from: string
  /** Empty when deleted for everyone. */
  text: string
  sentAt: number
  forwarded: boolean
  deleted: boolean
}

/** "Watched X together" card in a DM (dms/{dmId}/w-…, kind "watch"). Never unread, never notifies. */
export interface WatchCard {
  kind: 'watched'
  id: string
  by: string
  title: string
  service: ServiceId | null
  durationMs: number
  items: number
  sentAt: number
}

export type ChatMessage = TextMessage | WatchCard

/** dms/{dmId}/{id}: a message, a message deleted for everyone, or a watch card. Unknown kinds: skipped. */
export function parseDmMessage(id: string, v: unknown): ChatMessage | null {
  const o = obj(v)
  if (!o) return null
  const sentAt = num(o.sentAt) ?? 0
  if (o.kind !== undefined) {
    if (o.kind !== 'watch') return null
    const title = nonBlank(o.title)
    const durationMs = num(o.durationMs)
    if (!title || durationMs === null) return null
    return {
      kind: 'watched',
      id,
      by: str(o.by) ?? '',
      title,
      service: isServiceId(o.service) ? o.service : null,
      durationMs,
      items: Math.max(1, Math.floor(num(o.items) ?? 1)),
      sentAt
    }
  }
  const from = str(o.from)
  if (!from) return null
  if (o.deleted === true) return { kind: 'text', id, from, text: '', sentAt, forwarded: false, deleted: true }
  const text = str(o.text)
  if (text === null) return null
  return { kind: 'text', id, from, text, sentAt, forwarded: o.forwarded === true, deleted: false }
}

/** groupMessages/{gid}/{id}: {from, text, sentAt}. */
export function parseGroupMessage(id: string, v: unknown): TextMessage | null {
  const o = obj(v)
  const from = str(o?.from)
  const text = str(o?.text)
  if (!o || !from || text === null) return null
  return { kind: 'text', id, from, text, sentAt: num(o.sentAt) ?? 0, forwarded: false, deleted: false }
}

export function parseList<T>(children: Children, parse: (id: string, v: unknown) => T | null): T[] {
  const out: T[] = []
  for (const [id, v] of children) {
    const item = parse(id, v)
    if (item !== null) out.push(item)
  }
  return out
}

// ---------- Groups and chat rooms ----------

export interface GroupMember {
  uid: string
  admin: boolean
  joinedAt: number
}

export interface Group {
  id: string
  name: string
  createdBy: string
  createdAt: number
  /** Oldest first. */
  members: GroupMember[]
  onlyAdminsSend: boolean
  onlyAdminsEdit: boolean
}

export function parseGroup(id: string, v: unknown): Group | null {
  const o = obj(v)
  const name = nonBlank(o?.name)
  if (!o || !name) return null
  const members = Object.entries(obj(o.members) ?? {})
    .map(([uid, m]) => ({ uid, admin: obj(m)?.role === 'admin', joinedAt: num(obj(m)?.joinedAt) ?? 0 }))
    .sort((a, b) => a.joinedAt - b.joinedAt)
  const settings = obj(o.settings)
  return {
    id,
    name,
    createdBy: str(o.createdBy) ?? '',
    createdAt: num(o.createdAt) ?? 0,
    members,
    // groups/{gid}/settings (2026-11-10): who may send and who may edit the group's info. Missing = everyone.
    onlyAdminsSend: settings?.send === 'admins',
    onlyAdminsEdit: settings?.edit === 'admins'
  }
}

/** dmRooms/{dmId} or groupRooms/{gid}. Null unless live (someone in the "in" list). */
export interface RoomLink {
  code: string
  startedBy: string
  startedAt: number
  /** uids in the room right now, sorted. */
  inRoom: string[]
}

export function parseRoomLink(v: unknown): RoomLink | null {
  const o = obj(v)
  const code = str(o?.code)
  if (!o || !code || !isRoomCode(code)) return null
  const inRoom = Object.entries(obj(o.in) ?? {})
    .filter(([, value]) => value === true)
    .map(([uid]) => uid)
    .sort()
  if (inRoom.length === 0) return null
  return { code, startedBy: str(o.startedBy) ?? '', startedAt: num(o.startedAt) ?? 0, inRoom }
}

/** previews/{code}: what the room's host is playing. Readable by any signed-in user. */
export interface Preview {
  hostName: string
  service: ServiceId | null
  title: string | null
  subtitle: string | null
  durationMs: number | null
}

export function parsePreview(v: unknown): Preview | null {
  const o = obj(v)
  if (!o) return null
  return {
    hostName: str(o.hostName) ?? '',
    service: isServiceId(o.service) ? o.service : null,
    title: nonBlank(o.title),
    subtitle: nonBlank(o.subtitle),
    durationMs: num(o.durationMs)
  }
}

/** rooms/{code}/members: who's talking (only readable while we're a member). */
export function parseVoiceMembers(v: unknown): Set<string> {
  const out = new Set<string>()
  for (const [uid, m] of Object.entries(obj(v) ?? {})) if (obj(m)?.voice === true && obj(m)?.online === true) out.add(uid)
  return out
}

// ---------- Requests and invites ----------

export interface FriendRequest {
  fromUid: string
  fromUsername: string
  fromDisplayName: string
  sentAt: number
  /** The invite token when the request came from our invite link. */
  via: string | null
}

export function parseFriendRequest(fromUid: string, v: unknown): FriendRequest | null {
  const o = obj(v)
  if (!o) return null
  return { fromUid, fromUsername: str(o.fromUsername) ?? '', fromDisplayName: str(o.fromDisplayName) ?? '', sentAt: num(o.sentAt) ?? 0, via: str(o.via) ?? null }
}

export interface GroupInvite {
  groupId: string
  groupName: string
  fromUid: string
  fromName: string
  sentAt: number
}

export function parseGroupInvite(groupId: string, v: unknown): GroupInvite | null {
  const o = obj(v)
  if (!o) return null
  return {
    groupId,
    groupName: nonBlank(o.groupName) ?? 'a group',
    fromUid: str(o.from) ?? '',
    fromName: str(o.fromName) ?? '',
    sentAt: num(o.sentAt) ?? 0
  }
}

// ---------- Small shapes ----------

/** friends/{me}, userGroups/{me}: just the keys. */
export function parseKeys(children: Children): string[] {
  return children.map(([key]) => key)
}

/** dmReads/{me}, dmCleared/{me}, groupReads/{me}: key → server time. */
export function parseNumberMap(children: Children): Map<string, number> {
  const out = new Map<string, number>()
  for (const [key, v] of children) {
    const n = num(v)
    if (n !== null) out.set(key, n)
  }
  return out
}

/** dmHidden/{me}: dmId → ids deleted for me. */
export function parseHidden(v: unknown): Map<string, Set<string>> {
  const out = new Map<string, Set<string>>()
  for (const [dm, ids] of Object.entries(obj(v) ?? {})) out.set(dm, new Set(Object.keys(obj(ids) ?? {})))
  return out
}
