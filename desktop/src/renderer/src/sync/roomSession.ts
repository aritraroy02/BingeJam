// Wires the player (browser tabs via the extension), the room transport and a SyncEngine together
// for one room: the desktop's RoomSyncCoordinator (media/.../service/RoomSyncCoordinator.kt), the
// watch-party parts. Pure TypeScript (no Firebase, no DOM), so it runs under `node --test`.
//
// Every input (a tab's snapshot, a closed tab, the room, the preview, the 1 s tick) goes through
// one queue and then evaluate(), which picks the tab, decides what the engine may see, feeds it
// and publishes the state for the room screen. The queue keeps the engine single-threaded in
// effect: Firebase raises our own writes synchronously and a player may answer a command at once,
// and those must be handled after the current input, as on Android's main thread.
//
//  - Host: always visible. A new item (another service, title or episode) → onItemChanged(true)
//    before its first reading; what's playing is mirrored into previews/{code}.
//  - Member: the host's item changed (preview) → onItemChanged(false). A tab on another service, or
//    clearly another title, is hidden from the engine ('otherTitle'); switching to the right one
//    looks like opening it, which the engine handles as a late join.
// Not ported (no browser equivalent yet): JamFollower / JumpHold (starting the host's track in a
// music app), the room-kind lock between watch parties and jams.
//
// Timing: local = the injected localNow (performance.now()); room = transport.serverClock.

import type { PlayerCommand, PlayerSnapshot, ServiceId } from '../../../shared/player.ts'
import { toLocalSnapshot, BROWSER_VIDEO_CAPABILITIES } from '../../../shared/sync/clock.ts'
import { DEFAULT_SYNC_CONFIG, type SyncConfig } from '../../../shared/sync/config.ts'
import { SyncEngine, sameSnapshot } from '../../../shared/sync/engine.ts'
import { localLivePositionMs, memberExpectedPositionMs, roomExpectedPositionMs } from '../../../shared/sync/positionMath.ts'
import type { Player, PlayerEvent, RoomTransport, Timers, Unsubscribe } from '../../../shared/sync/transport.ts'
import {
  WATCH_PARTY_SERVICES,
  countdownPlayAt,
  type EngineOutput,
  type LocalSnapshot,
  type NowPlaying,
  type PlayerCapabilities,
  type Room,
  type RoomPreview,
  type SyncMode,
  type SyncStatus
} from '../../../shared/sync/types.ts'

/** Same strings as the room screen's SyncHealth (ui/models.ts). */
export type MemberSyncHealth = 'inSync' | 'catchingUp' | 'buffering' | 'noPlayer'

export interface MemberSync {
  uid: string
  name: string
  online: boolean
  isMe: boolean
  isHost: boolean
  /**
   * Their position minus the room's expected position, ms (> 0: ahead). Others: from their last
   * report (members/{uid}/positionMs, every 5 s), extrapolated. Null when unknown.
   */
  offsetMs: number | null
  health: MemberSyncHealth
}

export interface RoomSyncState {
  code: string | null
  status: SyncStatus
  mode: SyncMode
  isHost: boolean
  /** The room's play state; false until someone has played. */
  playing: boolean
  /** Someone has written rooms/{code}/state. */
  started: boolean
  /** This PC's player position when attached, else the room's expected position; null = nothing yet. */
  positionMs: number | null
  /** Local time (localNow base) positionMs refers to: extrapolate with (now − positionAt) while playing. */
  positionAt: number
  durationMs: number | null
  service: ServiceId | null
  title: string | null
  subtitle: string | null
  /** The tab being synced. */
  tabId: number | null
  /** Our offset from the room at the last drift check (> 0: ahead). */
  driftMs: number | null
  /** A 0.98x / 1.02x nudge is running. */
  nudging: boolean
  members: MemberSync[]
  allInSync: boolean
  /** "All in sync · ±0.2 s", "Kabir is 2.1 s behind", "2 catching up"... */
  syncLabel: string
  /** Manual "3, 2, 1, play": server time to press play at (compare with serverNowMs()). */
  countdown: { playAtServerMs: number; startedBy: string } | null
  lastError: string | null
}

