// Browser-extension link, main-process side. One call from src/main/index.ts:
//   setupExtensionLink({ window: () => mainWindow, focus: focusMainWindow })
//
//   renderer ⇄ IPC (extension:*, browsers:*) ⇄ ExtensionLinkServer ⇄ \\.\pipe\bingejam-<user>
//            ⇄ native host (one per browser) ⇄ extension background ⇄ content script in the tab
import { app, ipcMain, type BrowserWindow } from 'electron'
import type { BridgeMessage } from '@shared/player'
import type { BrowserId, DetectedBrowser, ExtensionStatus, OverlayState } from '@shared/extension'
import { addToBrowser, findBrowsers, routeFor } from './browsers'
import { bundledBuild, syncInstallDir } from './bundle'
import { registerNativeHost } from './registry'
import { ExtensionLinkServer } from './server'

const BROWSER_IDS: BrowserId[] = ['edge', 'firefox', 'chrome', 'brave', 'opera']

export function setupExtensionLink(opts: { window: () => BrowserWindow | null; focus: () => void }): void {
  if (process.platform !== 'win32') return // Windows first (PLAN.md); the rest of the app still runs

  let hostRegistered = false
  let hostError: string | null = null
  /** "<browserId>:<build>" we already asked to reload, so a browser that keeps an old copy can't loop. */
  const reloadAsked = new Set<string>()

  const toRenderer = (channel: string, payload: unknown): void => {
    const w = opts.window()
    if (w && !w.isDestroyed()) w.webContents.send(channel, payload)
  }

  let statusTimer: NodeJS.Timeout | null = null
  const pushStatus = (): void => {
    if (statusTimer) return
    statusTimer = setTimeout(() => {
      statusTimer = null
      toRenderer('extension:status', status())
    }, 50)
  }

  const server = new ExtensionLinkServer({
    message: (msg: BridgeMessage) => toRenderer('extension:message', msg),
    status: pushStatus,
    openApp: () => opts.focus(),
    overlayInput: (input) => toRenderer('extension:overlay-input', input),
    linked: (c) => {
      // An unpacked copy older than what the app ships: rewrite the folder and have it reload.
      const bundled = bundledBuild()
      if (!bundled || !c.build || c.build === bundled.build || c.installType !== 'development') return
      const key = `${c.browserId}:${c.build}`
      if (reloadAsked.has(key)) return
      reloadAsked.add(key)
      syncInstallDir(c.browserId === 'firefox' ? 'firefox' : 'chromium', true)
      server.sendControl(c.id, { kind: 'link.reload' })
    }
  })

  function status(): ExtensionStatus {
    return {
      listening: server.listening,
      hostRegistered,
      bundledVersion: bundledBuild()?.version ?? null,
      connections: server.connections().map(({ id: _id, ...c }) => c),
      seen: server.seen(),
      settings: server.settings(),
      error: server.error ?? hostError
    }
  }

  server.start()
  // Keep copies the person already loaded up to date with this app's build.
  try {
    syncInstallDir('chromium', true)
    syncInstallDir('firefox', true)
  } catch (e) {
    console.error('[extension] refreshing the unpacked copy failed', e)
  }
  registerNativeHost()
    .then(() => {
      hostRegistered = true
      hostError = null
    })
    .catch((e: unknown) => {
      hostError = `Native host registration failed: ${e instanceof Error ? e.message : String(e)}`
      console.error('[extension]', hostError)
    })
    .finally(pushStatus)

  app.on('will-quit', () => server.stop())

  ipcMain.handle('extension:status', () => status())
  ipcMain.handle('extension:send', (_e, msg: unknown) => {
    if (!msg || typeof msg !== 'object') return false
    const kind = (msg as { kind?: unknown }).kind
    if (kind !== 'command' && kind !== 'roomInfo') return false
    return server.sendFromApp(msg as BridgeMessage)
  })
  ipcMain.on('extension:overlay', (_e, overlay: unknown) => server.setOverlay((overlay as OverlayState | null) ?? null))
  ipcMain.handle('extension:navigate', (_e, tabId: unknown, url: unknown) =>
    typeof tabId === 'number' && typeof url === 'string' ? server.navigate(tabId, url) : false
  )
  ipcMain.handle('browsers:detect', async (): Promise<DetectedBrowser[]> => {
    const connected = server.connections()
    const seen = server.seen()
    return (await findBrowsers()).map((b) => ({
      id: b.id,
      name: b.name,
      route: routeFor(b.id),
      extensionSeen: seen.some((s) => s.browserId === b.id),
      connected: connected.some((c) => c.browserId === b.id)
    }))
  })
  ipcMain.handle('browsers:add-to', (_e, id: unknown) => {
    if (typeof id !== 'string' || !BROWSER_IDS.includes(id as BrowserId)) throw new Error('Unknown browser')
    const connected = server.connections().some((c) => c.browserId === id)
    return addToBrowser(id as BrowserId, connected)
  })
}
