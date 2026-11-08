// Room-state transport on the Firebase JS SDK: the sync part of the Android app's
// FirebaseSyncTransport.kt (+ FirebaseServerClock.kt), same paths, fields and semantics, so a phone
// and a PC share one rooms/{code}/state. Everything written must pass
// data-firebase/firebase/database.rules.json:
//  - rooms/{code}/state: members only; updatedAt === now (serverTimestamp), updatedBy === auth.uid,
//    seq > previous seq (increment(1)), positionMs >= 0, no other fields.
//  - rooms/{code}/countdown: members only; requestedAt === now, 1 000 <= leadMs <= 10 000.
//  - rooms/{code}/members/{uid}: our own node only, and it must already have name + online
//    (room creation / joining is src/renderer/src/data's job); reportedAt <= now.
//  - previews/{code}: the host only; must already have hostName (written when the room is created).

import {
  increment,
  onValue,
  ref,
  serverTimestamp,
  set,
  update,
  type Database
} from 'firebase/database'
import { db as appDb } from '../lib/firebase'
import { AnchoredServerClock } from '../../../shared/sync/clock.ts'
import {
  countdownValue,
  nowPlayingUpdate,
  parsePreview,
  parseRoom,
  positionUpdate,
  ROOM_CODE,
  stateUpdate,
  type ServerSentinels
} from '../../../shared/sync/roomParser.ts'
import type { RoomTransport, Unsubscribe } from '../../../shared/sync/transport.ts'
import type { NowPlaying, Room, RoomPreview, StateBroadcast } from '../../../shared/sync/types.ts'

const clocks = new WeakMap<Database, AnchoredServerClock>()

/**
 * The app-wide server-time estimate for `database` (one listener, shared by everything that needs
 * server time). .info/serverTimeOffset = server time − this PC's wall clock; it's anchored once
 * per update with Date.now() (the documented exception to "never the wall clock", exactly like
 * Android's FirebaseServerClock) and then advanced with performance.now(). It's re-anchored when
 * the connection reopens, because performance.now() may stand still while the PC sleeps.
 */
export function firebaseServerClock(database: Database = appDb): AnchoredServerClock {
  const existing = clocks.get(database)
  if (existing) return existing
  const clock = new AnchoredServerClock(
    () => performance.now(),
    () => Date.now() // documented exception: only to interpret Firebase's wall-clock-based offset
  )
  onValue(ref(database, '.info/serverTimeOffset'), (snap) => {
    const offset = snap.val()
    if (typeof offset === 'number') clock.onOffset(offset)
  })
  onValue(ref(database, '.info/connected'), (snap) => {
    if (snap.val() === true && clock.synced) clock.reanchor()
  })
  clocks.set(database, clock)
  return clock
}

function sentinels(): ServerSentinels {
  return { timestamp: serverTimestamp(), increment: increment(1) }
}

function roomPath(code: string, rest = ''): string {
  // Codes go into database paths: never let anything but a real room code through.
  if (!ROOM_CODE.test(code)) throw new Error(`Not a room code: ${code}`)
  return `rooms/${code}${rest}`
}

export interface FirebaseRoomTransportOptions {
  /** The signed-in (non-anonymous) user's uid. */
  uid: string
  db?: Database
}

export class FirebaseRoomTransport implements RoomTransport {
  readonly uid: string
  readonly serverClock: AnchoredServerClock
  private readonly db: Database

  constructor(options: FirebaseRoomTransportOptions) {
    this.uid = options.uid
    this.db = options.db ?? appDb
    this.serverClock = firebaseServerClock(this.db)
  }

  /**
   * One listener on the whole room (state, members, countdown, title), like Android. Emits null if
   * the room doesn't exist, or on "permission denied" (we're not, or no longer, a member: the
   * SDK then drops the listener, so start the session after joining).
   */
  observeRoom(code: string, listener: (room: Room | null) => void): Unsubscribe {
    return onValue(
      ref(this.db, roomPath(code)),
      (snap) => listener(parseRoom(code, snap.val())),
      () => listener(null)
    )
  }

  observePreview(code: string, listener: (preview: RoomPreview | null) => void): Unsubscribe {
    if (!ROOM_CODE.test(code)) throw new Error(`Not a room code: ${code}`)
    return onValue(
      ref(this.db, `previews/${code}`),
      (snap) => listener(parsePreview(code, snap.val())),
      () => listener(null)
    )
  }

  /**
   * One atomic multi-field update. The server stamps updatedAt and increments seq while applying
   * it, so concurrent writers are ordered by arrival at the server: last write wins, and
   * seq/updatedAt agree on which one was last. (The SDK also raises our own write locally first,
   * synchronously; the session queues it.)
   */
  writeState(code: string, broadcast: StateBroadcast): Promise<void> {
    return update(ref(this.db, roomPath(code, '/state')), stateUpdate(broadcast, this.uid, sentinels()))
  }

  reportPosition(code: string, positionMs: number): Promise<void> {
    return update(ref(this.db, roomPath(code, `/members/${this.uid}`)), positionUpdate(positionMs, sentinels()))
  }

  startCountdown(code: string, leadMs: number): Promise<void> {
    return set(ref(this.db, roomPath(code, '/countdown')), countdownValue(leadMs, this.uid, sentinels()))
  }

  setNowPlaying(code: string, nowPlaying: NowPlaying): Promise<void> {
    if (!ROOM_CODE.test(code)) return Promise.reject(new Error(`Not a room code: ${code}`))
    return update(ref(this.db, `previews/${code}`), nowPlayingUpdate(nowPlaying, sentinels()))
  }
}
