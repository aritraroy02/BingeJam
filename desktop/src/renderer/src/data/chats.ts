import { useEffect, useMemo, useRef, useState } from 'react'
import { limitToLast, onDisconnect, onValue, orderByChild, push, ref, remove, serverTimestamp, set, startAt, update } from 'firebase/database'
import type { ChatHeaderVM, ChatSummaryVM, MessageVM, PersonVM } from '@/ui/models'
import { db } from '@/lib/firebase'
import {
  buildChatSummaries,
  buildMessageRows,
  countFrom,
  countNotFrom,
  groupSubtitle,
  headerRoom,
  latestVisible,
  personOf,
  type Clock,
  type DmInput,
  type GroupInput
} from './chatModel.ts'
import { serverNow, useServerNow } from './clock.ts'
import { systemHour12, timeLabel } from './format.ts'
import { dmFriend, dmId } from './ids.ts'
import { currentUid, isPermissionDenied, requireUid, useLive, useLiveMany, useProfiles, useUid, type LiveSpec, type Snap } from './live.ts'
import {
  parseDmMessage,
  parseGroup,
  parseGroupMessage,
  parseHidden,
  parseKeys,
  parseList,
  parseNumberMap,
  parsePresence,
  parsePreview,
  parseRoomLink,
  parseVoiceMembers,
  type ChatMessage,
  type Group,
  type RoomLink,
  type TextMessage
} from './parse.ts'
import { notifyRelay } from './relay.ts'
import { headerStatus } from './status.ts'
import { useFriendRecords } from './friends.ts'
import { isTypingFresh, nextTypingCheckMs, TypingSignal, TypingThrottle } from './typing.ts'

// Chats: 1:1 (dms/{dmId}, the Android dmId) and groups (groupMessages/{gid}) in one list, each with
// its room link (dmRooms/{dmId}, groupRooms/{gid}). Same reads and writes as Android's
// FirebaseDirectMessages and FirebaseGroupChats.

const MAX_TEXT = 1000
const MESSAGE_LIMIT = 100
/** Per DM, the last few messages, so a few "deleted for me" in a row are skipped. */
const LATEST_WINDOW = 5
/** Badges show "99+" beyond this, so there's no need to download more. */
const MAX_UNREAD = 100

export function clockNow(now: number): Clock {
  return { now, hour12: systemHour12() }
}

// ---------- Chats list ----------

export interface ChatData {
  me: string
  dms: DmInput[]
  groups: GroupInput[]
}

