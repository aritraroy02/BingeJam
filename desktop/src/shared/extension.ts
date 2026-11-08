// The app ↔ browser-extension link: ids, link-level messages and the status the UI reads.
// Player messages (snapshots, commands, roomInfo) are in player.ts; this file only adds the plumbing
// around them (which tabs exist, popup toggles, "open the app", reload after an update).
// Pure types and constants: used by the main process, the native host, the preload bridge and the UI.

import type { BridgeMessage, ServiceId } from './player'

/** Native messaging host name (registry key + host manifest "name"). Lowercase, dots only. */
export const NATIVE_HOST_NAME = 'com.bingejam.desktop'

/**
 * Chromium id of the extension when it's loaded unpacked (Chrome, Brave, Opera, Edge before its store
 * listing). Fixed by the public key in extension/src/manifest.json ("key"); extension/build.mjs checks
 * that the key still gives this id. There is no private key: it was never saved, and none is needed
 * for an unpacked extension.
 */
export const EXTENSION_ID_UNPACKED = 'bibkilijflgiepaaeghhmfbpoapaaaka'

/** Edge Add-ons gives the listed extension its own id. PLACEHOLDER: not published yet. */
export const EXTENSION_ID_EDGE_STORE: string | null = null

/** Firefox id (browser_specific_settings.gecko.id in the manifest). */
export const EXTENSION_ID_FIREFOX = 'bingejam@syncit-watchparty.web.app'

/**
 * Store routes. PLACEHOLDERS until the user publishes:
 * - edgeAddonsUrl: the listing, e.g. https://microsoftedge.microsoft.com/addons/detail/<product id>.
 *   While null, "Add to Edge" uses Load unpacked like Chrome.
 * - firefoxXpiUrl: the Mozilla-signed (unlisted) .xpi, as an https URL or a file the app ships.
 *   While null, "Add to Firefox" opens about:debugging for a temporary add-on (gone after a restart).
 */
export const EXTENSION_STORE: { edgeAddonsUrl: string | null; firefoxXpiUrl: string | null } = {
  edgeAddonsUrl: null,
  firefoxXpiUrl: null
}

export type BrowserId = 'edge' | 'firefox' | 'chrome' | 'brave' | 'opera'

/** The popup's toggles. The extension stores them; the app applies them in the room. */
export interface ExtensionSettings {
  waitForBuffering: boolean
  sameSubtitles: boolean
  followHostEpisodes: boolean
}

export const DEFAULT_EXTENSION_SETTINGS: ExtensionSettings = {
  waitForBuffering: true,
  sameSubtitles: true,
  followHostEpisodes: false
}

/**
 * Whether a direct `video.currentTime` seek works on this site. Netflix answers with error M7375;
 * once a seek fails the extension stops seeking there and moves with playbackRate instead.
 */
export type SeekSupport = 'unknown' | 'ok' | 'blocked'

/** Something the person has to do in the tab (the app shows it). */
export type TabProblem =
  /** The browser blocked play() until the person clicks the page once. */
  | { code: 'play-blocked' }
  /** Seeking is blocked here and the gap is too big for speed nudges: seek by hand to atMs. */
  | { code: 'seek-blocked'; atMs: number }
  /** The site's player crashed after a seek (Netflix M7375): reload the tab. */
  | { code: 'player-error' }

/** A streaming tab the extension sees. tabId is the app's own id once it reaches the renderer. */
export interface LinkTab {
  tabId: number
  service: ServiceId
  title: string | null
  subtitle: string | null
  playing: boolean
  seek: SeekSupport
  problem: TabProblem | null
}

/** Link-level messages around the player contract. Never reach the sync engine. */
export type LinkControl =
  /** ext → app, after hello: who is talking. build = extension/dist build id (changes on every rebuild). */
  | { kind: 'link.ext'; extensionId: string; installType: string | null; browserId: BrowserId | null; build: string | null }
  /** ext → app: the streaming tabs and which one the person is using. */
  | { kind: 'link.tabs'; tabs: LinkTab[]; activeTabId: number | null }
  /** both ways: popup toggles. */
  | { kind: 'link.settings'; settings: ExtensionSettings }
  /** ext → host/app: popup's "Open app" (the host starts the app if it isn't running). */
  | { kind: 'link.openApp' }
  /** app → ext: the unpacked folder was rewritten with a newer build; reload from disk. */
  | { kind: 'link.reload' }
  /** host → ext only: whether the host reached the running app. */
  | { kind: 'link.host'; appLinked: boolean }

export type LinkMessage = BridgeMessage | LinkControl

// ---------- What the UI reads (window.bingejam.extension / browsers) ----------

export interface ExtensionConnection {
  browser: string
  browserId: BrowserId | null
  extensionVersion: string
  /** 'development' = Load unpacked / temporary add-on, 'normal' = store. */
  installType: string | null
  tabs: LinkTab[]
  activeTabId: number | null
}

export interface ExtensionStatus {
  /** The app is listening for the extension (named pipe up). */
  listening: boolean
  /** Native host registered for this Windows user (HKCU). */
  hostRegistered: boolean
  /** The extension build this app ships; null if extension/dist hasn't been built. */
  bundledVersion: string | null
  /** Browsers linked right now. */
  connections: ExtensionConnection[]
  /** Every browser that ever linked (kept across restarts). lastSeen = Date.now(), display only. */
  seen: { browser: string; browserId: BrowserId | null; extensionVersion: string; lastSeen: number }[]
  settings: ExtensionSettings
  error: string | null
}

export type InstallRoute = 'edge-store' | 'firefox-xpi' | 'firefox-temporary' | 'unpacked'

export interface DetectedBrowser {
  id: BrowserId
  name: string
  /** How "Add to browser" installs it today (see EXTENSION_STORE). */
  route: InstallRoute
  /** This browser's extension has linked to the app at least once. */
  extensionSeen: boolean
  /** Linked right now. */
  connected: boolean
}

export interface AddToBrowserResult {
  browser: BrowserId
  route: InstallRoute
  ok: boolean
  /** Folder (or manifest.json for Firefox's temporary add-on) to pick; already on the clipboard. */
  path: string | null
  /** The guide for this route, in order. done = the app knows it happened. */
  steps: { label: string; done: boolean }[]
  error: string | null
}
