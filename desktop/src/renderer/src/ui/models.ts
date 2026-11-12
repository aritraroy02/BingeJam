// View models: what the screens draw. The Firebase data layer (src/renderer/src/data) and the demo
// data (src/renderer/src/demo) both map into these, so screens never touch the database directly.

export interface PersonVM {
  id: string
  name: string
  username?: string
  photoUrl?: string | null
  online?: boolean
}

export type FriendAction = 'join' | 'watch' | 'jam' | null

export interface FriendVM extends PersonVM {
  /** "Midnight Signal · S2:E4 · Netflix", "Online", "Last seen 2 h ago" */
  status: string
  /** Red "Live ·" prefix, e.g. someone streaming a game. */
  streaming?: boolean
  action: FriendAction
}

export interface TrendingVM {
  id: string
  title: string
  service: string
  rating?: number
  posterUrl?: string | null
  /** Fallback poster colours when there's no image. */
  gradient: string
}

export interface ContinueVM {
  title: string
  subtitle: string
  progress: number // 0..1
  leftLabel: string
  people: PersonVM[]
  posterUrl?: string | null
  gradient: string
}

export interface LiveRoomVM {
  chatId: string
  chatName: string
}

export interface HomeVM {
  continueWatching: ContinueVM | null
  liveRoom: LiveRoomVM | null
  trending: TrendingVM[] | null // null = loading
  friends: FriendVM[] | null
  onlineCount: number
}

// ---------- Chats (1:1 and groups are one list; every chat has its own room) ----------

/** A 1:1 chat keeps the Android dmId (the two uids sorted, joined with "_"); a group is its gid. */
export type ChatKind = 'dm' | 'group'

export interface ChatSummaryVM {
  chatId: string
  kind: ChatKind
  name: string
  /** 1:1: the friend; group: up to two members for the stacked avatar. */
  people: PersonVM[]
  /** "You: Count me in", "Meera: who's free Saturday?" */
  preview: string
  /** "9:02", "Mon", "Sun" (already formatted) */
  timeLabel: string
  unread: number
  /** Room running for this chat right now: "Room live · 3 in". */
  liveRoom: { inCount: number } | null
  /** Server time of the newest message, for sorting. */
  sortKey: number
}

export type MessageVM =
  | { kind: 'text'; id: string; fromMe: boolean; senderName?: string; senderId: string; text: string; sentAt: number; forwarded?: boolean; deleted?: boolean }
  | { kind: 'watched'; id: string; title: string; durationLabel: string; detail: string; sentAt: number }
  | { kind: 'event'; id: string; text: string; sentAt: number } // "Riya started a room · 9:02", "Kabir joined from his phone"
  | { kind: 'day'; id: string; label: string } // "Today", "Yesterday"

export interface ChatHeaderVM {
  chatId: string
  kind: ChatKind
  name: string
  people: PersonVM[]
  /** 1:1: "Watching Midnight Signal · Netflix" / "Online" / "Last seen 2 h ago"; group: "5 members · 3 in the room" */
  subtitle: string
  /** The chat's room: null when nobody is in it (button says "Start room"); else live info. */
  room: {
    code: string
    inCount: number
    startedAtLabel: string
    summary: string
    names: PersonVM[]
    /** You're already in this room: the button says "Open room" instead of "Join room". */
    meInRoom?: boolean
  } | null
  /** Group only. */
  canRename?: boolean
}

export interface GroupMemberVM extends PersonVM {
  /** "Started the room", "On his phone", "Talking", "Join any time", "Offline · 2 h" */
  detail: string
  inRoom: boolean
  isMe: boolean
  /** Not in the room: can be pinged, or was pinged recently ("Pinged · 1 min"). */
  ping: 'can' | { pingedLabel: string } | null
}

// ---------- Inside a room ----------

export type SyncHealth = 'inSync' | 'catchingUp' | 'buffering' | 'noPlayer'

