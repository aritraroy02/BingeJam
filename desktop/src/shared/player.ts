// The contract between the desktop app's room sync (src/shared/sync) and whatever plays the title
// on this PC: the browser extension's tab (Netflix, Prime Video, JioHotstar in a browser).
// Only play / pause / position travel; never video or audio.
//
// Path: extension content script ↔ extension service worker ↔ native messaging host ↔ desktop main
// process ↔ IPC ↔ renderer (engine). Messages are plain JSON so every hop can forward them as-is.

/** Same ids as the Android app's StreamingService (rooms/{code}/title/service, previews/{code}/service). */
export type ServiceId = 'NETFLIX' | 'JIOHOTSTAR' | 'PRIME_VIDEO' | 'SPOTIFY' | 'YOUTUBE_MUSIC' | 'AMAZON_MUSIC' | 'YOUTUBE'

export interface PlayerSnapshot {
  /** Which browser tab this came from (the extension's tab id), so the app can follow one tab. */
  tabId: number
  service: ServiceId
  /** What the page shows, best effort: "Midnight Signal", "S2:E4 Kaer Morhen". */
  title: string | null
  subtitle: string | null
  durationMs: number | null
  /** The video element's position when the snapshot was taken. */
  positionMs: number
  playing: boolean
  /** Waiting for data (HTMLMediaElement "waiting"/"stalled", readyState < 3). */
  buffering: boolean
  playbackRate: number
  /**
   * Local monotonic time (performance.now() in the extension's content script) when positionMs was
   * read. Each hop that measures its own delay may add it to `ageMs` instead of trusting clocks
   * across processes; the app converts to its own monotonic clock on receipt (see sync/clock.ts).
   */
  sampledAt: number
  /** Milliseconds the snapshot has already spent in transit, as measured by the hops. */
  ageMs: number
}

export type PlayerCommand =
  | { type: 'play' }
  | { type: 'pause' }
  /** Jump to an exact position (standard HTMLMediaElement currentTime). */
  | { type: 'seek'; positionMs: number }
  /** Small drift fixes without a jump: 0.98–1.02, then back to 1. */
  | { type: 'rate'; playbackRate: number }

export interface PlayerCommandMessage {
  tabId: number
  command: PlayerCommand
  /** Echo id: the extension returns it on the next snapshot so the engine can ignore its own echo. */
  commandId: string
}

/** Everything the app ↔ extension link carries. */
export type BridgeMessage =
  | { kind: 'hello'; extensionVersion: string; browser: string }
  | { kind: 'snapshot'; snapshot: PlayerSnapshot; lastCommandId: string | null }
  | { kind: 'tabClosed'; tabId: number }
  | { kind: 'command'; message: PlayerCommandMessage }
  | { kind: 'roomInfo'; room: { code: string; title: string | null; people: string; inSync: boolean; positionMs: number } | null }