/** Everything the Chats list and Home's live-room banner need; null while loading. */
export function useChatData(): ChatData | null {
  const me = useUid()
  const friends = useFriendRecords()
  const reads = useLive(me ? `dmReads/${me}` : null)
  const cleared = useLive(me ? `dmCleared/${me}` : null)
  const hidden = useLive(me ? `dmHidden/${me}` : null)
  const userGroups = useLive(me ? `userGroups/${me}` : null)
  const groupReads = useLive(me ? `groupReads/${me}` : null)

  const readMap = useMemo(() => parseNumberMap(reads?.children ?? []), [reads])
  const clearedMap = useMemo(() => parseNumberMap(cleared?.children ?? []), [cleared])
  const groupReadMap = useMemo(() => parseNumberMap(groupReads?.children ?? []), [groupReads])
  const gids = useMemo(() => (userGroups ? parseKeys(userGroups.children).sort() : []), [userGroups])

  const specs = useMemo(() => {
    if (!me || !friends || !reads || !cleared || !groupReads) return []
    const out: LiveSpec[] = []
    for (const f of friends) {
      const id = dmId(me, f.uid)
      const since = Math.max(readMap.get(f.uid) ?? 0, clearedMap.get(f.uid) ?? 0)
      out.push(
        { key: `latest:${id}`, path: `dms/${id}`, constraints: [orderByChild('sentAt'), limitToLast(LATEST_WINDOW)] },
        { key: `unread:${id}:${since}`, path: `dms/${id}`, constraints: [orderByChild('sentAt'), startAt(since + 1), limitToLast(MAX_UNREAD)] },
        { key: `room:${id}`, path: `dmRooms/${id}` }
      )
    }
    for (const gid of gids) {
      const since = groupReadMap.get(gid) ?? 0
      out.push(
        { key: `group:${gid}`, path: `groups/${gid}` },
        { key: `glatest:${gid}`, path: `groupMessages/${gid}`, constraints: [orderByChild('sentAt'), limitToLast(1)] },
        { key: `gunread:${gid}:${since}`, path: `groupMessages/${gid}`, constraints: [orderByChild('sentAt'), startAt(since + 1), limitToLast(MAX_UNREAD)] },
        { key: `groom:${gid}`, path: `groupRooms/${gid}` }
      )
    }
    return out
  }, [me, friends, reads, cleared, groupReads, readMap, clearedMap, groupReadMap, gids])
  const snaps = useLiveMany(specs)

  // Groups: their member list (for avatars) and the last sender's name.
  const groups = useMemo(() => {
    const out = new Map<string, Group>()
    for (const gid of gids) {
      const g = parseGroup(gid, snaps.get(`group:${gid}`)?.val)
      if (g && me && g.members.some((m) => m.uid === me)) out.set(gid, g)
    }
    return out
  }, [snaps, gids, me])
  const groupLatest = useMemo(() => {
    const out = new Map<string, TextMessage | null>()
    for (const gid of gids) out.set(gid, parseList(snaps.get(`glatest:${gid}`)?.children ?? [], parseGroupMessage).at(-1) ?? null)
    return out
  }, [snaps, gids])
  const peopleUids = useMemo(() => {
    const out: string[] = []
    for (const g of groups.values()) out.push(...g.members.filter((m) => m.uid !== me).slice(0, 2).map((m) => m.uid))
    for (const m of groupLatest.values()) if (m && m.from !== me) out.push(m.from)
    return out
  }, [groups, groupLatest, me])
  const profiles = useProfiles(peopleUids)

  return useMemo(() => {
    if (!me || !friends || !reads || !cleared || !hidden || !userGroups || !groupReads) return null
    const hiddenMap = parseHidden(hidden.val)
    const dms: DmInput[] = []
    for (const f of friends) {
      const id = dmId(me, f.uid)
      const latestSnap = snaps.get(`latest:${id}`)
      if (!latestSnap) return null // still loading
      const since = Math.max(readMap.get(f.uid) ?? 0, clearedMap.get(f.uid) ?? 0)
      dms.push({
        chatId: id,
        friend: { ...personOf(f.profile, f.uid, f.presence), online: f.presence?.online === true },
        latest: latestVisible(parseList(latestSnap.children, parseDmMessage), hiddenMap.get(id) ?? new Set(), clearedMap.get(f.uid) ?? 0),
        unread: countFrom(parseList(snaps.get(`unread:${id}:${since}`)?.children ?? [], parseDmMessage), f.uid),
        room: parseRoomLink(snaps.get(`room:${id}`)?.val)
      })
    }
    const groupInputs: GroupInput[] = []
    for (const [gid, group] of groups) {
      const latest = groupLatest.get(gid) ?? null
      const since = groupReadMap.get(gid) ?? 0
      groupInputs.push({
        group,
        latest,
        latestSenderName: latest ? profiles.get(latest.from)?.displayName ?? null : null,
        unread: countNotFrom(parseList(snaps.get(`gunread:${gid}:${since}`)?.children ?? [], parseGroupMessage), me),
        room: parseRoomLink(snaps.get(`groom:${gid}`)?.val),
        people: group.members.filter((m) => m.uid !== me).slice(0, 2).map((m) => personOf(profiles.get(m.uid), m.uid))
      })
    }
    return { me, dms, groups: groupInputs }
  }, [me, friends, reads, cleared, hidden, userGroups, groupReads, snaps, readMap, clearedMap, groupReadMap, groups, groupLatest, profiles])
}

/** Chats tab: 1:1 and groups together, newest first, with unread counts and "Room live · N in". */
export function useChats(): ChatSummaryVM[] | null {
  const data = useChatData()
  const now = useServerNow()
  return useMemo(() => (data ? buildChatSummaries(data.dms, data.groups, data.me, clockNow(now)) : null), [data, now])
}

// ---------- One chat's messages ----------

