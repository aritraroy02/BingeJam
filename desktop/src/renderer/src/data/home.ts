import { useEffect, useMemo, useState } from 'react'
import type { HomeVM, LiveRoomVM, TrendingVM } from '@/ui/models'
import { useChatData } from './chats.ts'
import { useFriends } from './friends.ts'
import { useUid } from './live.ts'
import { fetchTrending, fetchWatchLink } from './relay.ts'
import { parseTrendingId, trendingVMs } from './trendingModel.ts'

// Home: trending (relay GET /trending), friends, and a banner for a chat whose room is live.
// continueWatching stays null: "watched together" cards only hold how long two people watched,
// not where they stopped or the title's length, so there's no honest progress bar to draw.

export type TrendingKind = 'movies' | 'series'

/** Posters for Movies / Web series (Netflix, JioHotstar, Prime Video interleaved); null while loading. */
export function useTrending(kind: TrendingKind): TrendingVM[] | null {
  const uid = useUid()
  const [state, setState] = useState<{ kind: TrendingKind; items: TrendingVM[] } | null>(null)
  useEffect(() => {
    if (!uid) return
    let cancelled = false
    void fetchTrending({ type: kind === 'series' ? 'tv' : 'movie' }).then((rows) => {
      if (!cancelled) setState({ kind, items: trendingVMs(rows) })
    })
    return () => {
      cancelled = true
    }
  }, [kind, uid])
  return state?.kind === kind ? state.items : null
}

/** A trending poster's link in its app (Netflix/JioHotstar start it; Prime opens its page); null = not found. */
export async function trendingWatchLink(trendingId: string): Promise<string | null> {
  const t = parseTrendingId(trendingId)
  return t ? fetchWatchLink(t.tmdbId, t.type, t.service) : null
}

export function useHome(kind: TrendingKind): HomeVM {
  const trending = useTrending(kind)
  const friends = useFriends()
  const chats = useChatData()
  const liveRoom = useMemo((): LiveRoomVM | null => {
    if (!chats) return null
    // Rooms we're not in yet first (that's what "Join" is for), newest first.
    const live = [
      ...chats.dms.flatMap((d) => (d.room ? [{ chatId: d.chatId, chatName: d.friend.name, room: d.room }] : [])),
      ...chats.groups.flatMap((g) => (g.room ? [{ chatId: g.group.id, chatName: g.group.name, room: g.room }] : []))
    ].sort((a, b) => Number(a.room.inRoom.includes(chats.me)) - Number(b.room.inRoom.includes(chats.me)) || b.room.startedAt - a.room.startedAt)
    return live[0] ? { chatId: live[0].chatId, chatName: live[0].chatName } : null
  }, [chats])
  return {
    continueWatching: null,
    liveRoom,
    trending,
    friends,
    onlineCount: friends?.filter((f) => f.online).length ?? 0
  }
}