export interface RoomSessionOptions {
  transport: RoomTransport
  player: Player
  /** Local monotonic clock: () => performance.now(). Never Date.now(). */
  localNow: () => number
  config?: SyncConfig
  timers?: Timers
  tickMs?: number
  /**
   * Added to seeks while the room plays (Android aims Netflix's app 1 s ahead because it reports
   * PLAYING before the picture moves). Browser players report buffering until frames play, so the
   * default is 0 everywhere; kept as a hook in case a site measures otherwise.
   */
  seekLeadMs?: (service: ServiceId) => number
  /** What a tab's player can do; the extension doesn't report it yet (HTML5 video: everything). */
  capabilitiesFor?: (service: ServiceId) => PlayerCapabilities
}

const TICK_MS = 1_000
/** Later updates of the same item (title text settling) are debounced; a new item goes out at once. */
const NOW_PLAYING_DEBOUNCE_MS = 1_500
/** RoomPreview.matches: lengths within this are the same title. */
const DURATION_TOLERANCE_MS = 10_000

interface TabEntry {
  raw: PlayerSnapshot
  local: LocalSnapshot
  /** Local time this tab last went from not playing to playing (the user started it). */
  startedPlayingAt: number | null
  seenAt: number
}

function idleState(): RoomSyncState {
  return {
    code: null, status: 'idle', mode: 'manualCountdown', isHost: false, playing: false, started: false,
    positionMs: null, positionAt: 0, durationMs: null, service: null, title: null, subtitle: null, tabId: null,
    driftMs: null, nudging: false, members: [], allInSync: false, syncLabel: '', countdown: null, lastError: null
  }
}

export class RoomSession {
  private readonly transport: RoomTransport
  private readonly player: Player
  private readonly localNow: () => number
  private readonly config: SyncConfig
  private readonly timers: Timers
  private readonly tickMs: number
  private readonly seekLeadMs: (service: ServiceId) => number
  private readonly capabilitiesFor: (service: ServiceId) => PlayerCapabilities

  private code: string | null = null
  private engine: SyncEngine | null = null
  private room: Room | null = null
  private preview: RoomPreview | null = null
  private readonly tabs = new Map<number, TabEntry>()
  /** The tab the engine currently sees (null = none, or hidden). Commands go to it only. */
  private fed: TabEntry | null = null
  private fedEngine = false
  private followedTabId: number | null = null
  private statusOverride: SyncStatus | null = null
  private readonly hostItem = new ItemTracker()
  private readonly previewItem = new ItemTracker()
  private hostItemGeneration = 0
  private subscriptions: Unsubscribe[] = []
  private tickHandle: unknown = null
  private commandCounter = 0
  private readonly sessionId = Math.random().toString(36).slice(2, 8)
  private lastError: string | null = null

  // Now playing (host)
  private nowPlayingKey: string | null = null
  private lastPublishedItem = -1
  private nowPlayingTimer: unknown = null

  private queue: Array<() => void> = []
  private draining = false

  private _state: RoomSyncState = idleState()
  private stateKey = ''
  private readonly listeners = new Set<(state: RoomSyncState) => void>()

  constructor(options: RoomSessionOptions) {
    this.transport = options.transport
    this.player = options.player
    this.localNow = options.localNow
    this.config = options.config ?? DEFAULT_SYNC_CONFIG
    this.timers = options.timers ?? {
      setInterval: (fn, ms) => globalThis.setInterval(fn, ms),
      clearInterval: (h) => globalThis.clearInterval(h as ReturnType<typeof setInterval>),
      setTimeout: (fn, ms) => globalThis.setTimeout(fn, ms),
      clearTimeout: (h) => globalThis.clearTimeout(h as ReturnType<typeof setTimeout>)
    }
    this.tickMs = options.tickMs ?? TICK_MS
    this.seekLeadMs = options.seekLeadMs ?? (() => 0)
    this.capabilitiesFor = options.capabilitiesFor ?? (() => BROWSER_VIDEO_CAPABILITIES)
  }

