// Chats list, message rows, chat header and group members, built from parsed records. Mirrors the
// phone's ChatsScreen / ChatTimes.rows / WatchTogether / GroupChat. Pure, unit-tested.
import type { ChatHeaderVM, ChatSummaryVM, GroupMemberVM, MessageVM, PersonVM } from '../ui/models.ts'
import { SERVICES, isJam } from './catalog.ts'
import { agoShort, dayLabel, dayNumber, durationLabel, effectiveSentAt, elapsedLabel, listTimeLabel, type TimeOptions } from './format.ts'
import type { ChatMessage, Group, Presence, Preview, Profile, RoomLink, TextMessage, WatchCard } from './parse.ts'
import { pingState } from './roomModel.ts'

export interface Clock extends TimeOptions {
  /** Server time (clock.ts). */
  now: number
}

// ---------- Previews ----------

/** "Watched Leo together", "Listened to Blinding Lights + 3 more together" (WatchTogether.headline). */
export function watchedHeadline(card: Pick<WatchCard, 'title' | 'service' | 'items'>): string {
  const what = card.items > 1 ? `${card.title} + ${card.items - 1} more` : card.title
  return card.service && SERVICES[card.service].kind === 'music' ? `Listened to ${what} together` : `Watched ${what} together`
}

export function dmPreview(last: ChatMessage, me: string): string {
  if (last.kind === 'watched') return watchedHeadline(last)
  if (last.deleted) return last.from === me ? 'You deleted this message' : 'This message was deleted'
  return last.from === me ? `You: ${last.text}` : last.text
}

export function firstName(name: string): string {
  return name.trim().split(/\s+/)[0] ?? name
}

/** "You: Count me in", "Meera: who's free Saturday?", or "5 members" before the first message. */
export function groupPreview(last: TextMessage | null, me: string, senderName: string | null, memberCount: number): string {
  if (!last) return memberCount === 1 ? 'Just you · add people' : `${memberCount} members`
  if (last.from === me) return `You: ${last.text}`
  return senderName ? `${firstName(senderName)}: ${last.text}` : last.text
}

/** Newest message not deleted-for-me and newer than "delete chat" (FirebaseDirectMessages.observeLatest). */
export function latestVisible(messages: readonly ChatMessage[], hidden: ReadonlySet<string>, clearedAt: number): ChatMessage | null {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]
    if (!hidden.has(m.id) && m.sentAt > clearedAt) return m
  }
  return null
}

/** Unread = messages from [from] in the "newer than last read" query (cards have no `from`, never count). */
export function countFrom(messages: readonly ChatMessage[], from: string): number {
  return messages.filter((m) => m.kind === 'text' && m.from === from).length
}

/** Group unread: anything not from us. */
export function countNotFrom(messages: readonly ChatMessage[], me: string): number {
  return messages.filter((m) => m.kind === 'text' && m.from !== me).length
}

// ---------- Chats list ----------

export interface DmInput {
  chatId: string
  friend: PersonVM
  /** Already filtered for "delete for me" / "delete chat". */
  latest: ChatMessage | null
  unread: number
  room: RoomLink | null
}

export interface GroupInput {
  group: Group
  latest: TextMessage | null
  latestSenderName: string | null
  unread: number
  room: RoomLink | null
  /** Up to two members (not us) for the stacked avatar. */
  people: PersonVM[]
}

/**
 * 1:1 chats and groups in one list, newest first. A DM shows once it has a message or a live room
 * (Android lists only DMs with messages; rooms live in chats now). A pending stamp sorts first. A
 * live room counts as activity, so "Room live" chats float up.
 */
