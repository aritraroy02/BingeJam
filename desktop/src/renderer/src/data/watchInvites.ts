import { useMemo } from 'react'
import { ref, remove, serverTimestamp, update } from 'firebase/database'
import { db } from '@/lib/firebase'
import { requireUid, useLive, useUid } from './live.ts'

// Room invites and join requests, the same nodes and fields as the phone's FirebaseWatchInvites:
//   invites/{to}/{from}       {fromUsername, fromDisplayName, roomCode, app?, title?, sentAt, declined?, answeredAt?}
//       "Not now" adds declined = true + answeredAt, so the inviter sees it; Join removes it.
//   joinRequests/{host}/{from} {fromUsername, fromDisplayName, app, title?, status, roomCode?, sentAt, answeredAt?}
//       the host only changes status (accepted + roomCode, or declined) and stamps answeredAt.

export interface RoomInvite {
  fromUid: string
  fromUsername: string
  fromName: string
  roomCode: string
  title: string | null
  sentAt: number
}

export interface JoinRequest {
  fromUid: string
  fromUsername: string
  fromName: string
  app: string
  title: string | null
  sentAt: number
}

function str(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null
}

export function parseRoomInvite(fromUid: string, v: unknown): RoomInvite | null {
  const o = (v ?? {}) as Record<string, unknown>
  const code = str(o.roomCode)
  if (!code || o.declined === true) return null
  return {
    fromUid,
    fromUsername: str(o.fromUsername) ?? '',
    fromName: str(o.fromDisplayName) ?? str(o.fromUsername) ?? 'A friend',
    roomCode: code,
    title: str(o.title),
    sentAt: typeof o.sentAt === 'number' ? o.sentAt : 0
  }
}

export function parseJoinRequest(fromUid: string, v: unknown): JoinRequest | null {
  const o = (v ?? {}) as Record<string, unknown>
  if (o.status !== 'pending' || !str(o.app)) return null
  return {
    fromUid,
    fromUsername: str(o.fromUsername) ?? '',
    fromName: str(o.fromDisplayName) ?? str(o.fromUsername) ?? 'A friend',
    app: o.app as string,
    title: str(o.title),
    sentAt: typeof o.sentAt === 'number' ? o.sentAt : 0
  }
}

/** Invites waiting for an answer, newest first; null while loading. */
export function useRoomInvites(): RoomInvite[] | null {
  const me = useUid()
  const snap = useLive(me ? `invites/${me}` : null)
  return useMemo(
    () => (snap ? snap.children.flatMap(([from, v]) => parseRoomInvite(from, v) ?? []).sort((a, b) => b.sentAt - a.sentAt) : null),
    [snap]
  )
}

/** Friends asking to join us (pending only), newest first; null while loading. */
export function useJoinRequests(): JoinRequest[] | null {
  const me = useUid()
  const snap = useLive(me ? `joinRequests/${me}` : null)
  return useMemo(
    () => (snap ? snap.children.flatMap(([from, v]) => parseJoinRequest(from, v) ?? []).sort((a, b) => b.sentAt - a.sentAt) : null),
    [snap]
  )
}

/** Joined (or gone): the invite is done. */
export async function dismissInvite(fromUid: string): Promise<void> {
  await remove(ref(db, `invites/${requireUid()}/${fromUid}`))
}

/** "Not now": the inviter's phone shows it. */
export async function declineInvite(fromUid: string): Promise<void> {
  await update(ref(db, `invites/${requireUid()}/${fromUid}`), { declined: true, answeredAt: serverTimestamp() })
}

/** Let them in: their app joins [roomCode] by itself. */
export async function acceptJoin(fromUid: string, roomCode: string): Promise<void> {
  await update(ref(db, `joinRequests/${requireUid()}/${fromUid}`), { status: 'accepted', roomCode, answeredAt: serverTimestamp() })
}

export async function declineJoin(fromUid: string): Promise<void> {
  await update(ref(db, `joinRequests/${requireUid()}/${fromUid}`), { status: 'declined', roomCode: null, answeredAt: serverTimestamp() })
}