  get state(): RoomSyncState {
    return this._state
  }

  /** Called with every new state (and once right away). Returns an unsubscribe function. */
  subscribe(listener: (state: RoomSyncState) => void): Unsubscribe {
    this.listeners.add(listener)
    listener(this._state)
    return () => this.listeners.delete(listener)
  }

  /** Starts syncing `code`. Call it once we're a member (rooms/{code}/members/{uid} written). */
  start(code: string): void {
    if (code === this.code && this.engine !== null) return
    this.stop()
    this.code = code
    const transport = this.transport
    this.engine = new SyncEngine(
      transport.uid,
      {
        // Local monotonic clock; same base as LocalSnapshot.sampledAtMs (see clock.ts).
        localNowMs: () => this.localNow(),
        // Server time from the transport (Firebase offset anchored to the monotonic clock).
        serverNowMs: () => transport.serverClock.serverNowMs()
      },
      this.config
    )
    this.publish()
    this.subscriptions = [
      this.player.subscribe((event) => this.run(() => this.onPlayerEvent(event))),
      transport.observeRoom(code, (room) => this.run(() => this.onRoomUpdate(room))),
      transport.observePreview(code, (preview) => this.run(() => this.onPreview(preview)))
    ]
    this.tickHandle = this.timers.setInterval(() => this.tick(), this.tickMs)
  }

  stop(): void {
    for (const unsubscribe of this.subscriptions) unsubscribe()
    this.subscriptions = []
    if (this.tickHandle !== null) this.timers.clearInterval(this.tickHandle)
    this.tickHandle = null
    if (this.nowPlayingTimer !== null) this.timers.clearTimeout(this.nowPlayingTimer)
    this.nowPlayingTimer = null
    // Leave the tab at normal speed if we were nudging it.
    if (this.engine?.rateNudge && this.fed) this.send(this.fed.raw.tabId, { type: 'rate', playbackRate: 1 })
    this.code = null
    this.engine = null
    this.room = null
    this.preview = null
    this.tabs.clear()
    this.fed = null
    this.fedEngine = false
    this.followedTabId = null
    this.statusOverride = null
    this.hostItem.reset()
    this.previewItem.reset()
    this.nowPlayingKey = null
    this.lastPublishedItem = -1
    this.queue = []
    this.lastError = null
    this.publish()
  }

  /** About once a second: engine timers. Public for tests. */
  tick(): void {
    this.run(() => {
      const engine = this.engine
      if (engine === null) return
      this.execute(engine.onTick())
      this.evaluate()
    })
  }

  /** Manual "3, 2, 1, play" for everyone (when the player can't be controlled). */
  startCountdown(leadMs = 3_000): Promise<void> {
    const code = this.code
    if (code === null) return Promise.resolve()
    return this.transport.startCountdown(code, leadMs).catch((e: unknown) => this.fail('startCountdown', e))
  }

  // ---------------------------------------------------------------------------------------------
  // Inputs
  // ---------------------------------------------------------------------------------------------

  private run(fn: () => void): void {
    this.queue.push(fn)
    if (this.draining) return
    this.draining = true
    try {
      while (this.queue.length > 0) {
        const next = this.queue.shift() as () => void
        try {
          next()
        } catch (e) {
          this.lastError = `sync: ${e instanceof Error ? e.message : String(e)}`
        }
      }
    } finally {
      this.draining = false
    }
  }

