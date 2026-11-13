import { useEffect, useRef, useState } from 'react'
import { onValue, ref } from 'firebase/database'
import type { DesktopPrefs } from '@shared/desktop'
import { db } from '@/lib/firebase'
import type { ChatSummaryVM } from '@/ui/models'
import type { FriendRequestVM } from '@/data'
import type { JoinRequest, RoomInvite } from '@/data/watchInvites'

// Windows integration seen from the UI: start with Windows / tray preferences, toast
// notifications for what arrives while you're not looking, the taskbar badge, and the
// connection banner.

const NOTIFY_KEY = 'bj.notifications'

export function useDesktopPrefs(): {
  prefs: DesktopPrefs | null
  startWithWindows: boolean | null
  notifications: boolean
  setStartWithWindows: (on: boolean) => void
  setPrefs: (p: Partial<DesktopPrefs>) => void
  setNotifications: (on: boolean) => void
} {
  const [prefs, setPrefsState] = useState<DesktopPrefs | null>(null)
  const [login, setLogin] = useState<boolean | null>(null)
  const [notifications, setNotificationsState] = useState(() => {
    try {
      return localStorage.getItem(NOTIFY_KEY) !== '0'
    } catch {
      return true
    }
  })
  useEffect(() => {
    let live = true
    void window.bingejam.desktop.get().then((d) => {
      if (!live) return
      setPrefsState(d.prefs)
      setLogin(d.startWithWindows)
    })
    const off = window.bingejam.desktop.onPrefs((p) => live && setPrefsState(p))
    return () => {
      live = false
      off()
    }
  }, [])
  return {
    prefs,
    startWithWindows: login,
    notifications,
    setStartWithWindows: (on) => void window.bingejam.desktop.setStartWithWindows(on).then(setLogin),
    setPrefs: (p) => void window.bingejam.desktop.setPrefs(p).then(setPrefsState),
    setNotifications: (on) => {
      setNotificationsState(on)
      try {
        localStorage.setItem(NOTIFY_KEY, on ? '1' : '0')
      } catch {
        // Only this session.
      }
    }
  }
}

/** The window is in front and the person is looking at it. */
function looking(): boolean {
  return document.visibilityState === 'visible' && document.hasFocus()
}

export interface NotifierInput {
  enabled: boolean
  chats: ChatSummaryVM[] | null
  requests: FriendRequestVM[] | null
  invites: RoomInvite[] | null
  joinRequests: JoinRequest[] | null
  /** The chat on screen right now (no toast for it while you're looking). */
  viewing: string | null
}

/**
 * Toasts for new messages, friend requests, room invites and join requests, plus the taskbar badge.
 * The first reading of each list only seeds what's "already seen", so starting the app doesn't
 * replay old messages.
 */
export function useNotifier(i: NotifierInput): void {
  const seen = useRef({ chats: null as Map<string, number> | null, requests: null as Set<string> | null, invites: null as Set<string> | null, joins: null as Set<string> | null })

  useEffect(() => {
    if (!i.chats) return
    const prev = seen.current.chats
    const next = new Map(i.chats.map((c) => [c.chatId, c.sortKey]))
    seen.current.chats = next
    if (!prev || !i.enabled) return
    for (const c of i.chats) {
      const before = prev.get(c.chatId)
      // New message from someone else: the chat moved forward and has unread messages.
      if (c.unread > 0 && (before === undefined || c.sortKey > before)) {
        if (looking() && i.viewing === c.chatId) continue
        window.bingejam.desktop.notify({ tag: `chat:${c.chatId}`, title: c.name, body: c.preview || 'New message', action: `chat:${c.chatId}` })
      }
    }
  }, [i.chats])

  useEffect(() => {
    if (!i.requests) return
    const prev = seen.current.requests
    seen.current.requests = new Set(i.requests.map((r) => r.fromUid))
    if (!prev || !i.enabled) return
    for (const r of i.requests)
      if (!prev.has(r.fromUid))
        window.bingejam.desktop.notify({ tag: `friend:${r.fromUid}`, title: 'Friend request', body: `${r.name} (@${r.username}) wants to be friends`, action: 'friends' })
  }, [i.requests])

  useEffect(() => {
    if (!i.invites) return
    const prev = seen.current.invites
    seen.current.invites = new Set(i.invites.map((v) => `${v.fromUid}:${v.roomCode}`))
    if (!prev || !i.enabled) return
    for (const v of i.invites)
      if (!prev.has(`${v.fromUid}:${v.roomCode}`))
        window.bingejam.desktop.notify({
          tag: `invite:${v.fromUid}`,
          title: `${v.fromName} invited you`,
          body: v.title ? `Watch ${v.title} together` : 'Join their room',
          action: `invite:${v.fromUid}`
        })
  }, [i.invites])

  useEffect(() => {
    if (!i.joinRequests) return
    const prev = seen.current.joins
    seen.current.joins = new Set(i.joinRequests.map((j) => j.fromUid))
    if (!prev || !i.enabled) return
    for (const j of i.joinRequests)
      if (!prev.has(j.fromUid))
        window.bingejam.desktop.notify({ tag: `join:${j.fromUid}`, title: `${j.fromName} asks to join`, body: j.title ? `You're watching ${j.title}` : 'Let them into your room?', action: `join:${j.fromUid}` })
  }, [i.joinRequests])

  const count =
    (i.chats?.reduce((n, c) => n + c.unread, 0) ?? 0) + (i.requests?.length ?? 0) + (i.invites?.length ?? 0) + (i.joinRequests?.length ?? 0)
  useEffect(() => {
    window.bingejam.desktop.setBadge(count)
  }, [count])
}

export type ConnectionState = 'online' | 'offline' | 'back'

/** Firebase's own connection flag (with a short grace period), for the "You're offline" banner. */
export function useConnection(): ConnectionState {
  const [state, setState] = useState<ConnectionState>('online')
  useEffect(() => {
    let everConnected = false
    let offlineTimer: ReturnType<typeof setTimeout> | null = null
    let backTimer: ReturnType<typeof setTimeout> | null = null
    let wasOffline = false
    const stop = onValue(ref(db, '.info/connected'), (s) => {
      const connected = s.val() === true
      if (connected) {
        if (offlineTimer) clearTimeout(offlineTimer)
        offlineTimer = null
        if (wasOffline) {
          wasOffline = false
          setState('back')
          if (backTimer) clearTimeout(backTimer)
          backTimer = setTimeout(() => setState('online'), 3_000)
        }
        everConnected = true
      } else if (everConnected && !offlineTimer) {
        // Brief blips (Wi-Fi roaming) don't deserve a banner.
        offlineTimer = setTimeout(() => {
          offlineTimer = null
          wasOffline = true
          setState('offline')
        }, 2_500)
      }
    })
    return () => {
      stop()
      if (offlineTimer) clearTimeout(offlineTimer)
      if (backTimer) clearTimeout(backTimer)
    }
  }, [])
  return state
}
