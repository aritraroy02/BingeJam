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
