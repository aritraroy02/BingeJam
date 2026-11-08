// Shape of `window.bingejam` (exposed by src/preload/index.ts). Shared by preload and renderer.
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
}

declare global {
  interface Window {
    bingejam: BingeJamBridge
  }
}