  private onPlayerEvent(event: PlayerEvent): void {
    if (this.engine === null) return
    if (event.kind === 'tabClosed') {
      this.tabs.delete(event.tabId)
    } else {
      const raw = event.snapshot
      const now = this.localNow()
      const previous = this.tabs.get(raw.tabId)
      const local = toLocalSnapshot(raw, now, this.capabilitiesOf(raw))
      const nowPlaying = raw.playing && !raw.buffering
      const wasPlaying = previous !== undefined && previous.raw.playing && !previous.raw.buffering
      this.tabs.set(raw.tabId, {
        raw,
        local,
        startedPlayingAt: nowPlaying && !wasPlaying ? now : (previous?.startedPlayingAt ?? null),
        seenAt: now
      })
    }
    this.evaluate()
  }

  private onRoomUpdate(room: Room | null): void {
    const engine = this.engine
    if (engine === null) return
    this.room = room
    if (room !== null) this.execute(engine.onRoom(room.state, room.members))
    this.evaluate()
  }

  private onPreview(preview: RoomPreview | null): void {
    if (this.engine === null) return
    this.preview = preview
    this.evaluate()
  }

  // ---------------------------------------------------------------------------------------------
  // Evaluate
  // ---------------------------------------------------------------------------------------------

  private evaluate(): void {
    const engine = this.engine
    if (engine === null) return
    const room = this.room
    const preview = this.preview
    const isHost = room !== null && room.hostUid === this.transport.uid
    const picked = this.pick(room, preview, isHost)
    let visible = picked
    let override: SyncStatus | null = null
    let nowPlaying: (NowPlaying & { item: number }) | null = null

    if (room !== null && isHost) {
      this.previewItem.reset()
      if (picked !== null) {
        // A new item (service, title or episode): before feeding its first reading, so the engine
        // treats the switch as the new item's start and broadcasts our state for it >= 800 ms
        // later, after the immediate preview write below.
        if (this.hostItem.changed(picked.raw.service, picked.raw.title, picked.raw.subtitle)) {
          this.hostItemGeneration++
          this.execute(engine.onItemChanged(true))
        }
        if (picked.raw.title !== null && picked.raw.title.trim() !== '') {
          nowPlaying = {
            service: picked.raw.service,
            title: picked.raw.title,
            subtitle: picked.raw.subtitle,
            durationMs: picked.raw.durationMs,
            item: this.hostItemGeneration
          }
        }
      }
    } else {
      this.hostItem.reset()
      // The host moved to another title/episode/service: room writes from now on are about it.
      if (room !== null && preview !== null && preview.service !== null && this.previewItem.changed(preview.service, preview.title, preview.subtitle)) {
        this.execute(engine.onItemChanged(false))
      }
      if (!isHost && isOtherTitle(picked, preview)) {
        visible = null
        override = 'otherTitle'
      }
    }

    this.statusOverride = override
    this.feed(visible)
    if (nowPlaying !== null) this.publishNowPlaying(nowPlaying)
    this.publish()
  }

  /**
   * Which tab the room syncs (a simple SessionSelector): the room title's service, else (members)
   * the preview's, else any watch-party service. Stays on the followed tab unless another one was
   * started more recently while it isn't playing.
   */
  private pick(room: Room | null, preview: RoomPreview | null, isHost: boolean): TabEntry | null {
    const target = room?.title?.service ?? (isHost ? null : (preview?.service ?? null))
    const candidates = [...this.tabs.values()].filter((t) =>
      target !== null ? t.raw.service === target : WATCH_PARTY_SERVICES.includes(t.raw.service)
    )
    if (candidates.length === 0) {
      this.followedTabId = null
      return null
    }
    const isPlaying = (t: TabEntry): boolean => t.raw.playing && !t.raw.buffering
    const followed = candidates.find((t) => t.raw.tabId === this.followedTabId) ?? null
    let chosen: TabEntry | null = null
    if (followed !== null && (isPlaying(followed) || followed.raw.buffering)) chosen = followed
    if (chosen === null) {
      const playing = candidates.filter(isPlaying).sort((a, b) => (b.startedPlayingAt ?? 0) - (a.startedPlayingAt ?? 0))
      chosen = playing[0] ?? followed ?? candidates.sort((a, b) => b.seenAt - a.seenAt)[0]
    }
    this.followedTabId = chosen.raw.tabId
    return chosen
  }

