import { useMemo } from 'react'
import { limitToLast, orderByChild, push, ref, serverTimestamp, set } from 'firebase/database'
import { db } from '@/lib/firebase'
import type { MessageVM } from '@/ui/models'
import { requireUid, useLive, useUid } from './live.ts'
import { isRoomCode } from './ids.ts'

// The room's own chat and emoji reactions, where phones keep them (FirebaseSyncTransport):
//   chats/{code}/{id}     {uid, name, text (1–500), sentAt (server time)}
//   reactions/{code}/{id} {uid, name, emoji (≤16), sentAt (server time)}
// Members only; both are deleted with the room when the last one leaves.

const LIMIT = 100
const NAME_MAX = 40

export interface RoomMessage {
  id: string
  uid: string
  name: string
  text: string
  sentAt: number
}

export interface RoomReaction {
  id: string
  uid: string
  name: string
  emoji: string
  sentAt: number
}

export function parseRoomMessage(id: string, v: unknown): RoomMessage | null {
  const o = (v ?? {}) as Record<string, unknown>
  if (typeof o.uid !== 'string' || typeof o.text !== 'string' || typeof o.sentAt !== 'number') return null
  return { id, uid: o.uid, name: typeof o.name === 'string' ? o.name : 'Someone', text: o.text, sentAt: o.sentAt }
}

export function parseRoomReaction(id: string, v: unknown): RoomReaction | null {
  const o = (v ?? {}) as Record<string, unknown>
  if (typeof o.uid !== 'string' || typeof o.emoji !== 'string' || typeof o.sentAt !== 'number') return null
  return { id, uid: o.uid, name: typeof o.name === 'string' ? o.name : 'Someone', emoji: o.emoji, sentAt: o.sentAt }
}

function path(root: 'chats' | 'reactions', code: string | null): string | null {
  return code && isRoomCode(code) ? `${root}/${code}` : null
}

/** Room messages, oldest first (null while loading or not in a room). */
export function useRoomMessages(code: string | null): RoomMessage[] | null {
  const snap = useLive(path('chats', code), [orderByChild('sentAt'), limitToLast(LIMIT)], 'recent')
  return useMemo(() => (snap ? snap.children.flatMap(([id, v]) => parseRoomMessage(id, v) ?? []).sort((a, b) => a.sentAt - b.sentAt) : null), [snap])
}

/** For the room screen's chat panel. */
export function useRoomChat(code: string | null): MessageVM[] | null {
  const me = useUid()
  const messages = useRoomMessages(code)
  return useMemo(
    () =>
      messages?.map((m) => ({
        kind: 'text' as const,
        id: m.id,
        fromMe: m.uid === me,
        senderName: m.name,
        senderId: m.uid,
        text: m.text,
        sentAt: m.sentAt
      })) ?? null,
    [messages, me]
  )
}

/** The last few reactions (for floating emoji in the extension overlay and the room screen). */
export function useRoomReactions(code: string | null): RoomReaction[] | null {
  const snap = useLive(path('reactions', code), [orderByChild('sentAt'), limitToLast(20)], 'recent')
  return useMemo(() => (snap ? snap.children.flatMap(([id, v]) => parseRoomReaction(id, v) ?? []).sort((a, b) => a.sentAt - b.sentAt) : null), [snap])
}

export async function sendRoomMessage(code: string, name: string, text: string): Promise<void> {
  const uid = requireUid()
  const clean = text.trim().slice(0, 500)
  if (!clean || !isRoomCode(code)) return
  await set(push(ref(db, `chats/${code}`)), { uid, name: name.slice(0, NAME_MAX) || 'Guest', text: clean, sentAt: serverTimestamp() })
}

export async function sendRoomReaction(code: string, name: string, emoji: string): Promise<void> {
  const uid = requireUid()
  if (!emoji || emoji.length > 16 || !isRoomCode(code)) return
  await set(push(ref(db, `reactions/${code}`)), { uid, name: name.slice(0, NAME_MAX) || 'Guest', emoji, sentAt: serverTimestamp() })
}
