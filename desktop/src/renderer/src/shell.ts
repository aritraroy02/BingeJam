import type {
  AddFriendResult,
  BrowserId,
  BrowserSetupVM,
  ChatHeaderVM,
  ChatSummaryVM,
  FriendsVM,
  FriendVM,
  GroupMemberVM,
  HomeVM,
  MessageVM,
  InviteVM,
  PersonVM,
  ProfileVM,
  RoomVM,
  SettingsVM,
  StreamQuality,
  StreamSource,
  StreamVM,
  TrendingVM
} from '@/ui/models'
import type { ReportReasonValue } from '@/ui/ReportDialog'
import type { EditProfileValues } from '@/ui/ProfileDialogs'
import type { InviteAlertVM, JoinAlertVM } from '@/ui/Alerts'
import type { Density, ThemeMode } from '@/live/appearance'

/** Something outside the shell (a notification click, an invite link) wants a screen opened. */
export interface NavRequest {
  /** Changes on every request, so the same target can be asked for twice. */
  id: number
  to: 'room' | 'chat' | 'friends' | 'home'
  chatId?: string
}

// Everything the app shell (App.tsx) needs: the view models for every screen plus what each button
// does. Demo mode fills it from demo/useDemo.ts; the Firebase data layer fills it for real accounts.
// Screens stay props-only; only App.tsx reads this.

export type MyStatus = 'online' | 'dnd' | 'invisible'

export interface ShellData {
  me: {
    uid: string
    name: string
    username: string
    photoUrl: string | null
    /** Online / Do not disturb / Invisible (this PC), the activity line and whether it's shared. */
    status?: MyStatus
    activity?: string | null
    shareActivity?: boolean
  }
  home: HomeVM
  chats: ChatSummaryVM[] | null
  header: (chatId: string) => ChatHeaderVM | null
  messages: (chatId: string) => MessageVM[] | null
  members: (chatId: string) => GroupMemberVM[] | null
  /** Friends as plain people, for the New group / Add people pickers. */
  friendPeople: PersonVM[] | null
  /** The room this PC is in (null = not in one). */
  room: RoomVM | null
  /** Real accounts: true while Start/Join room is on its way. When false and room is null, the room view closes. */
  roomStarting?: boolean
  roomChat: MessageVM[] | null
  stream: StreamVM
  streamChat: MessageVM[] | null
  media: MediaStream | null
  browser: BrowserSetupVM
  settings: SettingsVM
  friends: FriendsVM
  badges: { chats?: number; friends?: number }
  /** The profile being looked at (App asks for it with profileUid); null when none or loading. */
  profile: ProfileVM | null
  /** The watchlist in the account, shared with the phone (newest first; null while loading). */
  watchlist: (TrendingVM & { key: string; addedAt: number })[] | null
  /** Our invite link and QR (Friends). */
  invite: InviteVM
  /** Friends with an unanswered invite to the open group (Add people shows "Invited"). */
  invited?: string[]
  /** Our private date of birth ("YYYY-MM-DD"), for Edit profile. */
  myBirthDate: string | null
  /** Edit profile is saving or a picture is uploading. */
  profileBusy?: boolean
  /** A short message for the person ("Invite link copied", an error); shown as a toast. */
  notice?: string | null
  /** Room invites and join requests, shown over every screen. */
  alerts?: { invites: InviteAlertVM[]; joins: JoinAlertVM[] }
  /** Database connection, for the "You're offline" banner. */
  connection?: 'online' | 'offline' | 'back'
  navRequest?: NavRequest | null
  /** The mini room window is open. */
  miniOpen?: boolean
  /** Settings → Appearance and Windows (real accounts). */
  prefs?: {
    theme: ThemeMode
    density: Density
    closeToTray: boolean | null
    globalShortcuts: boolean | null
    notifications: boolean
  }
  act: ShellActions
}