/** Oldest first with day pills, watch cards and "X started a room"; null while loading. */
export function useChatMessages(chatId: string | null): MessageVM[] | null {
  const me = useUid()
  const friend = me && chatId ? dmFriend(chatId, me) : null
  const isGroup = !!me && !!chatId && !friend
  const specs = useMemo((): LiveSpec[] => {
    if (!me || !chatId) return []
    const recent = [orderByChild('sentAt'), limitToLast(MESSAGE_LIMIT)]
    if (friend)
      return [
        { key: 'msgs', path: `dms/${chatId}`, constraints: recent },
        { key: 'hidden', path: `dmHidden/${me}/${chatId}` },
        { key: 'cleared', path: `dmCleared/${me}/${friend}` },
        { key: 'room', path: `dmRooms/${chatId}` }
      ]
    return [
      { key: 'msgs', path: `groupMessages/${chatId}`, constraints: recent },
      { key: 'group', path: `groups/${chatId}` },
      { key: 'room', path: `groupRooms/${chatId}` }
    ]
  }, [me, friend, chatId])
  const snaps = useLiveMany(specs)
  const group = isGroup && chatId ? parseGroup(chatId, snaps.get('group')?.val) : null
  const room = parseRoomLink(snaps.get('room')?.val)
  const nameUids = useMemo(() => {
    const out = group ? group.members.map((m) => m.uid) : friend ? [friend] : []
    if (room) out.push(room.startedBy)
    return out
  }, [group, friend, room])
  const profiles = useProfiles(nameUids)
  const now = useServerNow()

  return useMemo(() => {
    const msgs = snaps.get('msgs')
    if (!me || !msgs) return null
    let messages: ChatMessage[]
    if (friend) {
      // Hidden starts empty so the chat shows at once; a refused read hides nothing (like Android).
      const gone = new Set(Object.keys((snaps.get('hidden')?.val as Record<string, unknown> | null) ?? {}))
      const since = typeof snaps.get('cleared')?.val === 'number' ? (snaps.get('cleared')?.val as number) : 0
      messages = parseList(msgs.children, parseDmMessage).filter((m) => !gone.has(m.id) && m.sentAt > since)
    } else {
      messages = parseList(msgs.children, parseGroupMessage)
    }
    const names = new Map<string, string>()
    for (const [uid, p] of profiles) if (p) names.set(uid, p.displayName)
    const clock = clockNow(now)
    return buildMessageRows(messages, {
      me,
      clock,
      group: !friend,
      names,
      roomEvent: room ? { code: room.code, startedBy: room.startedBy, startedAt: room.startedAt, name: room.startedBy === me ? 'You' : names.get(room.startedBy) ?? 'Someone' } : null,
      timeOf: (ms) => timeLabel(ms, clock.now, clock)
    })
  }, [me, friend, snaps, profiles, room, now])
}

export class NotFriendsError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'NotFriendsError'
  }
}

/** Sends a text (trimmed, ≤ 1000 chars), then asks the relay to notify their phones. */
export async function sendMessage(chatId: string, text: string, forwarded = false): Promise<void> {
  const me = requireUid()
  const trimmed = text.trim().slice(0, MAX_TEXT)
  if (!trimmed) return
  const friend = dmFriend(chatId, me)
  if (friend) {
    stopTyping(chatId, me)
    const target = push(ref(db, `dms/${chatId}`))
    try {
      await set(target, forwarded ? { from: me, text: trimmed, sentAt: serverTimestamp(), forwarded: true } : { from: me, text: trimmed, sentAt: serverTimestamp() })
    } catch (e) {
      if (isPermissionDenied(e)) throw new NotFriendsError('You can only message friends')
      throw e
    }
    if (target.key) void notifyRelay({ type: 'dm', to: friend, messageId: target.key })
  } else {
    const target = push(ref(db, `groupMessages/${chatId}`))
    await set(target, { from: me, text: trimmed, sentAt: serverTimestamp() })
    if (target.key) void notifyRelay({ type: 'group_message', group: chatId, messageId: target.key })
  }
}

/** dmReads/{me}/{friend} or groupReads/{me}/{gid} = server time: older messages are read. */
export async function markRead(chatId: string): Promise<void> {
  const me = requireUid()
  const friend = dmFriend(chatId, me)
  await set(ref(db, friend ? `dmReads/${me}/${friend}` : `groupReads/${me}/${chatId}`), serverTimestamp())
}

