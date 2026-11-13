// BingeJam background: Chromium service worker / Firefox event page.
//
//   content script (one per streaming tab) ──port "bj-player"──┐
//   popup ──────────────────────────────────port "bj-popup"───┤
//                                                              ▼
//                         background ──runtime.connectNative("com.bingejam.desktop")──▶ native host
//                                                                                     ──named pipe──▶ app
//
// Messages to/from the app are desktop/src/shared/player.ts BridgeMessages plus the link messages in
// desktop/src/shared/extension.ts (kind "link.*"). The native port is opened only while it's needed
// (a streaming tab or the popup is open, or right after install) and closed after IDLE_MS without, so
// no host process lingers. An open native port also keeps Chromium's service worker alive.
'use strict'

const api = globalThis.browser ?? globalThis.chrome
const HOST = 'com.bingejam.desktop'
const IDLE_MS = 2 * 60000
const BACKOFF_MS = [1000, 2000, 5000, 10000, 30000, 60000]
const NO_HOST_RETRY_MS = 5 * 60000 // app not installed: check again now and then
const DOWNLOAD_URL = 'https://syncit-watchparty.web.app/'
const DEFAULT_SETTINGS = { waitForBuffering: true, sameSubtitles: true, followHostEpisodes: false }

const state = {
  native: null,
  /** 'idle' | 'connecting' | 'no-host' | 'forbidden' | 'app-closed' | 'linked' */
  link: 'idle',
  room: null,
  settings: { ...DEFAULT_SETTINGS },
  /** tabId → { port, service, title, subtitle, playing, seek, problem, snapshot, playAt, focusAt } */
  tabs: new Map(),
  activeTabId: null,
  popups: new Set(),
  retry: null,
  attempt: 0,
  idleTimer: null,
  helloUntil: 0,
  browser: null,
  build: null,
  /** Room chat + reactions over the video (link.overlay), replayed to tabs that open later. */
  overlay: null
}

// ---------- Startup ----------
const ready = (async () => {
  try {
    const s = await api.storage.local.get('settings')
    if (s?.settings) state.settings = { ...DEFAULT_SETTINGS, ...s.settings }
  } catch {
    /* defaults */
  }
  try {
    const r = await api.storage.session.get('room')
    if (r?.room !== undefined) state.room = r.room
  } catch {
    /* no session storage */
  }
  try {
    const res = await fetch(api.runtime.getURL('build.json'))
    if (res.ok) state.build = (await res.json()).build ?? null
  } catch {
    /* not built with build.mjs */
  }
  state.browser = await detectBrowser()
  updateBadge()
})()

api.runtime.onInstalled.addListener(() => {
  // Say hello once after install / update / reload so the app's "Add to browser" screen ticks off.
  state.helloUntil = Date.now() + 30000
  void ready.then(() => ensureNative())
})

