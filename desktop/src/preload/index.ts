import { contextBridge, ipcRenderer } from 'electron'
import type { BingeJamBridge } from './bridge'

// The only door between the UI and the main process: small, typed, no Node access in the page.
const bridge: BingeJamBridge = {
  platform: process.platform,
  version: () => ipcRenderer.invoke('app:version'),
  openExternal: (url) => ipcRenderer.invoke('shell:open-external', url),
  onDeepLink: (listener) => {
    const handler = (_event: unknown, link: string): void => listener(link)
    ipcRenderer.on('app:deep-link', handler)
    return () => ipcRenderer.removeListener('app:deep-link', handler)
  }
}

contextBridge.exposeInMainWorld('bingejam', bridge)