/** "Delete chat" (1:1, for us only): dmCleared/{me}/{friend} = server time; it comes back if they write again. */
export async function deleteChat(chatId: string): Promise<void> {
  const me = requireUid()
  const friend = dmFriend(chatId, me)
  if (!friend) throw new Error('Only 1:1 chats can be deleted; leave a group instead')
  await set(ref(db, `dmCleared/${me}/${friend}`), serverTimestamp())
}

// ---------- Typing (1:1 only; the rules have no typing node for groups) ----------

const throttle = new TypingThrottle(() => performance.now())
/** dmIds whose onDisconnect removal is registered on this connection (Firebase runs it once, then forgets). */
const armed = new Set<string>()
let watchingConnection = false

function watchConnection(): void {
  if (watchingConnection) return
  watchingConnection = true
  onValue(ref(db, '.info/connected'), (s) => {
    if (s.val() !== true) armed.clear()
  })
}

function stopTyping(chatId: string, me: string): void {
  if (!throttle.clear(chatId)) return
  remove(ref(db, `typing/${chatId}/${me}`)).catch(() => undefined)
}

/** Fire-and-forget, throttled to one write per 3 s; a failure only means a stale indicator, which expires. */
export function setTyping(chatId: string, typing: boolean): void {
  const me = currentUid()
  if (!me || !dmFriend(chatId, me)) return
  if (!typing) return stopTyping(chatId, me)
  if (!throttle.shouldWrite(chatId)) return
  watchConnection()
  const entry = ref(db, `typing/${chatId}/${me}`)
  // Arm the removal before the first write on this connection, so the entry can't outlive it.
  if (!armed.has(chatId)) {
    armed.add(chatId)
    onDisconnect(entry).remove().catch(() => armed.delete(chatId))
  }
  set(entry, serverTimestamp()).catch(() => undefined)
}

/** For a message box: call onDraftChange on every edit and onSent after sending; stops on unmount. */
export function useTypingReporter(chatId: string): { onDraftChange: (text: string) => void; onSent: () => void } {
  const signal = useRef(new TypingSignal())
  useEffect(() => {
    const s = new TypingSignal()
    signal.current = s
    return () => {
      if (s.onStopped() === false) setTyping(chatId, false)
    }
  }, [chatId])
  return useMemo(
    () => ({
      onDraftChange: (text: string) => {
        const v = signal.current.onDraftChanged(text)
        if (v !== null) setTyping(chatId, v)
      },
      onSent: () => {
        if (signal.current.onSent() !== null) setTyping(chatId, false)
      }
    }),
    [chatId]
  )
}

/** The friend is typing in this 1:1 chat (their entry younger than 6 s in server time). */
export function useFriendTyping(chatId: string | null): boolean {
  const me = useUid()
  const friend = me && chatId ? dmFriend(chatId, me) : null
  const snap = useLive(friend ? `typing/${chatId}/${friend}` : null)
  const stamp = typeof snap?.val === 'number' ? snap.val : null
  const [, tick] = useState(0)
  const fresh = stamp !== null && isTypingFresh(stamp, serverNow())
  useEffect(() => {
    if (stamp === null) return
    const now = serverNow()
    if (!isTypingFresh(stamp, now) && now >= stamp) return
    // Re-check until it goes stale; a refresh from the friend changes `stamp` and restarts this.
    const timer = setTimeout(() => tick((n) => n + 1), nextTypingCheckMs(stamp, now))
    return () => clearTimeout(timer)
  })
  return fresh
}

// ---------- Header ----------

function useRoomExtras(link: RoomLink | null, me: string | null | undefined): { preview: Snap | undefined; voice: Snap | undefined } {
  const specs = useMemo((): LiveSpec[] => {
    if (!link) return []
    const out: LiveSpec[] = [{ key: `preview:${link.code}`, path: `previews/${link.code}` }]
    // rooms/{code} is member-only; we're a member while we're in its "in" list.
    if (me && link.inRoom.includes(me)) out.push({ key: `members:${link.code}`, path: `rooms/${link.code}/members` })
    return out
  }, [link?.code, link?.inRoom.join(','), me])
  const snaps = useLiveMany(specs)
  return { preview: link ? snaps.get(`preview:${link.code}`) : undefined, voice: link ? snaps.get(`members:${link.code}`) : undefined }
}

