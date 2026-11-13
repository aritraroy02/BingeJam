// The app's end of the extension link: a named-pipe server the native hosts connect to (one host per
// browser that has BingeJam open). It
//   - forwards BridgeMessages (hello, snapshot, tabClosed) to the renderer, renaming tab ids so two
//     browsers' tabs never collide (the engine only ever sees the app's ids);
//   - routes the renderer's commands to the right browser/tab and broadcasts roomInfo;
//   - keeps the status the UI shows (linked browsers, their tabs, popup toggles, browsers seen before).
import { app } from 'electron'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer, type Server, type Socket } from 'node:net'
import { join } from 'node:path'
import type { BridgeMessage, PlayerCommandMessage } from '@shared/player'
import {
  DEFAULT_EXTENSION_SETTINGS,
  isNavigableUrl,
  type OverlayInput,
  type OverlayState,
  type BrowserId,
  type ExtensionConnection,
  type ExtensionSettings,
  type ExtensionStatus,
  type LinkControl,
  type LinkMessage,
  type LinkTab
} from '@shared/extension'
import { lineReader, pipePath, toLine } from './pipe'

interface Conn {
  id: number
  socket: Socket
  hello: boolean
  browser: string
  browserId: BrowserId | null
  extensionVersion: string
  installType: string | null
  build: string | null
  tabs: LinkTab[] // app tab ids
  activeTabId: number | null // app tab id
}

type Room = Extract<BridgeMessage, { kind: 'roomInfo' }>['room']

export interface LinkEvents {
  message(msg: BridgeMessage): void
  status(): void
  openApp(): void
  /** Typed into the overlay over the video. */
  overlayInput(input: OverlayInput): void
  /** A browser linked; lets the owner decide whether it needs link.reload. */
  linked(conn: { id: number; browserId: BrowserId | null; installType: string | null; build: string | null }): void
}

export class ExtensionLinkServer {
  private server: Server | null = null
  private conns = new Map<number, Conn>()
  private nextConnId = 1
  private nextTabId = 1
  /** "<connId>:<browserTabId>" ↔ app tab id */
  private toApp = new Map<string, number>()
  private toBrowser = new Map<number, { conn: number; tabId: number }>()
  private room: Room = null
  private overlay: OverlayState | null = null
  private stateFile = join(app.getPath('userData'), 'extension-link.json')
  private saved: { settings: ExtensionSettings; seen: ExtensionStatus['seen'] } = { settings: DEFAULT_EXTENSION_SETTINGS, seen: [] }
  listening = false
  error: string | null = null

  constructor(private events: LinkEvents) {
    try {
      if (existsSync(this.stateFile)) {
        const s = JSON.parse(readFileSync(this.stateFile, 'utf8')) as Partial<typeof this.saved>
        this.saved = { settings: { ...DEFAULT_EXTENSION_SETTINGS, ...s.settings }, seen: Array.isArray(s.seen) ? s.seen : [] }
      }
    } catch {
      /* start fresh */
    }
  }

  start(): void {
    const server = createServer((socket) => this.accept(socket))
    server.on('error', (e: NodeJS.ErrnoException) => {
      this.listening = false
      // EADDRINUSE: someone else holds our pipe name (another user's process, or a stuck instance).
      this.error = `Can't listen for the browser extension (${e.code ?? e.message})`
      console.error('[extension]', this.error)
      this.events.status()
    })
    server.listen(pipePath(), () => {
      this.listening = true
      this.error = null
      this.events.status()
    })
    this.server = server
  }

  stop(): void {
    for (const c of this.conns.values()) c.socket.destroy()
    this.server?.close()
    this.server = null
  }

  // ---------- connections ----------
  private accept(socket: Socket): void {
    const conn: Conn = { id: this.nextConnId++, socket, hello: false, browser: 'Browser', browserId: null, extensionVersion: '', installType: null, build: null, tabs: [], activeTabId: null }
    this.conns.set(conn.id, conn)
    socket.on('data', lineReader((msg) => this.onLine(conn, msg), () => socket.destroy()))
    socket.on('error', () => {
      /* close follows */
    })
    socket.on('close', () => this.drop(conn))
  }

  private drop(conn: Conn): void {
    if (!this.conns.delete(conn.id)) return
    for (const [key, appId] of [...this.toApp]) {
      if (!key.startsWith(`${conn.id}:`)) continue
      this.toApp.delete(key)
      this.toBrowser.delete(appId)
      this.events.message({ kind: 'tabClosed', tabId: appId })
    }
    this.events.status()
  }

  private appTabId(conn: Conn, browserTabId: number): number {
    const key = `${conn.id}:${browserTabId}`
    let id = this.toApp.get(key)
    if (id === undefined) {
      id = this.nextTabId++
      this.toApp.set(key, id)
      this.toBrowser.set(id, { conn: conn.id, tabId: browserTabId })
    }
    return id
  }

  private send(conn: Conn, msg: LinkMessage): void {
    if (!conn.socket.destroyed) conn.socket.write(toLine(msg))
  }

