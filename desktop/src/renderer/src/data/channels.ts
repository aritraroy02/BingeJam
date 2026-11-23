import { useMemo } from 'react'
import { get, limitToLast, onDisconnect, orderByChild, push, ref, remove, serverTimestamp, set, update } from 'firebase/database'
import type { ChannelVM, GroupChannelsVM, MessageVM, VoiceOccupantVM } from '@/ui/models'
import { db } from '@/lib/firebase'
import {
  callElapsed,
  channelList,
  channelUnread,
  CHANNEL_STATUS_MAX,
  cleanChannelName,
  GENERAL,
  LOUNGE,
  nextOrder,
  parseChannels,
  parseVoiceOccupants,
  parseVoiceStatuses,
  type ChannelKind
} from './channelModel.ts'
import { buildMessageRows } from './chatModel.ts'
import { clockNow } from './chats.ts'
import { useServerNow } from './clock.ts'
import { timeLabel } from './format.ts'
import { requireUid, useLiveMany, useProfiles, useUid, type LiveSpec } from './live.ts'
import { parseGroup, parseGroupMessage, parseList, parseNumberMap } from './parse.ts'

// Group channels, Discord style (PC app, 2026-11-22); the data model is in channelModel.ts. Only the
// PC shows channels for now: phones keep showing the group's chat, which is #general here.

const MAX_TEXT = 1000
const MESSAGE_LIMIT = 100

export { GENERAL, LOUNGE }

/**
 * The open group's channels for the channel list: unread text channels, who's in each voice channel
 * with the call timer and status. [speaking]: uids heard talking in the voice channel we're
 * connected to ([connected]); null when the group isn't open.
 */
export function useGroupChannels(gid: string | null, generalUnread: boolean, connected: string | null, speaking: ReadonlySet<string>): GroupChannelsVM | null {
  const me = useUid()
  const base = useMemo((): LiveSpec[] => {
    if (!me || !gid) return []
    return [
      { key: 'group', path: `groups/${gid}` },
      { key: 'voice', path: `groupVoice/${gid}` },
      { key: 'status', path: `groupVoiceStatus/${gid}` },
      { key: 'reads', path: `groupChannelReads/${me}/${gid}` }
    ]
  }, [me, gid])
  const snaps = useLiveMany(base)
  const group = useMemo(() => (gid ? parseGroup(gid, snaps.get('group')?.val) : null), [gid, snaps])
  const stored = useMemo(() => parseChannels((snaps.get('group')?.val as { channels?: unknown } | null)?.channels), [snaps])
  // The newest message of every text channel other than #general (for the unread dot).
  const latestSpecs = useMemo(
    (): LiveSpec[] =>
      gid
        ? stored
            .filter((c) => c.kind === 'text')
            .map((c) => ({ key: c.id, path: `groupChannelMessages/${gid}/${c.id}`, constraints: [orderByChild('sentAt'), limitToLast(1)] }))
        : [],
    [gid, stored]
  )
  const latest = useLiveMany(latestSpecs)
  const occupants = useMemo(() => parseVoiceOccupants(snaps.get('voice')?.val), [snaps])
  const photos = useProfiles(useMemo(() => [...occupants.values()].flat().map((p) => p.uid), [occupants]))
  // The call timers count seconds while anyone is in a voice channel.
  const now = useServerNow(occupants.size > 0 ? 1_000 : 30_000)

  return useMemo(() => {
    if (!me || !gid || !group) return null
    const statuses = parseVoiceStatuses(snaps.get('status')?.val)
    const reads = parseNumberMap(snaps.get('reads')?.children ?? [])
    const { text, voice } = channelList(stored)
    const textVMs: ChannelVM[] = text.map((c) => {
      if (c.id === GENERAL) return { id: c.id, name: c.name, kind: 'text', builtIn: true, unread: generalUnread }
      const newest = parseList(latest.get(c.id)?.children ?? [], parseGroupMessage).at(-1) ?? null
      return { id: c.id, name: c.name, kind: 'text', builtIn: c.builtIn, unread: channelUnread(newest, reads.get(c.id) ?? 0, me) }
    })
    const voiceVMs: ChannelVM[] = voice.map((c) => {
      const people = occupants.get(c.id) ?? []
      const here = c.id === connected
      return {
        id: c.id,
        name: c.name,
        kind: 'voice',
        builtIn: c.builtIn,
        people: people.map(
          (p): VoiceOccupantVM => ({
            id: p.uid,
            name: p.uid === me ? 'You' : p.name,
            photoUrl: photos.get(p.uid)?.photoUrl ?? null,
            isMe: p.uid === me,
            muted: p.muted || p.deafened,
            deafened: p.deafened,
            live: p.live,
            speaking: here && speaking.has(p.uid)
          })
        ),
        elapsed: callElapsed(people, now),
        // A status only means something while someone's there to see it.
        status: people.length > 0 ? statuses.get(c.id) ?? null : null,
        connected: here
      }
    })
    return { chatId: gid, text: textVMs, voice: voiceVMs, canManage: group.members.some((m) => m.uid === me && m.admin) }
  }, [me, gid, group, snaps, stored, latest, occupants, photos, now, generalUnread, connected, speaking])
}

