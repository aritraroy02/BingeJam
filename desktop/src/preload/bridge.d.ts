// Shape of `window.bingejam` (exposed by src/preload/index.ts). Shared by preload and renderer.
import type { BridgeMessage } from '@shared/player'
import type { AddToBrowserResult, BrowserId, DetectedBrowser, ExtensionStatus } from '@shared/extension'

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
  /** bingejam://… links that arrive while the app is running. Returns an unsubscribe function. */
  onDeepLink(listener: (link: string) => void): () => void
  /** The browser extension link (src/main/extension). Tab ids are the app's own, unique across browsers. */
  extension: {
    status(): Promise<ExtensionStatus>
    onStatus(listener: (status: ExtensionStatus) => void): () => void
    /** hello / snapshot / tabClosed from the extension (player.ts BridgeMessage). */
    onMessage(listener: (message: BridgeMessage) => void): () => void
    /** `command` goes to its tab, `roomInfo` to every linked browser. false = nobody received it. */
    send(message: BridgeMessage): Promise<boolean>
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