  /**
   * Gives the engine `visible`'s reading, but only when the tab or its reading changed.
   * Switching from one tab to another is a re-attach: the engine first hears the old one is gone,
   * so the new tab is lined up with the room instead of its position being taken as a jump.
   */
  private feed(visible: TabEntry | null): void {
    const engine = this.engine
    if (engine === null) return
    const previous = this.fed
    this.fed = visible
    const changed =
      !this.fedEngine || visible?.raw.tabId !== previous?.raw.tabId || !sameSnapshot(visible?.local ?? null, previous?.local ?? null)
    if (!changed) return
    this.fedEngine = true
    if (previous !== null && visible !== null && previous.raw.tabId !== visible.raw.tabId) {
      // Don't leave the old tab nudged.
      if (engine.rateNudge !== null) this.send(previous.raw.tabId, { type: 'rate', playbackRate: 1 })
      this.execute(engine.onLocalSnapshot(null))
    }
    this.execute(engine.onLocalSnapshot(visible?.local ?? null))
  }

  /** Applies commands to the synced tab and sends broadcasts/reports to the room. */
  private execute(output: EngineOutput): void {
    const code = this.code
    if (code === null) return
    const tab = this.fed
    if (tab !== null) {
      for (const command of output.commands) {
        if (command.type === 'seek' && this.room?.state?.status === 'playing') {
          this.send(tab.raw.tabId, { type: 'seek', positionMs: command.positionMs + this.seekLeadMs(tab.raw.service) })
        } else {
          this.send(tab.raw.tabId, command)
        }
      }
    }
    if (output.broadcast !== null) {
      this.transport.writeState(code, output.broadcast).catch((e: unknown) => this.fail('writeState', e))
    }
    if (output.positionReport !== null) {
      this.transport.reportPosition(code, output.positionReport).catch((e: unknown) => this.fail('reportPosition', e))
    }
  }

  /** The service's defaults, overridden by what the tab itself learned (Netflix: canSeek false). */
  private capabilitiesOf(raw: PlayerSnapshot): PlayerCapabilities {
    const base = this.capabilitiesFor(raw.service)
    return {
      playPause: base.playPause,
      seek: raw.canSeek ?? base.seek,
      rate: raw.canSetRate ?? base.rate
    }
  }

  private send(tabId: number, command: PlayerCommand): void {
    this.commandCounter++
    this.player.send({ tabId, command, commandId: `${this.sessionId}-${this.commandCounter}` })
  }

  /**
   * Host only: mirrors the synced tab's title into previews/{code} so members (phones too) see it
   * and can tell titles apart. A NEW item goes out at once, so members learn about it before the
   * engine's restart write (>= 800 ms later; one client's writes arrive in order).
   */
  private publishNowPlaying(np: NowPlaying & { item: number }): void {
    const key = JSON.stringify(np)
    if (key === this.nowPlayingKey) return
    this.nowPlayingKey = key
    if (this.nowPlayingTimer !== null) this.timers.clearTimeout(this.nowPlayingTimer)
    this.nowPlayingTimer = null
    const write = (): void => {
      this.nowPlayingTimer = null
      const code = this.code
      if (code === null) return
      this.lastPublishedItem = np.item
      const { item: _item, ...nowPlaying } = np
      this.transport.setNowPlaying(code, nowPlaying).catch((e: unknown) => this.fail('setNowPlaying', e))
    }
    if (np.item !== this.lastPublishedItem) write()
    else this.nowPlayingTimer = this.timers.setTimeout(write, NOW_PLAYING_DEBOUNCE_MS)
  }

  private fail(what: string, e: unknown): void {
    this.lastError = `${what} failed: ${e instanceof Error ? e.message : String(e)}`
    this.publish()
  }