/** A text channel's messages (not #general, which is the group chat): oldest first with day pills. */
export function useChannelMessages(gid: string | null, cid: string | null): MessageVM[] | null {
  const me = useUid()
  const specs = useMemo((): LiveSpec[] => {
    if (!me || !gid || !cid || cid === GENERAL) return []
    return [
      { key: 'msgs', path: `groupChannelMessages/${gid}/${cid}`, constraints: [orderByChild('sentAt'), limitToLast(MESSAGE_LIMIT)] },
      { key: 'group', path: `groups/${gid}` }
    ]
  }, [me, gid, cid])
  const snaps = useLiveMany(specs)
  const group = useMemo(() => (gid ? parseGroup(gid, snaps.get('group')?.val) : null), [gid, snaps])
  const profiles = useProfiles(useMemo(() => group?.members.map((m) => m.uid) ?? [], [group]))
  const now = useServerNow()
  return useMemo(() => {
    const msgs = snaps.get('msgs')
    if (!me || !msgs) return null
    const names = new Map<string, string>()
    for (const [uid, p] of profiles) if (p) names.set(uid, p.displayName)
    const clock = clockNow(now)
    return buildMessageRows(parseList(msgs.children, parseGroupMessage), {
      me,
      clock,
      group: true,
      names,
      roomEvent: null,
      timeOf: (ms) => timeLabel(ms, clock.now, clock)
    })
  }, [me, snaps, profiles, now])
}

// ---------- Writes ----------

export async function sendChannelMessage(gid: string, cid: string, text: string): Promise<void> {
  const me = requireUid()
  const trimmed = text.trim().slice(0, MAX_TEXT)
  if (!trimmed) return
  await set(push(ref(db, `groupChannelMessages/${gid}/${cid}`)), { from: me, text: trimmed, sentAt: serverTimestamp() })
}

export async function markChannelRead(gid: string, cid: string): Promise<void> {
  const me = requireUid()
  await set(ref(db, `groupChannelReads/${me}/${gid}/${cid}`), serverTimestamp())
}

/** Admins: a new text or voice channel, last of its kind. Returns its id. */
export async function createChannel(gid: string, name: string, kind: ChannelKind): Promise<string> {
  const me = requireUid()
  const clean = cleanChannelName(name, kind)
  if (!clean) throw new Error('Give the channel a name')
  const stored = parseChannels((await get(ref(db, `groups/${gid}/channels`))).val())
  const target = push(ref(db, `groups/${gid}/channels`))
  if (!target.key) throw new Error('No channel id')
  await set(target, { name: clean, kind, order: nextOrder(stored, kind), createdBy: me, createdAt: serverTimestamp() })
  return target.key
}