export function buildChatSummaries(dms: readonly DmInput[], groups: readonly GroupInput[], me: string, clock: Clock): ChatSummaryVM[] {
  const out: ChatSummaryVM[] = []
  for (const d of dms) {
    if (!d.latest && !d.room) continue
    const at = d.latest ? (d.latest.sentAt > 0 ? d.latest.sentAt : Number.MAX_SAFE_INTEGER) : 0
    const sortKey = Math.max(at, d.room?.startedAt ?? 0)
    out.push({
      chatId: d.chatId,
      kind: 'dm',
      name: d.friend.name,
      people: [d.friend],
      preview: d.latest ? dmPreview(d.latest, me) : '',
      timeLabel: listTimeLabel(Math.min(sortKey, clock.now), clock.now, clock),
      unread: d.unread,
      liveRoom: d.room ? { inCount: d.room.inRoom.length } : null,
      sortKey
    })
  }
  for (const g of groups) {
    const at = g.latest ? (g.latest.sentAt > 0 ? g.latest.sentAt : Number.MAX_SAFE_INTEGER) : g.group.createdAt
    const sortKey = Math.max(at, g.room?.startedAt ?? 0)
    out.push({
      chatId: g.group.id,
      kind: 'group',
      name: g.group.name,
      people: g.people,
      preview: groupPreview(g.latest, me, g.latestSenderName, g.group.members.length),
      timeLabel: g.latest || g.room ? listTimeLabel(Math.min(sortKey, clock.now), clock.now, clock) : '',
      unread: g.unread,
      liveRoom: g.room ? { inCount: g.room.inRoom.length } : null,
      sortKey
    })
  }
  return out.sort((a, b) => b.sortKey - a.sortKey)
}

// ---------- Messages ----------

export interface RoomEvent {
  code: string
  startedBy: string
  startedAt: number
  /** "Riya", or "You". */
  name: string
}

/**
 * Messages (oldest first) → rows with a day pill before the first message of each day, watch cards,
 * and "Riya started a room · 9:02 pm" for the chat's live room (from its link, not a stored message).
 */
export function buildMessageRows(
  messages: readonly ChatMessage[],
  opts: { me: string; clock: Clock; group: boolean; names: ReadonlyMap<string, string>; roomEvent: RoomEvent | null; timeOf: (ms: number) => string }
): MessageVM[] {
  type Item = ChatMessage | { kind: 'event'; id: string; sentAt: number; text: string }
  const items: Item[] = [...messages]
  if (opts.roomEvent) {
    const e = opts.roomEvent
    const event = { kind: 'event' as const, id: `room-${e.code}-${e.startedAt}`, sentAt: e.startedAt, text: `${e.name} started a room · ${opts.timeOf(e.startedAt)}` }
    const at = items.findIndex((m) => effectiveSentAt(m.sentAt, opts.clock.now) > e.startedAt)
    items.splice(at < 0 ? items.length : at, 0, event)
  }
  const out: MessageVM[] = []
  const seenDays = new Set<number>()
  let previousDay: number | null = null
  items.forEach((m, index) => {
    const at = effectiveSentAt(m.sentAt, opts.clock.now)
    const day = dayNumber(at, opts.clock.timeZone)
    if (day !== previousDay) {
      // Keys must be unique even if out-of-order stamps revisit a day.
      const id = seenDays.has(day) ? `day-${day}-${index}` : `day-${day}`
      seenDays.add(day)
      out.push({ kind: 'day', id, label: dayLabel(at, opts.clock.now, opts.clock) })
      previousDay = day
    }
    if (m.kind === 'event') out.push(m)
    else if (m.kind === 'watched')
      out.push({ kind: 'watched', id: m.id, title: watchedHeadline(m), durationLabel: durationLabel(m.durationMs), detail: m.service ? SERVICES[m.service].name : '', sentAt: at })
    else
      out.push({
        kind: 'text',
        id: m.id,
        fromMe: m.from === opts.me,
        senderName: opts.group && m.from !== opts.me ? opts.names.get(m.from) ?? 'Member' : undefined,
        senderId: m.from,
        text: m.text,
        sentAt: at,
        forwarded: m.forwarded || undefined,
        deleted: m.deleted || undefined
      })
  })
  return out
}

// ---------- Header ----------

