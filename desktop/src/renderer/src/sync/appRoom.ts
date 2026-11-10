import { useEffect, useMemo, useSyncExternalStore } from 'react'
import type { BridgeMessage } from '@shared/player'
import { activeChatRoom, useActiveChatRoom } from '@/data'
import type { Player } from '../../../shared/sync/transport.ts'
import { FirebaseRoomTransport } from './firebaseRoomTransport'
import { RoomSession, type RoomSyncState } from './roomSession'
import { livePositionMs } from './roomVm'

// The app's one room session: started when this PC enters a chat's room (data/chatRooms.ts sets
// the active room only after our member node is written, which the session needs) and stopped when
// it leaves. Player = the browser extension's tabs, through the preload bridge.

/** Browser tabs via the extension: snapshots / closed tabs in, commands out. */
export function extensionPlayer(): Player {
  return {
    send(message) {
      void window.bingejam.extension.send({ kind: 'command', message })
    },
    subscribe(listener) {
      return window.bingejam.extension.onMessage((m: BridgeMessage) => {
        if (m.kind === 'snapshot' || m.kind === 'tabClosed') listener(m)
      })
    }
  }
}

/** How often the extension popup's room line is refreshed while nothing else changes. */
const ROOM_INFO_REFRESH_MS = 5_000

/** The extension popup's room card, sent when it changes (and its position every few seconds). */
function roomInfoSender(session: RoomSession): () => void {
  let lastKey = ''
  let lastAt = -Infinity
  return session.subscribe((s: RoomSyncState) => {
    const active = activeChatRoom()
    const now = performance.now()
    const room =
      s.code && active
        ? {
            code: s.code,
            title: s.title ? (s.subtitle ? `${s.title} · ${s.subtitle}` : s.title) : null,
            people: `${s.members.length} in the room`,
            inSync: s.allInSync,
            positionMs: Math.round(livePositionMs(s, now))
          }
        : null
    const key = room ? `${room.code}|${room.title}|${room.people}|${room.inSync}|${s.playing}` : 'none'
    if (key === lastKey && now - lastAt < ROOM_INFO_REFRESH_MS) return
    lastKey = key
    lastAt = now
    void window.bingejam.extension.send({ kind: 'roomInfo', room })
  })
}

export interface RoomSync {
  session: RoomSession
  state: RoomSyncState
  active: { chatId: string; code: string } | null
}

export function useRoomSync(uid: string): RoomSync {
  const session = useMemo(
    () => new RoomSession({ transport: new FirebaseRoomTransport({ uid }), player: extensionPlayer(), localNow: () => performance.now() }),
    [uid]
  )
  const active = useActiveChatRoom()
  useEffect(() => roomInfoSender(session), [session])
  useEffect(() => {
    if (active) session.start(active.code)
    else session.stop()
  }, [session, active?.code])
  useEffect(() => () => session.stop(), [session])
  const state = useSyncExternalStore(
    (onChange) => session.subscribe(() => onChange()),
    () => session.state
  )
  return { session, state, active }
}