  private onLine(conn: Conn, raw: unknown): void {
    if (!raw || typeof raw !== 'object' || typeof (raw as { kind?: unknown }).kind !== 'string') return
    const msg = raw as LinkMessage
    switch (msg.kind) {
      case 'hello': {
        conn.hello = true
        conn.browser = String(msg.browser).slice(0, 60)
        conn.extensionVersion = String(msg.extensionVersion).slice(0, 30)
        this.events.message({ kind: 'hello', browser: conn.browser, extensionVersion: conn.extensionVersion })
        if (this.room !== null) this.send(conn, { kind: 'roomInfo', room: this.room })
        if (this.overlay !== null) this.send(conn, { kind: 'link.overlay', overlay: this.overlay })
        this.remember(conn)
        this.events.status()
        break
      }
      case 'link.ext':
        conn.browserId = msg.browserId ?? null
        conn.installType = msg.installType ?? null
        conn.build = msg.build ?? null
        this.remember(conn)
        this.events.linked({ id: conn.id, browserId: conn.browserId, installType: conn.installType, build: conn.build })
        this.events.status()
        break
      case 'snapshot': {
        const s = msg.snapshot
        if (!s || typeof s.tabId !== 'number' || typeof s.positionMs !== 'number') return
        this.events.message({ kind: 'snapshot', snapshot: { ...s, tabId: this.appTabId(conn, s.tabId) }, lastCommandId: msg.lastCommandId ?? null })
        break
      }
      case 'tabClosed': {
        const key = `${conn.id}:${msg.tabId}`
        const appId = this.toApp.get(key)
        if (appId === undefined) return
        this.toApp.delete(key)
        this.toBrowser.delete(appId)
        conn.tabs = conn.tabs.filter((t) => t.tabId !== appId)
        this.events.message({ kind: 'tabClosed', tabId: appId })
        this.events.status()
        break
      }
      case 'link.tabs':
        if (!Array.isArray(msg.tabs)) return
        conn.tabs = msg.tabs.map((t) => ({ ...t, tabId: this.appTabId(conn, t.tabId) }))
        conn.activeTabId = typeof msg.activeTabId === 'number' ? this.appTabId(conn, msg.activeTabId) : null
        this.events.status()
        break
      case 'link.settings':
        if (!msg.settings) return
        this.saved.settings = { ...DEFAULT_EXTENSION_SETTINGS, ...msg.settings }
        this.persist()
        // Keep every linked browser's popup showing the same toggles.
        for (const other of this.conns.values()) if (other !== conn && other.hello) this.send(other, { kind: 'link.settings', settings: this.saved.settings })
        this.events.status()
        break
      case 'link.openApp':
        this.events.openApp()
        break
      case 'link.overlaySend':
        if (typeof msg.text === 'string' && msg.text.trim()) this.events.overlayInput({ kind: 'send', text: msg.text.slice(0, 500) })
        break
      case 'link.overlayReact':
        if (typeof msg.emoji === 'string' && msg.emoji.length <= 16) this.events.overlayInput({ kind: 'react', emoji: msg.emoji })
        break
    }
  }

  private remember(conn: Conn): void {
    // One entry per browser name (hello comes before link.ext fills in browserId).
    const seen = this.saved.seen.filter((s) => s.browser !== conn.browser)
    seen.push({ browser: conn.browser, browserId: conn.browserId, extensionVersion: conn.extensionVersion, lastSeen: Date.now() })
    this.saved.seen = seen.slice(-10)
    this.persist()
  }

  private persistTimer: NodeJS.Timeout | null = null
  private persist(): void {
    if (this.persistTimer) return
    this.persistTimer = setTimeout(() => {
      this.persistTimer = null
      try {
        writeFileSync(this.stateFile, JSON.stringify(this.saved, null, 2))
      } catch (e) {
        console.error('[extension] could not save state', e)
      }
    }, 300)
  }

  // ---------- from the renderer ----------
  /** command → one tab; roomInfo → every linked browser. Returns whether anything received it. */
  sendFromApp(msg: BridgeMessage): boolean {
    if (msg.kind === 'command') return this.command(msg.message)
    if (msg.kind === 'roomInfo') {
      this.room = msg.room ?? null
      let sent = false
      for (const c of this.conns.values()) {
        if (!c.hello) continue
        this.send(c, { kind: 'roomInfo', room: this.room })
        sent = true
      }
      return sent
    }
    return false
  }

  private command(m: PlayerCommandMessage): boolean {
    const target = typeof m?.tabId === 'number' ? this.toBrowser.get(m.tabId) : undefined
    const conn = target && this.conns.get(target.conn)
    if (!target || !conn) {
      if (typeof m?.tabId === 'number') this.events.message({ kind: 'tabClosed', tabId: m.tabId })
      return false
    }
    this.send(conn, { kind: 'command', message: { ...m, tabId: target.tabId } })
    return true
  }

  /** Room chat + reactions over the video, in every linked browser (null hides it). */
  setOverlay(overlay: OverlayState | null): void {
    this.overlay = overlay
    for (const c of this.conns.values()) if (c.hello) this.send(c, { kind: 'link.overlay', overlay })
  }

  /** Opens a service's watch page in one of the app's tabs. */
  navigate(appTabId: number, url: string): boolean {
    if (!isNavigableUrl(url)) return false
    const target = this.toBrowser.get(appTabId)
    const conn = target && this.conns.get(target.conn)
    if (!target || !conn) return false
    this.send(conn, { kind: 'link.navigate', tabId: target.tabId, url })
    return true
  }

  sendControl(connId: number, msg: LinkControl): void {
    const c = this.conns.get(connId)
    if (c) this.send(c, msg)
  }

  // ---------- status ----------
  connections(): (ExtensionConnection & { id: number })[] {
    return [...this.conns.values()]
      .filter((c) => c.hello)
      .map((c) => ({ id: c.id, browser: c.browser, browserId: c.browserId, extensionVersion: c.extensionVersion, installType: c.installType, tabs: c.tabs, activeTabId: c.activeTabId }))
  }

  seen(): ExtensionStatus['seen'] {
    return this.saved.seen
  }

  settings(): ExtensionSettings {
    return this.saved.settings
  }
}
