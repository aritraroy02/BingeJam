import { useMemo } from 'react'
import { get, ref, serverTimestamp, set, update } from 'firebase/database'
import type { FriendVM } from '@/ui/models'
import { db } from '@/lib/firebase'
import { useServerNow } from './clock.ts'
import { isPermissionDenied, requireUid, useLive, usePresences, useProfiles, useUid } from './live.ts'
import { parseFriendRequest, parseKeys, parsePresence, parseProfile, type FriendRequest } from './parse.ts'
import { notifyRelay } from './relay.ts'
import { buildFriendVMs, visibleActivity, type FriendRecord } from './status.ts'
import { isValidUsername, normalizeUsername, usernameKey } from './usernames.ts'

// Friends and friend requests, the same reads and multi-path writes as Android's
// FirebaseFriendsRepository:
//   friendRequests/{to}/{from} + sentFriendRequests/{from}/{to}, friends/{a}/{b} + friends/{b}/{a} {since}.

/** friends/{me} with each friend's profile and presence (all devices); null while loading. */
export function useFriendRecords(): FriendRecord[] | null {
  const me = useUid()
  const list = useLive(me ? `friends/${me}` : null)
  const uids = useMemo(() => (list ? parseKeys(list.children) : []), [list])
  const profiles = useProfiles(uids)
  const presences = usePresences(uids)
  return useMemo(() => {
    if (!me || !list) return null
    // Wait for every profile (like Android's combine), so the list doesn't fill in one by one.
    if (uids.some((uid) => !profiles.has(uid))) return null
    return uids.flatMap((uid) => {
      const profile = profiles.get(uid)
      return profile ? [{ uid, profile, presence: presences.get(uid) ?? null }] : []
    })
  }, [me, list, uids, profiles, presences])
}

/** Home / Friends list: status line and join/jam action as on the phone. Null while loading. */
export function useFriends(): FriendVM[] | null {
  const records = useFriendRecords()
  const now = useServerNow()
  return useMemo(() => (records ? buildFriendVMs(records, now) : null), [records, now])
}

export interface FriendRequestVM {
  fromUid: string
  name: string
  username: string
  photoUrl: string | null
  sentAt: number
}

/** friendRequests/{me}: newest first. Null while loading. */
export function useFriendRequests(): FriendRequestVM[] | null {
  const me = useUid()
  const snap = useLive(me ? `friendRequests/${me}` : null)
  const requests = useMemo(
    () => (snap ? snap.children.flatMap(([from, v]) => parseFriendRequest(from, v) ?? []).sort((a, b) => b.sentAt - a.sentAt) : null),
    [snap]
  )
  const profiles = useProfiles(requests?.map((r) => r.fromUid) ?? [])
  return useMemo(
    () =>
      requests?.map((r: FriendRequest) => {
        const p = profiles.get(r.fromUid)
        return { fromUid: r.fromUid, name: p?.displayName ?? r.fromDisplayName, username: p?.username ?? r.fromUsername, photoUrl: p?.photoUrl ?? null, sentAt: r.sentAt }
      }) ?? null,
    [requests, profiles]
  )
}

export class FriendRequestError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'FriendRequestError'
  }
}

async function exists(path: string): Promise<boolean> {
  return (await get(ref(db, path))).exists()
}

/**
 * By exact username only (usernames/{key}; prefix listing is denied). If they already asked us,
 * this accepts. Then the relay notifies their phone.
 */