  // ---------------------------------------------------------------------------------------------
  // State for the room screen
  // ---------------------------------------------------------------------------------------------

  private publish(): void {
    const next = this.computeState()
    const key = JSON.stringify(next)
    if (key === this.stateKey) return
    this.stateKey = key
    this._state = next
    for (const listener of this.listeners) listener(next)
  }

  private computeState(): RoomSyncState {
    const engine = this.engine
    if (engine === null || this.code === null) return idleState()
    const now = this.localNow()
    const serverNow = this.transport.serverClock.serverNowMs()
    const room = this.room
    const state = room?.state ?? null
    const tab = this.fed
    const roomExpected = state !== null ? roomExpectedPositionMs(state, serverNow) : null
    const timeline = engine.mode === 'full' || engine.mode === 'playPauseCatchUp'
    const localLive = tab !== null ? localLivePositionMs(tab.local, now) : -1

    // Our own offset, measured now: live local position vs the room's expected position.
    const myOffset = timeline && localLive >= 0 && roomExpected !== null ? localLive - roomExpected : null
    const members: MemberSync[] = (room?.members ?? []).map((m) => {
      const isMe = m.uid === this.transport.uid
      const isHost = m.uid === room?.hostUid
      if (isMe) {
        let health: MemberSyncHealth
        if (tab === null) health = 'noPlayer'
        else if (tab.raw.buffering) health = 'buffering'
        else if (myOffset !== null && Math.abs(myOffset) > this.config.driftThresholdMs) health = 'catchingUp'
        else health = 'inSync'
        return { uid: m.uid, name: m.name, online: m.online, isMe, isHost, offsetMs: myOffset, health }
      }
      if (!m.online) return { uid: m.uid, name: m.name, online: false, isMe, isHost, offsetMs: null, health: 'noPlayer' }
      const expected = memberExpectedPositionMs(m, state?.status ?? null, serverNow, this.config.memberReportMaxAgeMs)
      if (expected === null) return { uid: m.uid, name: m.name, online: true, isMe, isHost, offsetMs: null, health: 'noPlayer' }
      // A report from before the latest room write says nothing about the new timeline yet
      // (their engine is applying it); their next report (<= 5 s) will.
      if (state === null || roomExpected === null || (m.reportedAt ?? 0) < state.updatedAt) {
        return { uid: m.uid, name: m.name, online: true, isMe, isHost, offsetMs: null, health: 'inSync' }
      }
      const offsetMs = expected - roomExpected
      const health: MemberSyncHealth = Math.abs(offsetMs) > this.config.driftThresholdMs ? 'catchingUp' : 'inSync'
      return { uid: m.uid, name: m.name, online: true, isMe, isHost, offsetMs, health }
    })

    const summary = summarizeSync(members, state !== null)
    const status: SyncStatus = room === null ? 'waitingForRoom' : (this.statusOverride ?? engine.status)
    return {
      code: this.code,
      status,
      mode: engine.mode,
      isHost: room !== null && room.hostUid === this.transport.uid,
      playing: state?.status === 'playing',
      started: state !== null,
      positionMs: localLive >= 0 ? localLive : roomExpected,
      positionAt: now,
      durationMs: tab?.raw.durationMs ?? this.preview?.durationMs ?? null,
      service: tab?.raw.service ?? room?.title?.service ?? this.preview?.service ?? null,
      title: tab?.raw.title ?? this.preview?.title ?? null,
      subtitle: tab?.raw.subtitle ?? this.preview?.subtitle ?? null,
      tabId: tab?.raw.tabId ?? null,
      driftMs: engine.lastDriftMs,
      nudging: engine.rateNudge !== null,
      members,
      allInSync: summary.allInSync,
      syncLabel: summary.label,
      countdown: room?.countdown ? { playAtServerMs: countdownPlayAt(room.countdown), startedBy: room.countdown.startedBy } : null,
      lastError: this.lastError
    }
  }
}

