import { useSyncExternalStore } from 'react'
import { get, onDisconnect, onValue, ref, remove, runTransaction, serverTimestamp, set, update } from 'firebase/database'
import { db } from '@/lib/firebase'
import { dmFriend, isRoomCode, newRoomCode } from './ids.ts'
import { isPermissionDenied, requireUid } from './live.ts'
import { parseProfile, parseRoomLink } from './parse.ts'
import { notifyRelay } from './relay.ts'
import { initialPreview, linkStartPayload, memberFields, newRoomPayload, PING_COOLDOWN_MS, roomInvite } from './roomModel.ts'

// The room that belongs to a chat (the social side; the play/pause state sync is the engine's).
// Same as Android: the room is rooms/{code} in today's format (FirebaseSyncTransport), and the
// chat's link dmRooms/{dmId} or groupRooms/{gid} = {code, startedBy, startedAt, in/{uid}} keeps
// its own "who's in" list, because rooms are member-read only (ChatRoomLinks + ChatRoomTracker).
// While we're in, both rooms/{code}/members/{me}/online and in/{me} are kept alive: every
// (re)connect first registers the onDisconnect, then writes the value again.
//
// One account on two devices: both nodes are per uid, not per device, so if the phone and this PC
// are in the same room, whichever leaves or drops first takes the account off the list.

interface Active {
  chatId: string
  code: string
  linkPath: string
  me: string
  /** Our display name, so a reconnect adds us back if the last one out removed us while offline. */
  name: string
}

let active: Active | null = null
let activeView: { chatId: string; code: string } | null = null
let stopConnection: (() => void) | null = null
const roomListeners = new Set<() => void>()

function setActive(next: Active | null): void {
  active = next
  activeView = next ? { chatId: next.chatId, code: next.code } : null
  for (const l of roomListeners) l()
}

export function activeChatRoom(): { chatId: string; code: string } | null {
  return activeView
}

/** The chat room this PC is in right now (one at a time), or null. */
export function useActiveChatRoom(): { chatId: string; code: string } | null {
  return useSyncExternalStore(
    (l) => {
      roomListeners.add(l)
      return () => roomListeners.delete(l)
    },
    activeChatRoom
  )
}

function linkPathFor(chatId: string, me: string): string {
  return dmFriend(chatId, me) ? `dmRooms/${chatId}` : `groupRooms/${chatId}`
}

function log(what: string): (e: unknown) => void {
  return (e) => console.debug(`Chat room: ${what} failed`, e)
}

/** onDisconnect first, so neither entry can outlive the connection; both go out in this order. */
function arm(a: Active): void {
  const member = ref(db, `rooms/${a.code}/members/${a.me}`)
  onDisconnect(member)
    .update({ online: false, lastSeen: serverTimestamp() })
    .then(() => update(member, { name: a.name, online: true, lastSeen: serverTimestamp() }))
    .catch(log('member presence'))
  const mark = ref(db, `${a.linkPath}/in/${a.me}`)
  onDisconnect(mark).remove().catch(log('link onDisconnect'))
  set(mark, true).catch(log('link presence'))
}

function keepAlive(a: Active): void {
  stopConnection?.()
  stopConnection = onValue(ref(db, '.info/connected'), (s) => {
    if (s.val() === true && active === a) arm(a)
  })
}

async function myName(me: string): Promise<string> {
  const profile = parseProfile(me, (await get(ref(db, `users/${me}`))).val())
  return profile?.displayName ?? 'Guest'
}

/** Joining = writing our own member node; the rules refuse it if the room doesn't exist. */
async function joinRoom(code: string, me: string, name: string): Promise<void> {
  await update(ref(db, `rooms/${code}/members/${me}`), memberFields(name, serverTimestamp()))
}

/** Rules only allow creating a room that doesn't exist yet, so a taken code is "permission denied". */
async function createRoom(me: string, name: string): Promise<string> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = newRoomCode()
    try {
      await set(ref(db, `rooms/${code}`), newRoomPayload(me, name, serverTimestamp()))
    } catch (e) {
      if (isPermissionDenied(e)) continue
      throw e
    }
    // The room works without a preview; invitees just see the bare code.
    await set(ref(db, `previews/${code}`), initialPreview(name, serverTimestamp())).catch(log('preview'))
    return code
  }
  throw new Error("Couldn't create a room (permission denied). Check the database rules.")
}

/** invites/{friend}/{me} (a pop-up on their phone): the rules need us to be a member of that room. */
async function inviteFriend(friend: string, me: string, code: string): Promise<void> {
  const profile = parseProfile(me, (await get(ref(db, `users/${me}`))).val())
  if (!profile) throw new Error('Pick a username first')
  await set(ref(db, `invites/${friend}/${me}`), roomInvite(profile.username, profile.displayName, code, serverTimestamp()))
  void notifyRelay({ type: 'invite', to: friend })
}

async function enter(a: Active): Promise<void> {
  const previous = active
  if (previous && (previous.chatId !== a.chatId || previous.code !== a.code)) await leaveChatRoom()
  setActive(a)
  keepAlive(a)
}

/**
 * The chat header's "Start room" / "Join": joins the chat's live room, or creates a room
 * (rooms/{code} + previews/{code}), links it to the chat and, for a 1:1 chat, invites the friend.
 * [reuseCode]: a room we're already syncing in to link instead of creating one (Android does this).
 * Returns the room code for the room screen / sync engine.
 */