export interface RoomMemberVM extends PersonVM {
  /** "Laptop · Edge", "This PC · Edge", "Phone · Netflix app" */
  device: string
  isHost: boolean
  isMe: boolean
  /** "In sync", "+0.1 s", "Catching up" */
  syncLabel: string
  health: SyncHealth
}

export interface VoiceMemberVM extends PersonVM {
  speaking: boolean
  muted: boolean
  isMe: boolean
}

export interface RoomVM {
  code: string
  chatId: string | null
  /** "Movie Night · group room · started by Riya" */
  context: string
  /** "Midnight Signal · S2:E4" */
  title: string
  service: string | null // "Netflix", "Prime Video", "JioHotstar", "Spotify"…
  /** Where the movie plays on this PC: the browser tab the extension controls. */
  playerLabel: string // "Playing in your Netflix tab" / "Open Netflix in your browser"
  /** Button under playerLabel; default "Show the <service> tab". E.g. "Open Netflix" when no tab is open. */
  playerAction?: string
  playing: boolean
  positionMs: number
  durationMs: number | null
  syncLabel: string // "All in sync · ±0.2 s"
  allInSync: boolean
  members: RoomMemberVM[]
  notHere: GroupMemberVM[]
  voice: { members: VoiceMemberVM[]; quality: 'HD' | 'SD'; pushToTalkKey: string | null; joined: boolean; muted: boolean }
  settings: { waitForBuffering: boolean; subtitles: string | null }
}

// ---------- Streams (Go Live) ----------

export type StreamSource = 'game' | 'screen' | 'window'
export type StreamQuality = '1080p60' | '1440p60' | '2160p60'

export interface StreamVM {
  liveHost: PersonVM | null
  /** "Skyline Racers · direct connection" */
  liveDetail: string
  stats: string // "4K · 60 fps · 41 ms"
  viewers: number
  source: StreamSource
  quality: StreamQuality
  /** "4K · 60 needs about 25 Mbps upload per viewer" + measured capacity line. */
  capacityTitle: string
  capacityDetail: string
  /** This PC is streaming right now (Start streaming becomes Stop streaming). */
  streaming?: boolean
  /** People watching, shown as chips over the stream chat ("You", "Meera"). */
  watchers?: PersonVM[]
}

// ---------- Add to browser ----------

export type BrowserId = 'edge' | 'firefox' | 'chrome' | 'brave' | 'opera'

export interface BrowserSetupVM {
  found: { id: BrowserId; name: string }[]
  connected: { browser: string; detail: string } | null
  popup: { roomCode: string; people: string; title: string; position: string; inSync: boolean } | null
}

// ---------- Friends tab ----------

export interface FriendRequestVM extends PersonVM {
  /** "2 h ago" */
  sentLabel: string
}

export interface FriendsVM {
  friends: FriendVM[] | null // null = loading
  /** Requests other people sent you. */
  requests: FriendRequestVM[] | null
  /** Requests you sent that are still waiting. */
  sent: FriendRequestVM[] | null
  /** People you blocked (unblock here). null = loading. */
  blockedPeople?: { id: string; name: string; username: string; photoUrl: string | null }[] | null
  /** Groups you exited and blocked. */
  blockedGroups?: { id: string; name: string }[] | null
}

/** What "Add friend" says back: "Request sent to @riya", "No one has that username". */
export interface AddFriendResult {
  ok: boolean
  message: string
}

// ---------- Settings ----------

export interface DeviceOptionVM {
  id: string
  label: string
}

export interface SettingsVM {
  account: { uid: string; name: string; username: string; email: string | null; photoUrl: string | null }
  extension: { connected: boolean; detail: string }
  voice: {
    /** navigator.mediaDevices.enumerateDevices(); empty = only the system default. */
    inputs: DeviceOptionVM[]
    outputs: DeviceOptionVM[]
    inputId: string | null // null = system default
    outputId: string | null
    /** KeyboardEvent.code-style label shown to people ("V"); null = open mic. */
    pushToTalkKey: string | null
  }
  /** null when the OS setting can't be changed (e.g. a dev build). */
  startWithWindows: boolean | null
  version: string
}
