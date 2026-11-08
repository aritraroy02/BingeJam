import { onDisconnect, onValue, ref, remove, serverTimestamp, update } from 'firebase/database'
import { db } from '@/lib/firebase'

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
      .update({ kind: 'desktop', online: false, lastSeen: serverTimestamp(), label })
      .then(() => update(entry, { kind: 'desktop', online: true, lastSeen: serverTimestamp(), label }))
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
    .then(() => update(entry, { kind: 'desktop', online: false, lastSeen: serverTimestamp(), label: deviceLabel() }))
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
