import type {
  AddFriendResult,
  BrowserId,
  BrowserSetupVM,
  ChatHeaderVM,
  ChatSummaryVM,
  FriendsVM,
  GroupMemberVM,
  HomeVM,
  MessageVM,
  PersonVM,
  RoomVM,
  SettingsVM,
  StreamQuality,
  StreamSource,
  StreamVM
} from '@/ui/models'

// Everything the app shell (App.tsx) needs: the view models for every screen plus what each button
// does. Demo mode fills it from demo/useDemo.ts; the Firebase data layer fills it for real accounts.
// Screens stay props-only; only App.tsx reads this.

export interface ShellData {
  me: { uid: string; name: string; username: string; photoUrl: string | null }
  home: HomeVM
  chats: ChatSummaryVM[] | null
  header: (chatId: string) => ChatHeaderVM | null
  messages: (chatId: string) => MessageVM[] | null
  members: (chatId: string) => GroupMemberVM[] | null
  /** Friends as plain people, for the New group / Add people pickers. */
  friendPeople: PersonVM[] | null
  /** The room this PC is in (null = not in one). */
  room: RoomVM | null
  roomChat: MessageVM[] | null
  stream: StreamVM
  streamChat: MessageVM[] | null
  media: MediaStream | null
  browser: BrowserSetupVM
  settings: SettingsVM
  friends: FriendsVM
  badges: { chats?: number; friends?: number }
  act: ShellActions
}

export interface ShellActions {
  send: (chatId: string, text: string) => void
  typing: (chatId: string, typing: boolean) => void
  ping: (chatId: string, uid: string) => void
  rename: (chatId: string, name: string) => void
  /** Returns the new group's chat id (so the app can open it), or null. */
  newGroup: (name: string, memberIds: string[]) => string | null
  addPeople: (chatId: string, memberIds: string[]) => void
  deleteChat: (chatId: string) => void
  /** Start (or rejoin) the room that belongs to this chat. */
  startRoom: (chatId: string) => void
  joinRoom: (chatId: string) => void
  leaveRoom: () => void
  room: {
    send: (text: string) => void
    togglePlay: () => void
    seekBy: (deltaMs: number) => void
    seekTo: (positionMs: number) => void
    subtitles: () => void
    waitForBuffering: (on: boolean) => void
    syncAllToMe: () => void
    invite: () => void
    showPlayer: () => void
    toggleMute: () => void
    joinVoice: () => void
  }
  stream: {
    source: (s: StreamSource) => void
    quality: (q: StreamQuality) => void
    start: () => void
    stop: () => void
    send: (text: string) => void
  }
  addToBrowser: (browser: BrowserId) => void
  settings: {
    signOut: () => void
    inputDevice: (id: string | null) => void
    outputDevice: (id: string | null) => void
    pushToTalkKey: (key: string | null) => void
    startWithWindows: (on: boolean) => void
  }
  friends: {
    add: (username: string) => Promise<AddFriendResult>
    accept: (uid: string) => void
    decline: (uid: string) => void
    cancel: (uid: string) => void
  }
}