async function detectBrowser() {
  try {
    if (api.runtime.getBrowserInfo) {
      const i = await api.runtime.getBrowserInfo()
      return { name: i.name || 'Firefox', id: 'firefox' }
    }
  } catch {
    /* not Firefox */
  }
  try {
    if (navigator.brave && (await navigator.brave.isBrave())) return { name: 'Brave', id: 'brave' }
  } catch {
    /* not Brave */
  }
  const brands = (navigator.userAgentData?.brands ?? []).map((b) => b.brand)
  const ua = navigator.userAgent || ''
  if (brands.includes('Microsoft Edge') || /Edg\//.test(ua)) return { name: 'Microsoft Edge', id: 'edge' }
  if (brands.some((b) => /^Opera/.test(b)) || /OPR\//.test(ua)) return { name: 'Opera', id: 'opera' }
  if (brands.includes('Brave')) return { name: 'Brave', id: 'brave' }
  if (brands.includes('Google Chrome')) return { name: 'Google Chrome', id: 'chrome' }
  return { name: 'Chromium', id: null }
}

// ---------- Native link ----------
function needed() {
  return state.tabs.size > 0 || state.popups.size > 0 || Date.now() < state.helloUntil
}

function ensureNative() {
  clearIdle()
  if (state.native || state.retry) return
  let port
  setLink('connecting')
  try {
    port = api.runtime.connectNative(HOST)
  } catch (e) {
    onNativeGone(String(e?.message ?? e))
    return
  }
  state.native = port
  port.onMessage.addListener(onNativeMessage)
  port.onDisconnect.addListener((p) => {
    const err = api.runtime.lastError?.message ?? p?.error?.message ?? ''
    if (state.native === port) onNativeGone(err)
  })
  // Until the host reports the app, assume it's still starting.
  scheduleIdle()
}

function onNativeGone(error) {
  state.native = null
  const e = (error || '').toLowerCase()
  if (e.includes('not found') || e.includes('no such native application')) setLink('no-host')
  else if (e.includes('forbidden')) setLink('forbidden')
  else setLink(state.link === 'idle' ? 'idle' : 'app-closed')
  if (!needed()) {
    setLink('idle')
    state.attempt = 0
    return
  }
  const wait = state.link === 'no-host' || state.link === 'forbidden'
    ? NO_HOST_RETRY_MS
    : BACKOFF_MS[Math.min(state.attempt, BACKOFF_MS.length - 1)]
  state.attempt++
  state.retry = setTimeout(() => {
    state.retry = null
    if (needed()) ensureNative()
  }, wait)
}

function disconnectNative() {
  if (state.retry) clearTimeout(state.retry)
  state.retry = null
  state.attempt = 0
  const p = state.native
  state.native = null
  try {
    p?.disconnect()
  } catch {
    /* gone */
  }
  setLink('idle')
}

function sendNative(msg) {
  if (!state.native) return false
  try {
    state.native.postMessage(msg)
    return true
  } catch {
    return false
  }
}

async function sendHello() {
  await ready
  const manifest = api.runtime.getManifest()
  let installType = null
  try {
    installType = (await api.management?.getSelf?.())?.installType ?? null
  } catch {
    /* not available */
  }
  sendNative({ kind: 'hello', extensionVersion: manifest.version, browser: state.browser?.name ?? 'Browser' })
  sendNative({ kind: 'link.ext', extensionId: api.runtime.id, installType, browserId: state.browser?.id ?? null, build: state.build })
  sendNative({ kind: 'link.settings', settings: state.settings })
  sendTabs()
  for (const [tabId, t] of state.tabs) {
    if (t.snapshot) sendNative({ kind: 'snapshot', snapshot: { ...t.snapshot, tabId }, lastCommandId: t.lastCommandId ?? null })
  }
}

function onNativeMessage(msg) {
  if (!msg || typeof msg.kind !== 'string') return
  switch (msg.kind) {
    case 'link.host':
      if (msg.appLinked) {
        state.attempt = 0
        setLink('linked')
        void sendHello()
      } else {
        setLink('app-closed')
      }
      break
    case 'command': {
      const m = msg.message
      const tab = m && state.tabs.get(m.tabId)
      if (!tab) {
        if (m && typeof m.tabId === 'number') sendNative({ kind: 'tabClosed', tabId: m.tabId })
        return
      }
      tab.port.postMessage({ t: 'command', command: m.command, commandId: m.commandId })
      break
    }
    case 'roomInfo':
      state.room = msg.room ?? null
      try {
        void api.storage.session.set({ room: state.room })
      } catch {
        /* ignore */
      }
      updateBadge()
      pushPopups()
      break
    case 'link.settings':
      if (msg.settings) setSettings(msg.settings, false)
      break
    case 'link.overlay':
      state.overlay = msg.overlay ?? null
      for (const t of state.tabs.values()) postTab(t, { t: 'overlay', overlay: state.overlay })
      break
    case 'link.navigate': {
      const tab = typeof msg.tabId === 'number' ? state.tabs.get(msg.tabId) : null
      if (tab && typeof msg.url === 'string') postTab(tab, { t: 'navigate', url: msg.url })
      break
    }
    case 'link.reload':
      // The app rewrote our unpacked folder with a newer build: load it.
      api.runtime.reload()
      break
  }
}

// ---------- Streaming tabs ----------
api.runtime.onConnect.addListener((port) => {
  if (port.name === 'bj-player') onPlayerPort(port)
  else if (port.name === 'bj-popup') onPopupPort(port)
})

function onPlayerPort(port) {
  const tabId = port.sender?.tab?.id
  if (typeof tabId !== 'number' || port.sender?.frameId) {
    port.disconnect()
    return
  }
  const old = state.tabs.get(tabId)
  if (old && old.port !== port) {
    try {
      old.port.disconnect()
    } catch {
      /* gone */
    }
  }
  const tab = { port, service: null, title: null, subtitle: null, playing: false, seek: 'unknown', problem: null, snapshot: null, lastCommandId: null, playAt: 0, focusAt: Date.now() }
  state.tabs.set(tabId, tab)
  port.onMessage.addListener((msg) => onPlayerMessage(tabId, tab, msg))
  port.onDisconnect.addListener(() => {
    if (state.tabs.get(tabId) !== tab) return
    state.tabs.delete(tabId)
    sendNative({ kind: 'tabClosed', tabId })
    pickActive()
    sendTabs()
    updateBadge()
    pushPopups()
    scheduleIdle()
  })
  ensureNative()
  pickActive()
  updateBadge()
  if (state.overlay) postTab(tab, { t: 'overlay', overlay: state.overlay })
}

function postTab(tab, msg) {
  try {
    tab.port.postMessage(msg)
  } catch {
    /* tab gone; its onDisconnect cleans up */
  }
}

function onPlayerMessage(tabId, tab, msg) {
  if (!msg) return
  if (msg.t === 'snapshot' && msg.snapshot) {
    const s = msg.snapshot
    if (s.playing && !tab.playing) tab.playAt = Date.now()
    tab.playing = !!s.playing
    tab.service = s.service
    tab.snapshot = s
    tab.lastCommandId = msg.lastCommandId ?? null
    // Forwarded in the same task it arrived: this hop adds no measurable delay to ageMs.
    if (state.link === 'linked') sendNative({ kind: 'snapshot', snapshot: { ...s, tabId }, lastCommandId: tab.lastCommandId })
    if (pickActive()) sendTabs()
    pushPopups()
  } else if (msg.t === 'overlaySend' && typeof msg.text === 'string') {
    sendNative({ kind: 'link.overlaySend', text: msg.text.slice(0, 500) })
  } else if (msg.t === 'overlayReact' && typeof msg.emoji === 'string') {
    sendNative({ kind: 'link.overlayReact', emoji: msg.emoji.slice(0, 16) })
  } else if (msg.t === 'info') {
    if (msg.playing && !tab.playing) tab.playAt = Date.now()
    Object.assign(tab, { service: msg.service, title: msg.title, subtitle: msg.subtitle, playing: !!msg.playing, seek: msg.seek, problem: msg.problem ?? null })
    pickActive()
    sendTabs()
    pushPopups()
  }
}

api.tabs.onActivated.addListener(({ tabId }) => {
  const tab = state.tabs.get(tabId)
  if (!tab) return
  tab.focusAt = Date.now()
  if (pickActive()) {
    sendTabs()
    pushPopups()
  }
})

/** The tab the person is using: the one that most recently started playing or was focused. */
function pickActive() {
  let best = null
  let bestAt = -1
  for (const [id, t] of state.tabs) {
    const at = Math.max(t.playAt, t.focusAt) + (t.playing ? 1e12 : 0) // a playing tab beats a paused one
    if (at > bestAt) {
      best = id
      bestAt = at
    }
  }
  const changed = best !== state.activeTabId
  state.activeTabId = best
  return changed
}

let tabsTimer = null
function sendTabs() {
  if (tabsTimer) return
  tabsTimer = setTimeout(() => {
    tabsTimer = null
    const tabs = [...state.tabs].filter(([, t]) => t.service).map(([tabId, t]) => ({
      tabId,
      service: t.service,
      title: t.title ?? null,
      subtitle: t.subtitle ?? null,
      playing: t.playing,
      seek: t.seek ?? 'unknown',
      problem: t.problem ?? null
    }))
    sendNative({ kind: 'link.tabs', tabs, activeTabId: state.activeTabId })
  }, 100)
}

// ---------- Idle ----------
function scheduleIdle() {
  clearIdle()
  if (needed()) {
    if (state.tabs.size === 0 && state.popups.size === 0) {
      // Only the post-install hello keeps us: check again when that window ends.
      state.idleTimer = setTimeout(scheduleIdle, Math.max(0, state.helloUntil - Date.now()) + 1000)
    }
    return
  }
  state.idleTimer = setTimeout(() => {
    state.idleTimer = null
    if (!needed()) disconnectNative()
  }, IDLE_MS)
}

function clearIdle() {
  if (state.idleTimer) clearTimeout(state.idleTimer)
  state.idleTimer = null
}

// ---------- Popup ----------
function onPopupPort(port) {
  state.popups.add(port)
  port.onDisconnect.addListener(() => {
    state.popups.delete(port)
    scheduleIdle()
  })
  port.onMessage.addListener((msg) => onPopupMessage(port, msg))
  void ready.then(() => {
    ensureNative()
    pushPopup(port)
  })
}

function popupState() {
  const t = state.activeTabId != null ? state.tabs.get(state.activeTabId) : null
  return {
    link: state.link,
    browser: state.browser?.name ?? null,
    room: state.room,
    settings: state.settings,
    tab: t
      ? {
          service: t.service,
          title: t.title,
          subtitle: t.subtitle,
          playing: t.playing,
          positionMs: t.snapshot?.positionMs ?? null,
          seek: t.seek,
          problem: t.problem
        }
      : null
  }
}

function pushPopup(port) {
  try {
    port.postMessage({ t: 'state', state: popupState() })
  } catch {
    state.popups.delete(port)
  }
}

function pushPopups() {
  for (const p of state.popups) pushPopup(p)
}

function onPopupMessage(port, msg) {
  if (!msg) return
  if (msg.t === 'setting' && msg.key in DEFAULT_SETTINGS) {
    setSettings({ ...state.settings, [msg.key]: !!msg.value }, true)
  } else if (msg.t === 'toggle-play') {
    const t = state.activeTabId != null ? state.tabs.get(state.activeTabId) : null
    t?.port.postMessage({ t: 'local', action: t.playing ? 'pause' : 'play' })
  } else if (msg.t === 'open-app') {
    if (state.link === 'no-host' || state.link === 'forbidden') {
      void api.tabs.create({ url: DOWNLOAD_URL })
    } else {
      // Linked: the app comes to the front. Not linked: the host starts it.
      sendNative({ kind: 'link.openApp' })
    }
  } else if (msg.t === 'refresh') {
    pushPopup(port)
  }
}

function setSettings(settings, tellApp) {
  state.settings = { ...DEFAULT_SETTINGS, ...settings }
  try {
    void api.storage.local.set({ settings: state.settings })
  } catch {
    /* ignore */
  }
  if (tellApp) sendNative({ kind: 'link.settings', settings: state.settings })
  pushPopups()
}

// ---------- Badge ----------
function setLink(link) {
  if (state.link === link) return
  state.link = link
  updateBadge()
  pushPopups()
}

function updateBadge() {
  let text = ''
  let color = '#6B7280'
  let title = 'BingeJam'
  if (state.link === 'linked' && state.room) {
    text = 'ON'
    color = '#059669'
    title = `BingeJam · Room ${state.room.code}${state.room.inSync ? ' · in sync' : ''}`
  } else if (state.link === 'linked') {
    title = 'BingeJam · Linked to the app'
  } else if (state.tabs.size > 0 && (state.link === 'app-closed' || state.link === 'no-host' || state.link === 'forbidden')) {
    text = 'OFF'
    title = state.link === 'app-closed' ? 'BingeJam · Open the app to sync this tab' : 'BingeJam · Install the BingeJam app'
  }
  try {
    void api.action.setBadgeText({ text })
    void api.action.setBadgeBackgroundColor({ color })
    void api.action.setBadgeTextColor?.({ color: '#FFFFFF' })
    void api.action.setTitle({ title })
  } catch {
    /* ignore */
  }
}
