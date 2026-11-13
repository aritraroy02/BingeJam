// Shape of `window.bingejam` (exposed by src/preload/index.ts). Shared by preload and renderer.
import type { BridgeMessage } from '@shared/player'
import type { AddToBrowserResult, BrowserId, DetectedBrowser, ExtensionStatus, OverlayInput, OverlayState } from '@shared/extension'
import type { DesktopPrefs, GlobalShortcutId, MiniAction, MiniRoomState, NotifyRequest } from '@shared/desktop'

export interface BingeJamBridge {
  platform: string
  version(): Promise<string>
  /** Opens an https:// or mailto: link in the user's browser / mail app. */
  openExternal(url: string): Promise<void>
  /**
   * Calls the push relay through the main process (the relay answers no CORS preflight). Only the
   * relay's own address is allowed; see src/main/relayProxy.ts.
   */
  relayFetch(request: {
    method: 'GET' | 'POST'
    url: string
    headers: Record<string, string>
    body?: string
  }): Promise<{ status: number; body: string }>
  /** Uploads (PUT, image bytes ≤ 1 MB) or removes (DELETE) our profile picture on the relay. */
  relayAvatar(request: { method: 'PUT' | 'DELETE'; token: string; bytes?: Uint8Array }): Promise<{ status: number; body: string }>
  /** bingejam://… links that arrive while the app is running. Returns an unsubscribe function. */
  onDeepLink(listener: (link: string) => void): () => void
  /** The game running on this PC (src/main/games.ts), for "Playing VALORANT". since = Date.now() at detection. */
  games: {
    current(): Promise<{ name: string; since: number } | null>
    onChange(listener: (game: { name: string; since: number } | null) => void): () => void
  }
  /** The bingejam:// link the app was started with (once; null afterwards). */
  takeDeepLink(): Promise<string | null>
  /** Tray, start with Windows, notifications, global shortcuts (src/main/desktop.ts). */
  desktop: {
    get(): Promise<{ prefs: DesktopPrefs; startWithWindows: boolean | null }>
    /** Returns the new state; null = not possible here (a dev build). */
    setStartWithWindows(on: boolean): Promise<boolean | null>
    setPrefs(prefs: Partial<DesktopPrefs>): Promise<DesktopPrefs>
    onPrefs(listener: (prefs: DesktopPrefs) => void): () => void
    notify(request: NotifyRequest): void
    /** Unread count on the tray tooltip and a dot on the taskbar button (0 clears). */
    setBadge(count: number): void
    onNotifyClick(listener: (action: string) => void): () => void
    onGlobalShortcut(listener: (id: GlobalShortcutId) => void): () => void
    setTheme(theme: 'dark' | 'light'): void
    /** Compact mode: 0.9; normal: 1. */
    setZoom(factor: number): void
  }
  /** The always-on-top mini room window (src/main/mini.ts). */
  mini: {
    /** Main window: open / close it. */
    open(on: boolean): void
    /** Main window: what it shows (null closes it). */
    state(state: MiniRoomState | null): void
    /** Main window: button presses from the mini window. */
    onAction(listener: (action: MiniAction) => void): () => void
    /** Main window: whether it's open. */
    onOpen(listener: (open: boolean) => void): () => void
    /** Mini window: send a button press. */
    action(action: MiniAction): void
    /** Mini window: the room's state. */
    onState(listener: (state: MiniRoomState) => void): () => void
  }
  /** The browser extension link (src/main/extension). Tab ids are the app's own, unique across browsers. */
  extension: {
    status(): Promise<ExtensionStatus>
    onStatus(listener: (status: ExtensionStatus) => void): () => void
    /** hello / snapshot / tabClosed from the extension (player.ts BridgeMessage). */
    onMessage(listener: (message: BridgeMessage) => void): () => void
    /** `command` goes to its tab, `roomInfo` to every linked browser. false = nobody received it. */
    send(message: BridgeMessage): Promise<boolean>
    /** Room chat + reactions over the video in every streaming tab (null hides it). */
    overlay(overlay: OverlayState | null): void
    /** Opens a service's watch page in the app's tab [tabId]; false if not possible. */
    navigate(tabId: number, url: string): Promise<boolean>
    onOverlayInput(listener: (input: OverlayInput) => void): () => void
  }
  /** Installed browsers and the "Add to browser" routes (PLAN.md). */
  browsers: {
    detect(): Promise<DetectedBrowser[]>
    addTo(browser: BrowserId): Promise<AddToBrowserResult>
  }
}

declare global {
  interface Window {
    bingejam: BingeJamBridge
  }
}