/** The chat header: who, status / member count, and the room (null when nobody's in it). Null while loading. */
export function useChatHeader(chatId: string | null): ChatHeaderVM | null {
  const me = useUid()
  const friend = me && chatId ? dmFriend(chatId, me) : null
  const specs = useMemo((): LiveSpec[] => {
    if (!me || !chatId) return []
    if (friend)
      return [
        { key: 'presence', path: `presence/${friend}` },
        { key: 'room', path: `dmRooms/${chatId}` }
      ]
    return [
      { key: 'group', path: `groups/${chatId}` },
      { key: 'room', path: `groupRooms/${chatId}` }
    ]
  }, [me, friend, chatId])
  const snaps = useLiveMany(specs)
  const group = !friend && chatId ? parseGroup(chatId, snaps.get('group')?.val) : null
  const room = parseRoomLink(snaps.get('room')?.val)
  const uids = useMemo(() => {
    const out = friend ? [friend] : group ? group.members.map((m) => m.uid) : []
    if (me) out.push(me)
    if (room) out.push(...room.inRoom)
    return out
  }, [friend, group, room, me])
  const profiles = useProfiles(uids)
  const extras = useRoomExtras(room, me)
  const now = useServerNow()

  return useMemo(() => {
    if (!me || !chatId) return null
    const people = new Map<string, PersonVM>()
    for (const uid of uids) people.set(uid, personOf(profiles.get(uid), uid))
    const talking = parseVoiceMembers(extras.voice?.val).size
    const roomVM = headerRoom(room, parsePreview(extras.preview?.val), people, talking, now)
    if (friend) {
      const profile = profiles.get(friend)
      if (profile === undefined || !snaps.get('presence')) return null
      const presence = parsePresence(snaps.get('presence')?.val)
      return {
        chatId,
        kind: 'dm',
        name: profile?.displayName ?? 'Friend',
        people: [personOf(profile, friend, presence)],
        subtitle: headerStatus(presence, now),
        room: roomVM
      }
    }
    if (!group) return null
    return {
      chatId,
      kind: 'group',
      name: group.name,
      people: group.members.filter((m) => m.uid !== me).slice(0, 3).map((m) => people.get(m.uid) ?? personOf(null, m.uid)),
      subtitle: groupSubtitle(group.members.length, room),
      room: roomVM,
      // Members rename unless only admins may edit the group's info (rules: groups/$gid/name).
      canRename: group.members.some((m) => m.uid === me && (m.admin || !group.onlyAdminsEdit)),
      canSend: group.members.some((m) => m.uid === me && (m.admin || !group.onlyAdminsSend)),
      group: {
        amAdmin: group.members.some((m) => m.uid === me && m.admin),
        onlyAdminsSend: group.onlyAdminsSend,
        onlyAdminsEdit: group.onlyAdminsEdit
      }
    }
  }, [me, friend, group, room, uids, profiles, extras, snaps, now, chatId])
}

// ---------- Message actions (2026-11-12, same as the phone's long-press menu) ----------

/** Hides a message (or card) for us only, on all our devices; the friend still sees it. 1:1 chats only. */
export async function deleteMessageForMe(chatId: string, messageId: string): Promise<void> {
  const me = requireUid()
  if (!dmFriend(chatId, me)) throw new Error('Only in 1:1 chats')
  await set(ref(db, `dmHidden/${me}/${chatId}/${messageId}`), true)
}

/** Our own message: clears its text and marks it deleted for both of us (the node stays). Can't be undone. */
export async function deleteMessageForEveryone(chatId: string, messageId: string): Promise<void> {
  const me = requireUid()
  const friend = dmFriend(chatId, me)
  if (!friend) throw new Error('Only in 1:1 chats')
  await update(ref(db, `dms/${chatId}/${messageId}`), { deleted: true, text: null, forwarded: null })
  void notifyRelay({ type: 'dm_deleted', to: friend, messageId })
}
