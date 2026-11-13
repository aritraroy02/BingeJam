import { onDisconnect, onValue, ref, remove, serverTimestamp, set, update } from 'firebase/database'
import { db } from '@/lib/firebase'
import { serverNow } from './clock.ts'

// This PC's own online state: presence/{uid}/devices/{deviceId} = {kind: "desktop", online,
// lastSeen (server time), label}. Never the shared presence/{uid}/online|lastSeen|activity: those
// are the phone's, and its onDisconnect clears them, so a PC writing there would be wiped (or
// would wipe the phone's live activity). Friends see someone online when the phone OR any device
// is (aggregatePresence in parse.ts, PresenceStatus.kt on Android).
//
// Connection: like Android's presence, every (re)connect first registers the server-side
// onDisconnect (offline + lastSeen), then marks us online. Rules (presence/$uid/devices, pending
// deploy): kind phone|desktop, online boolean, lastSeen === now, label 1–40 chars, id
// [A-Za-z0-9_-]{8,40}. Until those rules are live every write here is refused; that's logged only.

const DEVICE_KEY = 'bj.deviceId'
const ID_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-'
let cachedId: string | null = null

/** Random [A-Za-z0-9_-]{20}, made once per install (localStorage). Stable for the session even if storage is blocked. */
export function deviceId(): string {
  if (cachedId) return cachedId
  try {
    const stored = localStorage.getItem(DEVICE_KEY)
    if (stored && /^[A-Za-z0-9_-]{20}$/.test(stored)) return (cachedId = stored)
  } catch {
    // Storage blocked: a new id this session.
  }
  const bytes = crypto.getRandomValues(new Uint8Array(20))
  cachedId = Array.from(bytes, (b) => ID_ALPHABET[b % 64]).join('')
  try {
    localStorage.setItem(DEVICE_KEY, cachedId)
  } catch {
    // ignore
  }
  return cachedId
}

function deviceLabel(): string {
  const platform = (globalThis as { bingejam?: { platform?: string } }).bingejam?.platform
  if (platform === 'win32') return 'Windows PC'
  if (platform === 'darwin') return 'Mac'
  if (platform === 'linux') return 'Linux PC'
  return 'Computer'
}

const invisibleKey = (uid: string): string => `bj.invisible.${uid}`

/** Desktop "Invisible" (per account, this PC only): friends don't see this PC online. */
export function isInvisible(uid: string): boolean {
  try {
    return localStorage.getItem(invisibleKey(uid)) === '1'
  } catch {
    return false
  }
}

let wantedUid: string | null = null
let connection: { uid: string; stop: () => void } | null = null

function deviceRef(uid: string): ReturnType<typeof ref> {
  return ref(db, `presence/${uid}/devices/${deviceId()}`)
}

function logRefused(what: string): (e: unknown) => void {
  return (e) => console.debug(`Device presence: ${what} refused`, e)
}

function connect(uid: string): void {
  if (connection?.uid === uid) return
  disconnectListener()
  const entry = deviceRef(uid)
  const label = deviceLabel()
  const stop = onValue(ref(db, '.info/connected'), (s) => {
    if (s.val() !== true) return
    // onDisconnect first, so the "online" entry can't outlive the connection; Firebase runs it once
    // per connection, so it's registered again on every reconnect. Rules check it on registration,
    // when the entry may not exist yet, so it carries every required field.
    onDisconnect(entry)
      .update({ kind: 'desktop', online: false, lastSeen: serverTimestamp(), label, activity: null, game: null })
      .then(() => update(entry, { kind: 'desktop', online: true, lastSeen: serverTimestamp(), label, status: isDnd(uid) ? 'dnd' : 'online' }))
      .then(() => {
        // The previous connection's onDisconnect removed the activity: write it again.
        activityKey = ''
        if (lastActivity?.uid === uid) setDeviceActivity(uid, lastActivity.a)
        gameKey = ''
        if (lastGame?.uid === uid) setDeviceGame(uid, lastGame.game)
      })
      .catch(logRefused('online'))
  })
  connection = { uid, stop }
}

function disconnectListener(): void {
  connection?.stop()
  connection = null
}

function markOffline(uid: string): Promise<void> {
  const entry = deviceRef(uid)
  return onDisconnect(entry)
    .cancel()
    .then(() => update(entry, { kind: 'desktop', online: false, lastSeen: serverTimestamp(), label: deviceLabel(), activity: null, game: null }))
    .catch(logRefused('offline'))
}

/** Never let an offline write (no network) hold up sign-out. */
function bounded(p: Promise<void>, ms = 3_000): Promise<void> {
  return Promise.race([p, new Promise<void>((resolve) => setTimeout(resolve, ms))])
}

/**
 * Starts this PC's presence for [uid] (call after sign-in). Returns stop(): it removes our device
 * entry (sign-out) and resolves within 3 s even offline. Await it before signing out: the write
 * needs our token.
 */
export function startDevicePresence(uid: string): () => Promise<void> {
  wantedUid = uid
  if (isInvisible(uid)) {
    disconnectListener()
    void markOffline(uid)
  } else {
    connect(uid)
  }
  return async () => {
    if (wantedUid !== uid) return
    wantedUid = null
    disconnectListener()
    const entry = deviceRef(uid)
    await bounded(
      onDisconnect(entry)
        .cancel()
        .then(() => remove(entry))
        .catch(logRefused('remove'))
    )
  }
}

