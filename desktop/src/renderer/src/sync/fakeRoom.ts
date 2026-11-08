// Test doubles for the room backend: an in-memory "server" with Firebase's room semantics
// (server-stamped updatedAt, seq + 1 per state write, members-only writes, every listener told,
// including the writer) and manually driven timers. Tests only.

import type { RoomTransport, Timers, Unsubscribe } from '../../../shared/sync/transport.ts'
import type { Member, NowPlaying, Room, RoomPreview, ServerClock, StateBroadcast } from '../../../shared/sync/types.ts'

interface ServerRoom {
  hostUid: string
  room: Room
  preview: RoomPreview | null
}

export class FakeRoomServer {
  private readonly serverNow: () => number
  private readonly rooms = new Map<string, ServerRoom>()
  private readonly roomListeners = new Map<string, Set<(room: Room | null) => void>>()
  private readonly previewListeners = new Map<string, Set<(preview: RoomPreview | null) => void>>()
  /** Every state write, in server order. */
  readonly stateWrites: Array<StateBroadcast & { by: string; seq: number }> = []
  readonly nowPlayingWrites: NowPlaying[] = []

  constructor(serverNow: () => number) {
    this.serverNow = serverNow
  }

  createRoom(code: string, hostUid: string, hostName: string): void {
    const now = this.serverNow()
    this.rooms.set(code, {
      hostUid,
      room: { code, hostUid, createdAt: now, title: null, state: null, members: [], countdown: null },
      preview: { code, hostName, service: null, title: null, subtitle: null, durationMs: null, playableRef: null, updatedAt: now }
    })
    this.join(code, hostUid, hostName)
  }

  join(code: string, uid: string, name: string): void {
    const r = this.must(code)
    const member: Member = { uid, name, online: true, positionMs: null, reportedAt: null, lastSeen: this.serverNow(), inVoice: false }
    r.room = { ...r.room, members: [...r.room.members.filter((m) => m.uid !== uid), member] }
    this.notifyRoom(code)
  }

  room(code: string): Room {
    return this.must(code).room
  }

  preview(code: string): RoomPreview | null {
    return this.must(code).preview
  }

  transport(uid: string): RoomTransport {
    const clock: ServerClock = { serverNowMs: () => this.serverNow() }
    return {
      uid,
      serverClock: clock,
      observeRoom: (code, listener) => this.listen(this.roomListeners, code, listener, () => this.rooms.get(code)?.room ?? null),
      observePreview: (code, listener) => this.listen(this.previewListeners, code, listener, () => this.rooms.get(code)?.preview ?? null),
      writeState: async (code, b) => {
        const r = this.member(code, uid)
        const seq = (r.room.state?.seq ?? 0) + 1
        r.room = { ...r.room, state: { status: b.status, positionMs: Math.max(0, Math.round(b.positionMs)), updatedAt: this.serverNow(), updatedBy: uid, seq } }
        this.stateWrites.push({ ...b, by: uid, seq })
        this.notifyRoom(code)
      },
      reportPosition: async (code, positionMs) => {
        const r = this.member(code, uid)
        r.room = {
          ...r.room,
          members: r.room.members.map((m) => (m.uid === uid ? { ...m, positionMs: Math.round(positionMs), reportedAt: this.serverNow() } : m))
        }
        this.notifyRoom(code)
      },
      startCountdown: async (code, leadMs) => {
        const r = this.member(code, uid)
        r.room = { ...r.room, countdown: { requestedAt: this.serverNow(), leadMs, startedBy: uid } }
        this.notifyRoom(code)
      },
      setNowPlaying: async (code, np) => {
        const r = this.must(code)
        if (r.hostUid !== uid || r.preview === null) throw new Error('permission denied')
        r.preview = { ...r.preview, ...np, playableRef: null, updatedAt: this.serverNow() }
        this.nowPlayingWrites.push(np)
        for (const l of this.previewListeners.get(code) ?? []) l(r.preview)
      }
    }
  }

  private must(code: string): ServerRoom {
    const r = this.rooms.get(code)
    if (r === undefined) throw new Error(`no room ${code}`)
    return r
  }

  /** Rules: only members write state, countdown and their own position. */
  private member(code: string, uid: string): ServerRoom {
    const r = this.must(code)
    if (!r.room.members.some((m) => m.uid === uid)) throw new Error('permission denied')
    return r
  }

  private listen<T>(map: Map<string, Set<(v: T) => void>>, code: string, listener: (v: T) => void, current: () => T): Unsubscribe {
    let set = map.get(code)
    if (set === undefined) map.set(code, (set = new Set()))
    set.add(listener)
    listener(current())
    return () => set.delete(listener)
  }

  private notifyRoom(code: string): void {
    const room = this.rooms.get(code)?.room ?? null
    for (const l of [...(this.roomListeners.get(code) ?? [])]) l(room)
  }
}

/** Timers fired by hand: runDue() runs everything due at now(), in time order. */
export class ManualTimers implements Timers {
  private readonly now: () => number
  private nextId = 1
  private readonly entries = new Map<number, { at: number; every: number | null; fn: () => void }>()

  constructor(now: () => number) {
    this.now = now
  }

  setInterval(fn: () => void, ms: number): unknown {
    const id = this.nextId++
    this.entries.set(id, { at: this.now() + ms, every: ms, fn })
    return id
  }
  clearInterval(handle: unknown): void {
    this.entries.delete(handle as number)
  }
  setTimeout(fn: () => void, ms: number): unknown {
    const id = this.nextId++
    this.entries.set(id, { at: this.now() + ms, every: null, fn })
    return id
  }
  clearTimeout(handle: unknown): void {
    this.entries.delete(handle as number)
  }

  runDue(): void {
    for (;;) {
      const now = this.now()
      let dueId: number | null = null
      let dueAt = Number.POSITIVE_INFINITY
      for (const [id, e] of this.entries) {
        if (e.at <= now && e.at < dueAt) {
          dueId = id
          dueAt = e.at
        }
      }
      if (dueId === null) return
      const entry = this.entries.get(dueId)
      if (entry === undefined) return
      if (entry.every !== null) entry.at += entry.every
      else this.entries.delete(dueId)
      entry.fn()
    }
  }
}
