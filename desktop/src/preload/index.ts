import { contextBridge, ipcRenderer } from 'electron'
import type { BingeJamBridge } from './bridge'

// The only door between the UI and the main process: small, typed, no Node access in the page.
const bridge: BingeJamBridge = {
  platform: process.platform,
  version: () => ipcRenderer.invoke('app:version'),
  openExternal: (url) => ipcRenderer.invoke('shell:open-external', url),
  relayFetch: (request) => ipcRenderer.invoke('relay:fetch', request),
  onDeepLink: (listener) => subscribe('app:deep-link', listener),
  extension: {
    status: () => ipcRenderer.invoke('extension:status'),
    onStatus: (listener) => subscribe('extension:status', listener),
    onMessage: (listener) => subscribe('extension:message', listener),
    send: (message) => ipcRenderer.invoke('extension:send', message)
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