// -----------------------------------------------------------------------------------------------
// Helpers (exported for tests)
// -----------------------------------------------------------------------------------------------

function normalized(s: string | null | undefined): string | null {
  const n = s?.trim().toLowerCase().replace(/\s+/g, ' ') ?? ''
  return n === '' ? null : n
}

/**
 * Detects a change from one known item to another (port of ItemKeyTracker, by title). The first
 * item seen isn't a change (that's opening the player: a late join); readings without a title are
 * ignored; another service is always a new item. Desktop addition: a subtitle that changes from
 * one value to another (S1:E1 → S1:E2 under the same show title) is a new item too, while a
 * subtitle appearing after the title is the same one.
 */
export class ItemTracker {
  private service: ServiceId | null = null
  private title: string | null = null
  private subtitle: string | null = null
  private known = false

  changed(service: ServiceId | null, title: string | null, subtitle: string | null): boolean {
    const t = normalized(title)
    const s = normalized(subtitle)
    if (this.known && service !== null && this.service !== null && service !== this.service) {
      this.service = service
      this.title = t
      this.subtitle = s
      return true
    }
    if (service !== null) this.service = service
    if (t === null) return false
    if (!this.known) {
      this.known = true
      this.title = t
      this.subtitle = s
      return false
    }
    if (t !== this.title) {
      this.title = t
      this.subtitle = s
      return true
    }
    if (s !== null && this.subtitle !== null && s !== this.subtitle) {
      this.subtitle = s
      return true
    }
    if (s !== null) this.subtitle = s
    return false
  }

  reset(): void {
    this.service = null
    this.title = null
    this.subtitle = null
    this.known = false
  }
}

/**
 * Member on something other than the host's title? Another service always is. Titles are compared
 * leniently because the host may be a phone, whose app words titles differently from the website:
 * only a length that differs by more than 10 s says "another title"; different wording with the
 * same (or unknown) length can't be told apart and doesn't block syncing.
 */
export function isOtherTitle(tab: { raw: PlayerSnapshot } | null, preview: RoomPreview | null): boolean {
  if (tab === null || preview === null || preview.service === null || normalized(preview.title) === null) return false
  if (tab.raw.service !== preview.service) return true
  const a = preview.durationMs ?? 0
  const b = tab.raw.durationMs ?? 0
  return a > 0 && b > 0 && Math.abs(a - b) > DURATION_TOLERANCE_MS
}

function seconds(ms: number): string {
  return Math.max(0.1, Math.round(Math.abs(ms) / 100) / 10).toFixed(1)
}

/** "All in sync · ±0.2 s" and friends, from the members' sync health. */
export function summarizeSync(members: readonly MemberSync[], started: boolean): { allInSync: boolean; label: string } {
  if (!started) return { allInSync: false, label: 'Waiting for someone to press play' }
  const out = members.filter((m) => m.health === 'catchingUp')
  const buffering = members.filter((m) => m.health === 'buffering')
  if (out.length === 0 && buffering.length === 0) {
    const offsets = members.filter((m) => m.offsetMs !== null).map((m) => Math.abs(m.offsetMs as number))
    return { allInSync: true, label: offsets.length > 0 ? `All in sync · ±${seconds(Math.max(...offsets))} s` : 'All in sync' }
  }
  if (out.length === 1 && buffering.length === 0) {
    const m = out[0]
    const way = (m.offsetMs ?? 0) > 0 ? 'ahead' : 'behind'
    return { allInSync: false, label: `${m.isMe ? 'You are' : `${m.name} is`} ${seconds(m.offsetMs ?? 0)} s ${way}` }
  }
  if (out.length === 0 && buffering.length === 1) {
    return { allInSync: false, label: `${buffering[0].isMe ? 'You are' : `${buffering[0].name} is`} buffering` }
  }
  return { allInSync: false, label: `${out.length + buffering.length} catching up` }
}
