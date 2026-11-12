import { useMemo, useSyncExternalStore } from 'react'
import { get, push, ref, remove, serverTimestamp, set, update } from 'firebase/database'
import type { GroupMemberVM } from '@/ui/models'
import { db } from '@/lib/firebase'
import { buildGroupMembers } from './chatModel.ts'
import { pingAgeMs, subscribePings, pingVersion, leaveChatRoom, activeChatRoom } from './chatRooms.ts'
import { useServerNow } from './clock.ts'
import { requireUid, useLive, useLiveMany, usePresences, useProfiles, useUid, type LiveSpec } from './live.ts'
import { parseGroup, parseGroupInvite, parseKeys, parseRoomLink, parseVoiceMembers, type GroupInvite } from './parse.ts'
import { notifyRelay } from './relay.ts'
import { cleanGroupName } from './usernames.ts'

// Groups, as Android's FirebaseGroupChats:
//   groups/{gid} {name, createdBy, createdAt, members/{uid} {role admin|member, joinedAt}}
//   userGroups/{uid}/{gid}: true (written with the membership), groupInvites/{to}/{gid} for "ask" people.
// Only admins add or remove, and only their own friends; anyone may rename; anyone may leave.

export const GROUP_MAX_MEMBERS = 32

/** Members' info sheet: in the room first, ping state for the rest. Null while loading. */
export function useGroupMembers(gid: string | null): GroupMemberVM[] | null {
  const me = useUid()
  const groupSnap = useLive(me && gid ? `groups/${gid}` : null)
  const roomSnap = useLive(me && gid ? `groupRooms/${gid}` : null)
  const friendsSnap = useLive(me ? `friends/${me}` : null)
  const group = useMemo(() => (gid ? parseGroup(gid, groupSnap?.val) : null), [gid, groupSnap])
  const room = useMemo(() => parseRoomLink(roomSnap?.val), [roomSnap])
  const uids = useMemo(() => group?.members.map((m) => m.uid) ?? [], [group])
  const profiles = useProfiles(uids)
  // presence/{uid} is friends-only: don't even ask for the others (each would be a refused listener).
  const friendSet = useMemo(() => new Set(parseKeys(friendsSnap?.children ?? [])), [friendsSnap])
  const presences = usePresences(useMemo(() => uids.filter((u) => friendSet.has(u) || u === me), [uids, friendSet, me]))
  const voiceSpecs = useMemo((): LiveSpec[] => (room && me && room.inRoom.includes(me) ? [{ key: 'members', path: `rooms/${room.code}/members` }] : []), [room, me])
  const voice = useLiveMany(voiceSpecs).get('members')
  const pings = useSyncExternalStore(subscribePings, pingVersion)
  const now = useServerNow()
  return useMemo(() => {
    if (!me || !gid || !groupSnap || !group) return null
    const talking = parseVoiceMembers(voice?.val)
    void pings // re-render when we ping someone
    const admins = new Set(group.members.filter((m) => m.admin).map((m) => m.uid))
    return buildGroupMembers(
      group.members.map((m) => ({
        uid: m.uid,
        profile: profiles.get(m.uid) ?? null,
        presence: presences.get(m.uid) ?? null,
        joinedAt: m.joinedAt,
        talking: talking.has(m.uid),
        pingedAgoMs: pingAgeMs(gid, m.uid)
      })),
      me,
      room,
      now
    ).map((vm) => ({ ...vm, admin: admins.has(vm.id), creator: vm.id === group.createdBy }))
  }, [me, groupSnap, group, profiles, presences, voice, room, pings, now, gid])
}

/** Whether we're an admin of [gid] (Remove / Add buttons). */
export function useAmGroupAdmin(gid: string): boolean {
  const me = useUid()
  const snap = useLive(me ? `groups/${gid}` : null)
  return !!me && !!parseGroup(gid, snap?.val)?.members.some((m) => m.uid === me && m.admin)
}

/** Friends who chose "Join directly" are added; everyone else gets an invite (missing = ask). */
async function splitByPolicy(uids: string[]): Promise<{ direct: string[]; ask: string[] }> {
  const policies = await Promise.all(uids.map(async (uid) => [uid, (await get(ref(db, `users/${uid}/groupInvites`))).val()] as const))
  return { direct: policies.filter(([, p]) => p === 'direct').map(([u]) => u), ask: policies.filter(([, p]) => p !== 'direct').map(([u]) => u) }
}

async function putInvites(changes: Record<string, unknown>, me: string, gid: string, groupName: string, to: string[]): Promise<void> {
  if (to.length === 0) return
  const myName = String((await get(ref(db, `users/${me}/displayName`))).val() ?? '').slice(0, 60)
  for (const uid of to) changes[`groupInvites/${uid}/${gid}`] = { from: me, fromName: myName, groupName: groupName.slice(0, 50), sentAt: serverTimestamp() }
}

/**
 * New group with us as admin. [friendUids] may be empty: a group of one, people added later.
 * One multi-path update: groups/{gid} {name, createdBy, createdAt, members}, userGroups for us and
 * direct-add friends, groupInvites for the "ask" ones (then the relay pops them up).
 */