/** Invisible on: this PC shows offline (online: false) and stops re-marking itself online. */
export async function setInvisible(invisible: boolean, uid: string | null = wantedUid): Promise<void> {
  if (!uid) return
  try {
    if (invisible) localStorage.setItem(invisibleKey(uid), '1')
    else localStorage.removeItem(invisibleKey(uid))
  } catch {
    // Not remembered across restarts; still applied now.
  }
  if (wantedUid !== uid) return
  if (invisible) {
    disconnectListener()
    await bounded(markOffline(uid))
  } else {
    connect(uid)
  }
}

// ---------- What this PC is playing (devices/{id}/activity) ----------

export interface DeviceActivity {
  app: 'netflix' | 'jiohotstar' | 'primevideo' | 'youtube'
  title: string | null
  subtitle: string | null
  playing: boolean
  durationMs: number | null
  inWatchParty: boolean
  watchingWith: string | null
}

let activityKey = ''
let lastActivity: { uid: string; a: DeviceActivity | null } | null = null
let activityItem = ''

/**
 * Friends see "Watching X" for this PC (phones show it when their own phone plays nothing). Same
 * fields as the phone's presence/{uid}/activity; startedAt is set when the title changes. Nothing
 * is written while Invisible. null clears it.
 */
export function setDeviceActivity(uid: string, a: DeviceActivity | null): void {
  lastActivity = { uid, a }
  if (isInvisible(uid) || !isSharingActivity(uid)) a = null
  const key = a ? JSON.stringify(a) : ''
  if (key === activityKey) return
  activityKey = key
  const entry = ref(db, `presence/${uid}/devices/${deviceId()}/activity`)
  if (!a) {
    activityItem = ''
    void remove(entry).catch(logRefused('activity'))
    return
  }
  const item = `${a.app}|${a.title ?? ''}|${a.subtitle ?? ''}`
  const value: Record<string, unknown> = { app: a.app, playing: a.playing, inWatchParty: a.inWatchParty }
  if (a.title) value.title = a.title.slice(0, 200)
  if (a.subtitle) value.subtitle = a.subtitle.slice(0, 200)
  if (a.durationMs && a.durationMs > 0) value.durationMs = Math.round(a.durationMs)
  if (a.watchingWith) value.watchingWith = a.watchingWith.slice(0, 100)
  if (item !== activityItem) {
    activityItem = item
    // Server time a little in the past (the rules want startedAt <= now; our estimate may run ahead).
    activityStartedAt = Math.round(serverNow()) - 2_000
  }
  // Always the whole entry: a reconnect's onDisconnect may have removed it meanwhile.
  value.startedAt = activityStartedAt
  void set(entry, value).catch(logRefused('activity'))
}

let activityStartedAt = 0

// ---------- Do not disturb, "Share my activity" and the running game ----------

const dndKey = (uid: string): string => `bj.dnd.${uid}`
const shareKey = (uid: string): string => `bj.shareActivity.${uid}`

function flag(key: string, fallback: boolean): boolean {
  try {
    const v = localStorage.getItem(key)
    return v === null ? fallback : v === '1'
  } catch {
    return fallback
  }
}

function setFlag(key: string, on: boolean): void {
  try {
    localStorage.setItem(key, on ? '1' : '0')
  } catch {
    // Only this session.
  }
}

/** Do not disturb (this PC): friends see a red dot and "Do not disturb"; no pop-ups here. */
export function isDnd(uid: string): boolean {
  return flag(dndKey(uid), false)
}

export async function setDnd(on: boolean, uid: string): Promise<void> {
  setFlag(dndKey(uid), on)
  if (wantedUid !== uid || isInvisible(uid)) return
  await bounded(update(deviceRef(uid), { kind: 'desktop', online: true, lastSeen: serverTimestamp(), label: deviceLabel(), status: on ? 'dnd' : 'online' }).catch(logRefused('status')))
}

/** "Share my activity": what this PC watches or plays goes to friends (default on, like Discord). */
export function isSharingActivity(uid: string): boolean {
  return flag(shareKey(uid), true)
}

export function setSharingActivity(on: boolean, uid: string): void {
  setFlag(shareKey(uid), on)
  activityKey = ''
  gameKey = ''
  if (lastActivity?.uid === uid) setDeviceActivity(uid, lastActivity.a)
  if (lastGame?.uid === uid) setDeviceGame(uid, lastGame.game)
}

let gameKey = ''
let lastGame: { uid: string; game: string | null } | null = null

/** "Playing VALORANT" (devices/{id}/game); null clears it. Nothing while invisible or not sharing. */
export function setDeviceGame(uid: string, game: string | null): void {
  lastGame = { uid, game }
  const shown = isInvisible(uid) || !isSharingActivity(uid) ? null : game
  if ((shown ?? '') === gameKey) return
  gameKey = shown ?? ''
  const entry = ref(db, `presence/${uid}/devices/${deviceId()}/game`)
  if (!shown) {
    void remove(entry).catch(logRefused('game'))
    return
  }
  // Server time a little in the past (rules: startedAt <= now).
  void set(entry, { name: shown.slice(0, 60), startedAt: Math.round(serverNow()) - 2_000 }).catch(logRefused('game'))
}
