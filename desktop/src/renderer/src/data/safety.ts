import { useMemo } from 'react'
import { get, limitToLast, orderByChild, push, query, ref, remove, serverTimestamp, update } from 'firebase/database'
import { db } from '@/lib/firebase'
import { leaveGroup } from './groups.ts'
import { dmId } from './ids.ts'
import { requireUid, useLive, useProfiles, useUid } from './live.ts'

// Report and block, the same paths and shapes as Android's FirebaseFriendsRepository.block and
// FirebaseSafety (2026-11-10):
//   blocked/{me}/{them} = true             people we blocked (the rules refuse their requests)
//   blockedGroups/{me}/{gid} = {name, at}  groups we left and blocked (nobody can add us back)
//   reports/{me}/{pushId}                  write-once; only the owner reads them, in the console

export type ReportReason = 'spam' | 'harassment' | 'inappropriate' | 'impersonation' | 'other'

export const REPORT_REASONS: { value: ReportReason; label: string }[] = [
  { value: 'spam', label: 'Spam or scam' },
  { value: 'harassment', label: 'Harassment or bullying' },
  { value: 'inappropriate', label: 'Nudity or violent content' },
  { value: 'impersonation', label: 'Pretending to be someone else' },
  { value: 'other', label: 'Something else' }
]

/** Messages sent with a report, like WhatsApp: the last few from the person or group. */
export const MAX_EVIDENCE = 5

const MAX_DETAILS = 500

/** Unfriends them and stops their requests, invites and join requests, in one atomic write. */
export async function blockUser(uid: string): Promise<void> {
  const me = requireUid()
  if (uid === me) throw new Error("You can't block yourself")
  await update(ref(db), {
    [`blocked/${me}/${uid}`]: true,
    [`friends/${me}/${uid}`]: null,
    [`friends/${uid}/${me}`]: null,
    [`joinRequests/${me}/${uid}`]: null,
    [`joinRequests/${uid}/${me}`]: null,
    [`invites/${me}/${uid}`]: null,
    [`invites/${uid}/${me}`]: null,
    [`friendRequests/${me}/${uid}`]: null,
    [`friendRequests/${uid}/${me}`]: null,
    [`sentFriendRequests/${me}/${uid}`]: null,
    [`sentFriendRequests/${uid}/${me}`]: null
  })
}

export async function unblockUser(uid: string): Promise<void> {
  const me = requireUid()
  await remove(ref(db, `blocked/${me}/${uid}`))
}

export async function unfriend(uid: string): Promise<void> {
  const me = requireUid()
  await update(ref(db), { [`friends/${me}/${uid}`]: null, [`friends/${uid}/${me}`]: null })
}

interface Evidence {
  from: string
  text: string
  sentAt: number
}

/** The last messages in a chat that weren't sent by [notFrom] (or only those from [onlyFrom]). */
async function lastMessages(path: string, pick: (from: string) => boolean): Promise<Evidence[]> {
  const snap = await get(query(ref(db, path), orderByChild('sentAt'), limitToLast(30)))
  const out: Evidence[] = []
  snap.forEach((child) => {
    const v = child.val() as Record<string, unknown> | null
    if (!v || typeof v.text !== 'string' || typeof v.from !== 'string' || v.deleted === true || v.kind) return
    if (!pick(v.from)) return
    out.push({ from: v.from, text: v.text.slice(0, 1000), sentAt: typeof v.sentAt === 'number' ? v.sentAt : 0 })
  })
  return out.slice(-MAX_EVIDENCE)
}

async function writeReport(kind: 'user' | 'group', target: string, name: string, reason: ReportReason, details: string | null, blocked: boolean, evidence: Evidence[]): Promise<void> {
  const me = requireUid()
  const entry: Record<string, unknown> = {
    kind,
    target,
    targetName: name.slice(0, 60),
    reason,
    blocked,
    at: serverTimestamp()
  }
  const d = details?.trim()
  if (d) entry.details = d.slice(0, MAX_DETAILS)
  if (evidence.length > 0) entry.evidence = Object.fromEntries(evidence.map((m, i) => [String(i), m]))
  await update(ref(db), { [`reports/${me}/${push(ref(db, `reports/${me}`)).key}`]: entry })
}

/**
 * Reports a person with their last few messages to us (read before blocking: the chat is
 * friends-only, so it can't be read after). [block] blocks them too.
 */
export async function reportUser(uid: string, name: string, reason: ReportReason, details: string | null, block: boolean): Promise<void> {
  const me = requireUid()
  const evidence = await lastMessages(`dms/${dmId(me, uid)}`, (from) => from === uid).catch(() => [])
  await writeReport('user', uid, name, reason, details, block, evidence)
  if (block) await blockUser(uid)
}

/** Reports a group with its last few messages from others; [block] also leaves and blocks it. */
export async function reportGroup(gid: string, name: string, reason: ReportReason, details: string | null, block: boolean): Promise<void> {
  const me = requireUid()
  const evidence = await lastMessages(`groupMessages/${gid}`, (from) => from !== me).catch(() => [])
  await writeReport('group', gid, name, reason, details, block, evidence)
  if (block) await blockGroup(gid, name)
}

/** Leaves the group and stops anyone adding us back or inviting us to it. */
export async function blockGroup(gid: string, name: string): Promise<void> {
  const me = requireUid()
  // Not a member any more (removed, or blocking from an invite): nothing to leave.
  await leaveGroup(gid).catch(() => undefined)
  await update(ref(db), {
    [`blockedGroups/${me}/${gid}`]: { name: name.slice(0, 50), at: serverTimestamp() },
    [`groupInvites/${me}/${gid}`]: null
  })
}

export async function unblockGroup(gid: string): Promise<void> {
  const me = requireUid()
  await remove(ref(db, `blockedGroups/${me}/${gid}`))
}

export interface BlockedPerson {
  uid: string
  name: string
  username: string
  photoUrl: string | null
}

/** People we blocked, with their profiles; null while loading. */
export function useBlockedPeople(): BlockedPerson[] | null {
  const me = useUid()
  const snap = useLive(me ? `blocked/${me}` : null)
  const uids = useMemo(() => (snap ? snap.children.map(([k]) => k) : []), [snap])
  const profiles = useProfiles(uids)
  return useMemo(() => {
    if (!snap) return null
    return uids
      .map((uid) => {
        const p = profiles.get(uid)
        return { uid, name: p?.displayName ?? 'Someone', username: p?.username ?? '', photoUrl: p?.photoUrl ?? null }
      })
      .sort((a, b) => a.name.localeCompare(b.name))
  }, [snap, uids, profiles])
}

export interface BlockedGroup {
  id: string
  name: string
}

/** Groups we left and blocked; null while loading. */
export function useBlockedGroups(): BlockedGroup[] | null {
  const me = useUid()
  const snap = useLive(me ? `blockedGroups/${me}` : null)
  return useMemo(() => {
    if (!snap) return null
    return snap.children
      .map(([id, v]) => ({ id, name: typeof (v as { name?: unknown })?.name === 'string' ? (v as { name: string }).name : 'Group' }))
      .sort((a, b) => a.name.localeCompare(b.name))
  }, [snap])
}