/** "Watching S2:E4 · Midnight Signal · Netflix", "Listening to Neon Streets · Spotify", + voice. */
export function roomSummary(preview: Preview | null, talking: number): string {
  let line = 'Nothing playing yet'
  const title = preview?.title
  if (title) {
    const service = preview.service ? SERVICES[preview.service] : null
    if (service && isJam(service.kind)) line = `Listening to ${title} · ${service.name}`
    else {
      const what = service?.kind === 'movie' ? [preview.subtitle, title].filter(Boolean).join(' · ') : title
      line = service ? `Watching ${what} · ${service.name}` : `Watching ${what}`
    }
  }
  return talking > 0 ? `${line} · talking in voice` : line
}

export function headerRoom(link: RoomLink | null, preview: Preview | null, people: ReadonlyMap<string, PersonVM>, talking: number, now: number): ChatHeaderVM['room'] {
  if (!link) return null
  return {
    code: link.code,
    inCount: link.inRoom.length,
    startedAtLabel: elapsedLabel(link.startedAt, now),
    summary: roomSummary(preview, talking),
    names: link.inRoom.map((uid) => people.get(uid) ?? { id: uid, name: 'Someone' })
  }
}

/** "5 members · 3 in the room"; a group of one is "Just you · add people". */
export function groupSubtitle(memberCount: number, link: RoomLink | null): string {
  const base = memberCount <= 1 ? 'Just you · add people' : `${memberCount} members`
  return link ? `${base} · ${link.inRoom.length} in the room` : base
}

export function personOf(profile: Profile | null | undefined, uid: string, presence?: Presence | null): PersonVM {
  return {
    id: uid,
    name: profile?.displayName ?? 'Member',
    username: profile?.username,
    photoUrl: profile?.photoUrl ?? null,
    online: presence ? presence.online : undefined
  }
}

// ---------- Group members ----------

export interface MemberInput {
  uid: string
  profile: Profile | null
  /** Only friends' presence is readable; null otherwise. */
  presence: Presence | null
  joinedAt: number
  /** Talking in the room's voice chat (only known while we're in the room). */
  talking: boolean
  /** Monotonic ms since we pinged them, or null. */
  pingedAgoMs: number | null
}

/**
 * In the room first (whoever started it first), then us, then online, then by name. Details follow
 * the design: "Started the room" / "Talking" / "In the room", "Join any time" for us, "Online",
 * "Offline · 2 h". Device ("On his phone") isn't stored, so it isn't shown. [isMe] members keep
 * their real name: the screen writes "You".
 */
export function buildGroupMembers(members: readonly MemberInput[], me: string, room: RoomLink | null, now: number): GroupMemberVM[] {
  const inRoom = new Set(room?.inRoom ?? [])
  const vms = members.map((m) => {
    const here = inRoom.has(m.uid)
    const isMe = m.uid === me
    let detail: string
    if (here) detail = m.talking ? 'Talking' : room?.startedBy === m.uid ? 'Started the room' : 'In the room'
    else if (isMe) detail = room ? 'Join any time' : 'You'
    else if (m.presence?.online) detail = 'Online'
    else if (m.presence?.lastSeen) detail = `Offline · ${agoShort(now - m.presence.lastSeen)}`
    else detail = m.profile ? `@${m.profile.username}` : ''
    const vm: GroupMemberVM = {
      ...personOf(m.profile, m.uid, m.presence),
      detail,
      inRoom: here,
      isMe,
      ping: room && !here && !isMe ? pingState(m.pingedAgoMs, agoShort) : null
    }
    return { vm, m }
  })
  const rank = ({ vm }: { vm: GroupMemberVM }): number => (vm.inRoom ? 0 : vm.isMe ? 1 : vm.online ? 2 : 3)
  vms.sort((a, b) => {
    const r = rank(a) - rank(b)
    if (r !== 0) return r
    if (a.vm.inRoom) {
      const starter = Number(b.vm.id === room?.startedBy) - Number(a.vm.id === room?.startedBy)
      return starter !== 0 ? starter : a.m.joinedAt - b.m.joinedAt
    }
    return a.vm.name.toLowerCase().localeCompare(b.vm.name.toLowerCase())
  })
  return vms.map((x) => x.vm)
}