export async function startOrJoinChatRoom(chatId: string, options: { reuseCode?: string } = {}): Promise<string> {
  const me = requireUid()
  if (active?.chatId === chatId) return active.code
  const friend = dmFriend(chatId, me)
  const linkPath = linkPathFor(chatId, me)
  const name = await myName(me)
  let link
  try {
    link = parseRoomLink((await get(ref(db, linkPath))).val())
  } catch (e) {
    if (isPermissionDenied(e)) throw new Error(friend ? 'You can only start a room with friends' : "You're not in this group")
    throw e
  }
  if (link) {
    try {
      await joinRoom(link.code, me, name)
      await enter({ chatId, code: link.code, linkPath, me, name })
      return link.code
    } catch (e) {
      // The linked room is gone: start a new one below.
      if (!isPermissionDenied(e)) throw e
    }
  }
  let code: string
  if (options.reuseCode && isRoomCode(options.reuseCode)) {
    code = options.reuseCode
    await joinRoom(code, me, name)
  } else {
    code = await createRoom(me, name)
  }
  await set(ref(db, linkPath), linkStartPayload(me, code, serverTimestamp()))
  await enter({ chatId, code, linkPath, me, name })
  if (friend) await inviteFriend(friend, me, code).catch(log('invite'))
  return code
}

/**
 * Leaves the chat room we're in (optionally only if it's [chatId]'s): off the chat's "in" list and
 * out of rooms/{code}/members, like Android's ChatRoomTracker + FirebaseSyncTransport.leaveRoom.
 * The last one out also deletes the room (2026-11-10, same as the phone): when nobody else is
 * online, offline members go with us, then the room, its preview, chat and reactions, and the
 * chat's link if its "in" list is empty. Call it before signing out.
 */
export async function leaveChatRoom(chatId?: string): Promise<void> {
  const a = active
  if (!a || (chatId && a.chatId !== chatId)) return
  stopConnection?.()
  stopConnection = null
  setActive(null)
  const members = ref(db, `rooms/${a.code}/members`)
  const member = ref(db, `rooms/${a.code}/members/${a.me}`)
  const mark = ref(db, `${a.linkPath}/in/${a.me}`)
  await Promise.allSettled([onDisconnect(member).cancel(), onDisconnect(mark).cancel()])
  // Read before leaving: once we're out we can't read the room.
  const others = await get(members)
    .then((s) => Object.entries((s.val() ?? {}) as Record<string, { online?: unknown }>).filter(([uid]) => uid !== a.me))
    .catch(() => null)
  await remove(mark).catch(log('link presence'))
  await dropLinkIfEmpty(a.linkPath, a.code)
  if (others === null || others.some(([, m]) => m?.online === true)) {
    await remove(member).catch(log('leave'))
    return
  }
  const leaving: Record<string, null> = { [a.me]: null }
  for (const [uid] of others) leaving[uid] = null
  try {
    await update(members, leaving)
  } catch (e) {
    // Refused (someone came back online meanwhile): just leave, as before.
    log('remove offline members')(e)
    await remove(member).catch(log('leave'))
    return
  }
  await update(ref(db), {
    [`rooms/${a.code}`]: null,
    [`previews/${a.code}`]: null,
    [`chats/${a.code}`]: null,
    [`reactions/${a.code}`]: null
  }).catch(log('delete room'))
}

/** Removes the chat's room link when nobody's in it (a transaction, so a room started meanwhile stays). */
async function dropLinkIfEmpty(linkPath: string, code: string): Promise<void> {
  await runTransaction(ref(db, linkPath), (current: { code?: unknown; in?: Record<string, unknown> } | null) => {
    if (current === null) return current
    if (current.code !== code) return undefined
    if (current.in && Object.keys(current.in).length > 0) return undefined
    return null
  }).catch(log('drop room link'))
}

// ---------- Pings ----------

// When we last pinged someone (monotonic performance.now()), per chat + uid. The relay enforces the
// real 2-minute limit (KV ping:*); this only drives "Pinged · 1 min" and avoids pointless requests.
const pings = new Map<string, number>()
let pingsVersion = 0
const pingListeners = new Set<() => void>()

export function pingAgeMs(chatId: string, uid: string): number | null {
  const at = pings.get(`${chatId}:${uid}`)
  return at === undefined ? null : performance.now() - at
}

export function subscribePings(listener: () => void): () => void {
  pingListeners.add(listener)
  return () => pingListeners.delete(listener)
}

export function pingVersion(): number {
  return pingsVersion
}

/**
 * "Ping" a member who isn't in the room. Groups: relay room_ping (it checks both are members and
 * the room is live). 1:1 chats have no room_ping on the relay or in the rules, so this re-sends the
 * room invite (what Android's Start room sends), which needs us to be in the room.
 */
export async function pingMember(chatId: string, uid: string): Promise<'sent' | 'cooldown'> {
  const me = requireUid()
  if (uid === me) throw new Error("You can't ping yourself")
  const age = pingAgeMs(chatId, uid)
  if (age !== null && age < PING_COOLDOWN_MS) return 'cooldown'
  const friend = dmFriend(chatId, me)
  if (friend) {
    if (uid !== friend) throw new Error('Not in this chat')
    const code = active?.chatId === chatId ? active.code : parseRoomLink((await get(ref(db, `dmRooms/${chatId}`))).val())?.code
    if (!code) throw new Error("The room isn't running")
    await inviteFriend(friend, me, code)
  } else {
    await notifyRelay({ type: 'room_ping', group: chatId, to: uid })
  }
  pings.set(`${chatId}:${uid}`, performance.now())
  pingsVersion++
  for (const l of pingListeners) l()
  return 'sent'
}