export async function renameChannel(gid: string, cid: string, name: string, kind: ChannelKind): Promise<void> {
  requireUid()
  const clean = cleanChannelName(name, kind)
  if (!clean) throw new Error('Give the channel a name')
  await set(ref(db, `groups/${gid}/channels/${cid}/name`), clean)
}

/** Admins: the channel goes with its messages, its status and anyone still listed in it. */
export async function deleteChannel(gid: string, cid: string): Promise<void> {
  requireUid()
  if (cid === GENERAL || cid === LOUNGE) throw new Error("This channel can't be deleted")
  const inVoice = Object.keys(((await get(ref(db, `groupVoice/${gid}/${cid}`))).val() as Record<string, unknown> | null) ?? {})
  const changes: Record<string, unknown> = {
    [`groups/${gid}/channels/${cid}`]: null,
    [`groupChannelMessages/${gid}/${cid}`]: null,
    [`groupVoiceStatus/${gid}/${cid}`]: null
  }
  for (const uid of inVoice) changes[`groupVoice/${gid}/${cid}/${uid}`] = null
  await update(ref(db), changes)
}

// ---------- Who's in a voice channel ----------

/** We're in [cid]: listed for the group until we leave or this PC drops off (onDisconnect). */
export async function joinVoiceChannel(gid: string, cid: string, name: string, muted: boolean, deafened: boolean): Promise<void> {
  const me = requireUid()
  const entry = ref(db, `groupVoice/${gid}/${cid}/${me}`)
  await onDisconnect(entry).remove()
  await set(entry, { name: name.slice(0, 40) || 'Friend', since: serverTimestamp(), muted, deafened })
}

export async function updateVoiceChannel(gid: string, cid: string, muted: boolean, deafened: boolean): Promise<void> {
  const me = requireUid()
  await update(ref(db, `groupVoice/${gid}/${cid}/${me}`), { muted, deafened })
}

/** We started or stopped sharing our screen in [cid] (the LIVE badge for everyone in the group). */
export async function setVoiceLive(gid: string, cid: string, live: boolean): Promise<void> {
  const me = requireUid()
  await update(ref(db, `groupVoice/${gid}/${cid}/${me}`), { live })
}

/** Leaving: the last one out also clears the channel's status (Discord does the same). */
export async function leaveVoiceChannel(gid: string, cid: string): Promise<void> {
  const me = requireUid()
  const entry = ref(db, `groupVoice/${gid}/${cid}/${me}`)
  const others = Object.keys(((await get(ref(db, `groupVoice/${gid}/${cid}`)).catch(() => null))?.val() as Record<string, unknown> | null) ?? {}).filter(
    (u) => u !== me
  )
  if (others.length === 0) await remove(ref(db, `groupVoiceStatus/${gid}/${cid}`)).catch(() => undefined)
  await remove(entry)
  await onDisconnect(entry).cancel().catch(() => undefined)
}

/** "Set a channel status": anyone in the voice channel; empty clears it. */
export async function setChannelStatus(gid: string, cid: string, text: string): Promise<void> {
  const me = requireUid()
  const clean = text.replace(/\s+/g, ' ').trim().slice(0, CHANNEL_STATUS_MAX)
  const target = ref(db, `groupVoiceStatus/${gid}/${cid}`)
  if (!clean) await remove(target)
  else await set(target, { text: clean, by: me, at: serverTimestamp() })
}

/** Room voice (rooms/{code}/members/{uid}/voice): phones show who's talking in the room. */
export async function setRoomVoiceFlag(code: string, on: boolean): Promise<void> {
  const me = requireUid()
  const flag = ref(db, `rooms/${code}/members/${me}/voice`)
  if (on) {
    await onDisconnect(flag).remove()
    await set(flag, true)
  } else {
    await remove(flag)
    await onDisconnect(flag).cancel().catch(() => undefined)
  }
}