export interface ShellActions {
  send: (chatId: string, text: string) => void
  typing: (chatId: string, typing: boolean) => void
  ping: (chatId: string, uid: string) => void
  rename: (chatId: string, name: string) => void
  /** Returns the new group's chat id (so the app can open it), or null. */
  newGroup: (name: string, memberIds: string[]) => string | null | Promise<string | null>
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
    /** Open / close the always-on-top mini room window. */
    toggleMini?: () => void
    /** An emoji reaction everyone in the room sees (reactions/{code}). */
    react?: (emoji: string) => void
    /** Open the host's exact title in the browser (member). */
    openTitle?: () => void
  }
  /** Room invites ("Join" / "Not now") and join requests ("Let in" / "Not now"). */
  alerts?: {
    join: (fromUid: string) => void
    declineInvite: (fromUid: string) => void
    letIn: (fromUid: string) => void
    declineJoin: (fromUid: string) => void
  }
  preferences?: {
    theme: (t: ThemeMode) => void
    density: (d: Density) => void
    closeToTray: (on: boolean) => void
    globalShortcuts: (on: boolean) => void
    notifications: (on: boolean) => void
  }
  stream: {
    source: (s: StreamSource) => void
    quality: (q: StreamQuality) => void
    start: () => void
    stop: () => void
    send: (text: string) => void
  }
  addToBrowser: (browser: BrowserId) => void
  /** The status menu on your profile card. */
  status?: {
    set: (status: MyStatus) => void
    shareActivity: (on: boolean) => void
  }
  /** The chat on screen (null = none), for read receipts. */
  viewing?: (chatId: string | null) => void
  dismissNotice?: () => void
  /** Shows a toast (App reports' own messages). */
  showNotice?: (text: string) => void
  /** The 1:1 chat id with a friend (opens it even before the first message). */
  dmWith?: (friendUid: string) => string
  /** Home poster: open the title in its app / site. */
  openTitle?: (item: TrendingVM) => void
  /** Friend row's Join / Watch / Jam. */
  friendAction?: (friend: FriendVM) => void
  settings: {
    signOut: () => void
    inputDevice: (id: string | null) => void
    outputDevice: (id: string | null) => void
    pushToTalkKey: (key: string | null) => void
    startWithWindows: (on: boolean) => void
  }
  /** Save a title to the watchlist, or remove it when [saved]. */
  toggleWatchlist: (item: TrendingVM, saved: boolean) => void
  /** Our invite link: copy it, or make a new one (old links stop adding friends automatically). */
  invite: {
    copy: () => void
    reset: () => void
  }
  /** Group admins and settings (2026-11-10). */
  groups: {
    setAdmin: (chatId: string, uid: string, admin: boolean) => void
    remove: (chatId: string, uid: string) => void
    settings: (chatId: string, onlyAdminsSend: boolean, onlyAdminsEdit: boolean) => void
  }
  /** Message actions (2026-11-12): forward to friends, delete in 1:1 chats. */
  messages: {
    forward: (text: string, friendIds: string[]) => void
    deleteForMe: (chatId: string, messageId: string) => void
    deleteForEveryone: (chatId: string, messageId: string) => void
  }
  /** Someone's profile dialog (2026-11-12). */
  profile: {
    add: (username: string) => void
    accept: (uid: string) => void
    cancel: (uid: string) => void
    unblock: (uid: string) => void
    unfriend: (uid: string) => void
    block: (uid: string) => void
    report: (uid: string, name: string, reason: ReportReasonValue, details: string | null, block: boolean) => void
  }
  /** Our own profile: Edit profile in Settings. save() resolves true when everything was saved. */
  editProfile: {
    save: (values: EditProfileValues) => Promise<boolean>
    pickPhoto: (file: File) => void
    removePhoto: () => void
  }
  /** Report and block (2026-11-10): people from their 1:1 chat, groups from the group. */
  safety: {
    block: (chatId: string) => void
    blockGroup: (chatId: string) => void
    report: (chatId: string, reason: ReportReasonValue, details: string | null, block: boolean) => void
    unblockUser: (uid: string) => void
    unblockGroup: (gid: string) => void
  }
  friends: {
    add: (username: string) => Promise<AddFriendResult>
    accept: (uid: string) => void
    decline: (uid: string) => void
    cancel: (uid: string) => void
  }
}
