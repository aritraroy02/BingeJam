// Room screen view model from the room session's state (roomSession.ts) and the chat the room
// belongs to. Pure (no Firebase, no DOM) so it runs under `node --test`.

import type { GroupMemberVM, RoomMemberVM, RoomVM } from '../ui/models.ts'
import { SERVICES } from '../data/catalog.ts'
import type { MemberSync, RoomSyncState } from './roomSession.ts'

export interface RoomVmInput {
  state: RoomSyncState
  code: string
  chatId: string
  /** The chat's name (friend's name for a 1:1 chat); null while loading. */
  chatName: string | null
  chatKind: 'dm' | 'group' | null
  /** The chat's members (group) or the friend (1:1), for "Not here yet". */
  chatPeople: readonly GroupMemberVM[]
  photos: ReadonlyMap<string, string | null>
  /** Browser the extension is linked in ("Edge"); null = not linked. */
  browser: string | null
  waitForBuffering: boolean
  /** Local monotonic time (same base as state.positionAt). */
  localNow: number
}

/** "+0.4 s" / "−1.2 s" (ahead / behind the room). */
export function offsetLabel(ms: number): string {
  const s = Math.abs(ms) / 1000
  return `${ms >= 0 ? '+' : '−'}${s.toFixed(1)} s`
}

export function memberSyncLabel(m: MemberSync): string {
  switch (m.health) {
    case 'inSync':
      return 'In sync'
    case 'catchingUp':
      return m.offsetMs !== null ? offsetLabel(m.offsetMs) : 'Catching up'
    case 'buffering':
      return 'Buffering'
    case 'noPlayer':
      return m.isMe ? 'Nothing playing here' : 'Not playing'
  }
}

/** Where the room's position is now: the last reading moved on while playing, never past the end. */
export function livePositionMs(state: RoomSyncState, localNow: number): number {
  if (state.positionMs === null) return 0
  let at = state.positionMs + (state.playing ? Math.max(0, localNow - state.positionAt) : 0)
  if (state.durationMs !== null && state.durationMs > 0) at = Math.min(at, state.durationMs)
  return Math.max(0, at)
}

export function buildRoomVM(i: RoomVmInput): RoomVM {
  const s = i.state
  const service = s.service ? SERVICES[s.service].name : null
  const title = s.title ? (s.subtitle ? `${s.title} · ${s.subtitle}` : s.title) : 'Nothing playing yet'
  const context =
    i.chatKind === 'group' && i.chatName
      ? `${i.chatName} · group room`
      : i.chatKind === 'dm' && i.chatName
        ? `Room with ${i.chatName}`
        : `Room ${i.code}`

  let playerLabel: string
  let playerAction: string | undefined
  if (s.tabId !== null) {
    playerLabel = `Playing in your ${service ?? 'browser'} tab${i.browser ? ` · ${i.browser}` : ''}`
  } else if (i.browser) {
    playerLabel = `Open ${service ?? 'Netflix, JioHotstar or Prime Video'} in ${i.browser} and play the title`
    playerAction = `Open ${service ?? 'Netflix'}`
  } else {
    playerLabel = 'Add BingeJam to your browser to sync the movie on this PC'
    playerAction = 'Add to browser'
  }

  const inRoom = new Set(s.members.map((m) => m.uid))
  const members: RoomMemberVM[] = s.members.map((m) => ({
    id: m.uid,
    name: m.isMe ? 'You' : m.name,
    photoUrl: i.photos.get(m.uid) ?? null,
    online: m.online,
    device: m.isMe ? (i.browser ? `This PC · ${i.browser}` : 'This PC') : m.online ? 'In the room' : 'Away',
    isHost: m.isHost,
    isMe: m.isMe,
    syncLabel: memberSyncLabel(m),
    health: m.health
  }))

  return {
    code: i.code,
    chatId: i.chatId,
    context,
    title,
    service,
    playerLabel,
    playerAction,
    playing: s.playing,
    positionMs: livePositionMs(s, i.localNow),
    durationMs: s.durationMs,
    syncLabel: s.syncLabel || (s.tabId === null ? 'Waiting for the movie' : ''),
    allInSync: s.allInSync,
    members,
    notHere: i.chatPeople.filter((p) => !p.isMe && !inRoom.has(p.id)),
    // Voice chat isn't on the desktop yet.
    voice: { members: [], quality: 'HD', pushToTalkKey: null, joined: false, muted: false },
    settings: { waitForBuffering: i.waitForBuffering, subtitles: null }
  }
}
