// Screen share (Go Live in voice channels, 2026-11-22): what the main process offers to share.

export interface ScreenSource {
  /** desktopCapturer id: "screen:…" or "window:…". */
  id: string
  name: string
  kind: 'screen' | 'window'
  /** PNG data URL, 16:9 thumbnail. */
  thumbnail: string
  /** The app's icon (windows only), PNG data URL. */
  icon: string | null
  /**
   * A Netflix / Prime Video / JioHotstar window: their DRM makes a share black, and sharing them breaks
   * their terms (PLAN.md "Hard limit"), so the picker won't offer it. Movies are watched in a room.
   */
  blocked: boolean
}

const DRM_TITLES = /\b(netflix|prime video|primevideo|amazon prime|jiohotstar|hotstar|disney\+)\b/i

export function isDrmWindow(name: string): boolean {
  return DRM_TITLES.test(name)
}