export async function sendFriendRequest(exactUsername: string): Promise<'sent' | 'accepted' | 'alreadyPending'> {
  const me = requireUid()
  const name = normalizeUsername(exactUsername)
  if (!isValidUsername(name)) throw new FriendRequestError(`No one is called @${name}`)
  const mine = parseProfile(me, (await get(ref(db, `users/${me}`))).val())
  if (!mine) throw new FriendRequestError('Pick a username first')
  const them = (await get(ref(db, `usernames/${usernameKey(name)}`))).val()
  if (typeof them !== 'string') throw new FriendRequestError(`No one is called @${name}`)
  if (them === me) throw new FriendRequestError("That's you!")
  if (await exists(`friends/${me}/${them}`)) throw new FriendRequestError(`You're already friends with @${name}`)
  if (await exists(`blocked/${me}/${them}`)) throw new FriendRequestError(`You blocked @${name}. Unblock them first.`)
  // They already asked us: sending one back means yes.
  if (await exists(`friendRequests/${me}/${them}`)) {
    await acceptFriendRequest(them)
    return 'accepted'
  }
  if (await exists(`friendRequests/${them}/${me}`)) return 'alreadyPending'
  const theirs = parseProfile(them, (await get(ref(db, `users/${them}`))).val())
  if (!theirs) throw new FriendRequestError(`No one is called @${name}`)
  try {
    await update(ref(db), {
      [`friendRequests/${them}/${me}`]: {
        fromUsername: mine.username,
        fromDisplayName: mine.displayName,
        toUsername: theirs.username,
        toDisplayName: theirs.displayName,
        sentAt: serverTimestamp()
      },
      [`sentFriendRequests/${me}/${them}`]: true
    })
  } catch (e) {
    // We can't read their block list, so a denial here almost always means they blocked us.
    if (isPermissionDenied(e)) throw new FriendRequestError(`You can't send a request to @${name}`)
    throw e
  }
  void notifyRelay({ type: 'friend_request', to: them })
  return 'sent'
}

/** Removes requests both ways between a and b, plus the senders' indexes. */
function requestCleanup(a: string, b: string): Record<string, null> {
  return {
    [`friendRequests/${a}/${b}`]: null,
    [`friendRequests/${b}/${a}`]: null,
    [`sentFriendRequests/${a}/${b}`]: null,
    [`sentFriendRequests/${b}/${a}`]: null
  }
}

export async function acceptFriendRequest(fromUid: string): Promise<void> {
  const me = requireUid()
  if (!(await exists(`friendRequests/${me}/${fromUid}`))) throw new FriendRequestError('That request is no longer available')
  await update(ref(db), {
    [`friends/${me}/${fromUid}`]: { since: serverTimestamp() },
    [`friends/${fromUid}/${me}`]: { since: serverTimestamp() },
    ...requestCleanup(me, fromUid)
  })
}

export async function declineFriendRequest(fromUid: string): Promise<void> {
  const me = requireUid()
  await update(ref(db), { [`friendRequests/${me}/${fromUid}`]: null, [`sentFriendRequests/${fromUid}/${me}`]: null })
}

/**
 * The friend card's "Ask to join" / "Ask to jam" (FirebaseWatchInvites.requestToJoin): writes
 * joinRequests/{host}/{me} as pending with what they're playing, then pops up on their phone.
 */
export async function requestToJoin(hostUid: string): Promise<void> {
  const me = requireUid()
  if (hostUid === me) throw new Error("That's your own activity")
  const mine = parseProfile(me, (await get(ref(db, `users/${me}`))).val())
  if (!mine) throw new Error('Pick a username first')
  const activity = visibleActivity(parsePresence((await get(ref(db, `presence/${hostUid}`))).val()))
  if (!activity) throw new Error("They aren't playing anything right now")
  const request: Record<string, unknown> = {
    fromUsername: mine.username,
    fromDisplayName: mine.displayName,
    app: activity.app,
    status: 'pending',
    sentAt: serverTimestamp()
  }
  const title = activity.title?.trim().slice(0, 200)
  if (title) request.title = title
  try {
    await set(ref(db, `joinRequests/${hostUid}/${me}`), request)
  } catch (e) {
    if (isPermissionDenied(e)) throw new Error('You can only do that with friends')
    throw e
  }
  void notifyRelay({ type: 'join_request', to: hostUid })
}
