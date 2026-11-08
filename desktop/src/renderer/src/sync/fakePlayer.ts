// A simulated browser with video tabs, standing in for the extension bridge (tests, demos).
// Each tab is an idealised HTML5 video: frame-accurate seeks, playbackRate, buffering on demand.
// Time comes from the injected local clock; nothing here reads the wall clock.

import type { PlayerCommand, PlayerCommandMessage, PlayerSnapshot, ServiceId } from '../../../shared/player.ts'
import type { Player, PlayerEvent, Timers, Unsubscribe } from '../../../shared/sync/transport.ts'

export interface FakeTabOptions {
  tabId: number
  service?: ServiceId
  title?: string | null
  subtitle?: string | null
  durationMs?: number | null
  positionMs?: number
  playing?: boolean
  /** The page ignores playbackRate (stays 1x). */
  ignoreRate?: boolean
}

export class FakeTab {
  readonly tabId: number
  service: ServiceId
  title: string | null
  subtitle: string | null
  durationMs: number | null
  ignoreRate: boolean
  buffering = false
  rate = 1
  private playingFlag: boolean
  /** position = basePos + (now − baseAt) × rate while playing and not buffering. */
  private basePos: number
  private baseAt: number
  private readonly browser: FakePlayer
  lastCommandId: string | null = null
  readonly received: PlayerCommand[] = []

  constructor(browser: FakePlayer, options: FakeTabOptions) {
    this.browser = browser
    this.tabId = options.tabId
    this.service = options.service ?? 'NETFLIX'
    this.title = options.title ?? null
    this.subtitle = options.subtitle ?? null
    this.durationMs = options.durationMs ?? null
    this.ignoreRate = options.ignoreRate ?? false
    this.playingFlag = options.playing ?? false
    this.basePos = options.positionMs ?? 0
    this.baseAt = browser.now()
  }

  get playing(): boolean {
    return this.playingFlag
  }

  position(): number {
    const now = this.browser.now()
    if (!this.playingFlag || this.buffering) return this.basePos
    return this.basePos + (now - this.baseAt) * this.rate
  }

  private rebase(): void {
    this.basePos = this.position()
    this.baseAt = this.browser.now()
  }

  snapshot(): PlayerSnapshot {
    return {
      tabId: this.tabId,
      service: this.service,
      title: this.title,
      subtitle: this.subtitle,
      durationMs: this.durationMs,
      positionMs: this.position(),
      playing: this.playingFlag,
      buffering: this.buffering,
      playbackRate: this.rate,
      sampledAt: this.browser.now(),
      ageMs: 0
    }
  }

  emit(): void {
    this.browser.emit({ kind: 'snapshot', snapshot: this.snapshot(), lastCommandId: this.lastCommandId })
  }

  /** A command from the app (through the extension). */
  apply(message: PlayerCommandMessage): void {
    const command = message.command
    this.received.push(command)
    this.lastCommandId = message.commandId
    this.rebase()
    switch (command.type) {
      case 'play':
        this.playingFlag = true
        break
      case 'pause':
        this.playingFlag = false
        break
      case 'seek':
        this.basePos = command.positionMs
        break
      case 'rate':
        if (!this.ignoreRate) this.rate = command.playbackRate
        break
    }
    this.emit()
  }

  // ---- What a person on the page does ----
  userPlay(): void {
    this.rebase()
    this.playingFlag = true
    this.emit()
  }
  userPause(): void {
    this.rebase()
    this.playingFlag = false
    this.emit()
  }
  userSeek(positionMs: number): void {
    this.rebase()
    this.basePos = positionMs
    this.emit()
  }
  setBuffering(buffering: boolean): void {
    this.rebase()
    this.buffering = buffering
    this.emit()
  }
  /** Next episode / another title in the same tab, starting at `positionMs`. */
  changeItem(title: string | null, subtitle: string | null, durationMs: number | null, positionMs = 0): void {
    this.rebase()
    this.title = title
    this.subtitle = subtitle
    this.durationMs = durationMs
    this.basePos = positionMs
    this.emit()
  }
  /** Moves the picture without an event (decoder drift), seen on the next snapshot. */
  drift(deltaMs: number): void {
    this.rebase()
    this.basePos += deltaMs
  }
  close(): void {
    this.browser.closeTab(this.tabId)
  }
}

export interface FakePlayerOptions {
  /** With timers: every open tab sends a snapshot this often, like the extension's heartbeat. */
  heartbeatMs?: number
  timers?: Timers
}

export class FakePlayer implements Player {
  readonly now: () => number
  readonly tabs = new Map<number, FakeTab>()
  readonly sent: PlayerCommandMessage[] = []
  private readonly listeners = new Set<(event: PlayerEvent) => void>()

  constructor(now: () => number, options: FakePlayerOptions = {}) {
    this.now = now
    if (options.heartbeatMs !== undefined && options.timers !== undefined) {
      options.timers.setInterval(() => {
        for (const tab of this.tabs.values()) tab.emit()
      }, options.heartbeatMs)
    }
  }

  openTab(options: FakeTabOptions): FakeTab {
    const tab = new FakeTab(this, options)
    this.tabs.set(tab.tabId, tab)
    tab.emit()
    return tab
  }

  closeTab(tabId: number): void {
    if (this.tabs.delete(tabId)) this.emit({ kind: 'tabClosed', tabId })
  }

  send(message: PlayerCommandMessage): void {
    this.sent.push(message)
    this.tabs.get(message.tabId)?.apply(message)
  }

  subscribe(listener: (event: PlayerEvent) => void): Unsubscribe {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  emit(event: PlayerEvent): void {
    for (const listener of [...this.listeners]) listener(event)
  }
}
