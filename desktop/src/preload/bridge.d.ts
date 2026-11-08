// Shape of `window.bingejam` (exposed by src/preload/index.ts). Shared by preload and renderer.
export interface BingeJamBridge {
  platform: string
  version(): Promise<string>
  /** Opens an https:// or mailto: link in the user's browser / mail app. */
  openExternal(url: string): Promise<void>
  /** bingejam://… links that arrive while the app is running. Returns an unsubscribe function. */
  onDeepLink(listener: (link: string) => void): () => void
}

declare global {
  interface Window {
    bingejam: BingeJamBridge
  }
}
