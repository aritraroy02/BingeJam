import { contextBridge, ipcRenderer } from 'electron'
import type { BingeJamBridge } from './bridge'

// The only door between the UI and the main process: small, typed, no Node access in the page.
const bridge: BingeJamBridge = {
  platform: process.platform,
  version: () => ipcRenderer.invoke('app:version'),
  openExternal: (url) => ipcRenderer.invoke('shell:open-external', url),
  relayFetch: (request) => ipcRenderer.invoke('relay:fetch', request),
  relayAvatar: (request) => ipcRenderer.invoke('relay:avatar', request),
  onDeepLink: (listener) => subscribe('app:deep-link', listener),
  takeDeepLink: () => ipcRenderer.invoke('app:take-deep-link'),
  games: {
    current: () => ipcRenderer.invoke('games:current'),
    onChange: (listener) => subscribe('games:current', listener)
  },
  desktop: {
    get: () => ipcRenderer.invoke('desktop:get'),
    setStartWithWindows: (on) => ipcRenderer.invoke('desktop:set-start-with-windows', on),
    setPrefs: (prefs) => ipcRenderer.invoke('desktop:set-prefs', prefs),
    onPrefs: (listener) => subscribe('desktop:prefs', listener),
    notify: (request) => ipcRenderer.send('notify:show', request),
    setBadge: (count) => ipcRenderer.send('notify:badge', count),
    onNotifyClick: (listener) => subscribe('notify:click', listener),
    onGlobalShortcut: (listener) => subscribe('shortcut:global', listener),
    setTheme: (theme) => ipcRenderer.send('window:theme', theme),
    setZoom: (factor) => ipcRenderer.send('window:zoom', factor)
  },
  mini: {
    open: (on) => ipcRenderer.send('mini:open', on),
    state: (state) => ipcRenderer.send('mini:state', state),
    onAction: (listener) => subscribe('mini:action', listener),
    onOpen: (listener) => subscribe('mini:open', listener),
    action: (action) => ipcRenderer.send('mini:action', action),
    onState: (listener) => subscribe('mini:state', listener)
  },
  extension: {
    status: () => ipcRenderer.invoke('extension:status'),
    onStatus: (listener) => subscribe('extension:status', listener),
    onMessage: (listener) => subscribe('extension:message', listener),
    send: (message) => ipcRenderer.invoke('extension:send', message),
    overlay: (overlay) => ipcRenderer.send('extension:overlay', overlay),
    navigate: (tabId, url) => ipcRenderer.invoke('extension:navigate', tabId, url),
    onOverlayInput: (listener) => subscribe('extension:overlay-input', listener)
  },
  browsers: {
    detect: () => ipcRenderer.invoke('browsers:detect'),
    addTo: (browser) => ipcRenderer.invoke('browsers:add-to', browser)
  }
}

function subscribe<T>(channel: string, listener: (value: T) => void): () => void {
  const handler = (_event: unknown, value: T): void => listener(value)
  ipcRenderer.on(channel, handler)
  return () => ipcRenderer.removeListener(channel, handler)
}

contextBridge.exposeInMainWorld('bingejam', bridge)