export async function createGroup(name: string, friendUids: readonly string[] = []): Promise<string> {
  const me = requireUid()
  const clean = cleanGroupName(name)
  if (!clean) throw new Error('Give the group a name')
  const others = [...new Set(friendUids.filter((u) => u && u !== me))]
  if (others.length + 1 > GROUP_MAX_MEMBERS) throw new Error(`A group can have up to ${GROUP_MAX_MEMBERS} people`)
  const gid = push(ref(db, 'groups')).key
  if (!gid) throw new Error('No group id')
  const { direct, ask } = await splitByPolicy(others)
  const members: Record<string, unknown> = { [me]: { role: 'admin', joinedAt: serverTimestamp() } }
  for (const uid of direct) members[uid] = { role: 'member', joinedAt: serverTimestamp() }
  const changes: Record<string, unknown> = {
    [`groups/${gid}`]: { name: clean, createdBy: me, createdAt: serverTimestamp(), members }
  }
  for (const uid of [me, ...direct]) changes[`userGroups/${uid}/${gid}`] = true
  await putInvites(changes, me, gid, clean, ask)
  await update(ref(db), changes)
  for (const uid of ask) void notifyRelay({ type: 'group_invite', group: gid, to: uid })
  return gid
}

export async function renameGroup(gid: string, name: string): Promise<void> {
  requireUid()
  const clean = cleanGroupName(name)
  if (!clean) throw new Error('Give the group a name')
  await set(ref(db, `groups/${gid}/name`), clean)
}

/** Admins add their own friends: "direct" people join now, "ask" people get an invite. */
export async function addGroupMembers(gid: string, uids: readonly string[]): Promise<{ added: string[]; invited: string[] }> {
  const me = requireUid()
  const add = [...new Set(uids.filter((u) => u && u !== me))]
  if (add.length === 0) return { added: [], invited: [] }
  const name = String((await get(ref(db, `groups/${gid}/name`))).val() ?? '')
  const { direct, ask } = await splitByPolicy(add)
  const changes: Record<string, unknown> = {}
  for (const uid of direct) {
    changes[`groups/${gid}/members/${uid}`] = { role: 'member', joinedAt: serverTimestamp() }
    changes[`userGroups/${uid}/${gid}`] = true
  }
  await putInvites(changes, me, gid, name, ask)
  await update(ref(db), changes)
  for (const uid of ask) void notifyRelay({ type: 'group_invite', group: gid, to: uid })
  return { added: direct, invited: ask }
}

export async function removeGroupMember(gid: string, uid: string): Promise<void> {
  const me = requireUid()
  if (uid === me) throw new Error('Use leave to leave the group')
  await update(ref(db), { [`groups/${gid}/members/${uid}`]: null, [`userGroups/${uid}/${gid}`]: null })
}

/** If we're the only admin and others stay, the longest-standing member becomes admin first. */
export async function leaveGroup(gid: string): Promise<void> {
  const me = requireUid()
  const group = parseGroup(gid, (await get(ref(db, `groups/${gid}`))).val())
  const changes: Record<string, unknown> = {}
  if (group?.members.some((m) => m.uid === me && m.admin)) {
    const rest = group.members.filter((m) => m.uid !== me)
    if (rest.length > 0 && !rest.some((m) => m.admin)) changes[`groups/${gid}/members/${rest[0].uid}/role`] = 'admin' // oldest first
  }
  if (activeChatRoom()?.chatId === gid) await leaveChatRoom(gid)
  else await remove(ref(db, `groupRooms/${gid}/in/${me}`)).catch(() => undefined)
  changes[`groups/${gid}/members/${me}`] = null
  changes[`userGroups/${me}/${gid}`] = null
  changes[`groupReads/${me}/${gid}`] = null
  await update(ref(db), changes)
}

/** groupInvites/{me}: for people whose setting is "Ask me first". Newest first; null while loading. */
export function useGroupInvites(): GroupInvite[] | null {
  const me = useUid()
  const snap = useLive(me ? `groupInvites/${me}` : null)
  return useMemo(() => (snap ? snap.children.flatMap(([gid, v]) => parseGroupInvite(gid, v) ?? []).sort((a, b) => b.sentAt - a.sentAt) : null), [snap])
}

export async function acceptGroupInvite(gid: string): Promise<void> {
  const me = requireUid()
  await update(ref(db), {
    [`groups/${gid}/members/${me}`]: { role: 'member', joinedAt: serverTimestamp() },
    [`userGroups/${me}/${gid}`]: true,
    [`groupInvites/${me}/${gid}`]: null
  })
}

export async function declineGroupInvite(gid: string): Promise<void> {
  const me = requireUid()
  await remove(ref(db, `groupInvites/${me}/${gid}`))
}

// ---------- Admins and settings (2026-11-10, same as the phone's Group info) ----------

/** Makes [uid] an admin or dismisses them. The rules refuse it for the creator and for non-admins. */
export async function setGroupAdmin(gid: string, uid: string, admin: boolean): Promise<void> {
  requireUid()
  await set(ref(db, `groups/${gid}/members/${uid}/role`), admin ? 'admin' : 'member')
}

/** Who may send messages and who may edit the group's info ("all" or "admins"). Admins only. */
export async function setGroupSettings(gid: string, onlyAdminsSend: boolean, onlyAdminsEdit: boolean): Promise<void> {
  requireUid()
  await set(ref(db, `groups/${gid}/settings`), { send: onlyAdminsSend ? 'admins' : 'all', edit: onlyAdminsEdit ? 'admins' : 'all' })
}

/** Which of [uids] have an unanswered invite to [gid] ("Invited" in Add people). Members may read these. */
export function useGroupInvited(gid: string | null, uids: readonly string[]): ReadonlySet<string> {
  const specs = useMemo((): LiveSpec[] => (gid ? uids.map((uid) => ({ key: uid, path: `groupInvites/${uid}/${gid}` })) : []), [gid, uids])
  const snaps = useLiveMany(specs)
  return useMemo(() => new Set(uids.filter((uid) => snaps.get(uid)?.exists === true)), [uids, snaps])
}
