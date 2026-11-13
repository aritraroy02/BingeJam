import { BrowserWindow, ipcMain, screen } from 'electron'
import { join } from 'node:path'
import type { MiniAction, MiniRoomState } from '@shared/desktop'
import { appIcon } from './desktop'

// The mini room window: small, always on top (above a fullscreen browser too), showing the room's
// sync state, play/pause and room chat. The main window owns the room; this window only mirrors
// its state (mini:state) and sends button presses back (mini:action).

let mini: BrowserWindow | null = null
let lastState: MiniRoomState | null = null

interface Hooks {
  main: () => BrowserWindow | null
  load: (win: BrowserWindow, query: Record<string, string>) => void
}

let hooks: Hooks

function open(): void {
  if (mini) {
    mini.showInactive()
    return
  }
  const area = screen.getPrimaryDisplay().workArea
  const width = 340
  const height = 460
  mini = new BrowserWindow({
    width,
    height,
    minWidth: 280,
    minHeight: 220,
    x: area.x + area.width - width - 24,
    y: area.y + area.height - height - 24,
    frame: false,
    resizable: true,
    maximizable: false,
    minimizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: true,
    show: false,
    title: 'BingeJam room',
    icon: appIcon(),
    backgroundColor: '#121110',
    webPreferences: { preload: join(__dirname, '../preload/index.js'), contextIsolation: true, nodeIntegration: false, sandbox: true }
  })
  // 'screen-saver' keeps it above fullscreen windows (the browser playing the movie).
  mini.setAlwaysOnTop(true, 'screen-saver')
  mini.setVisibleOnAllWorkspaces(true, { visibleOnFullScreen: true })
  mini.once('ready-to-show', () => mini?.showInactive())
  mini.on('closed', () => {
    mini = null
    hooks.main()?.webContents.send('mini:open', false)
  })
  mini.webContents.once('did-finish-load', () => {
    if (lastState) mini?.webContents.send('mini:state', lastState)
  })
  hooks.load(mini, { mini: '1' })
  hooks.main()?.webContents.send('mini:open', true)
}

function close(): void {
  mini?.close()
}

export function toggleMini(): void {
  if (mini) close()
  else if (lastState) open()
}

export function setupMini(h: Hooks): void {
  hooks = h
  ipcMain.on('mini:open', (_e, on: unknown) => (on === true ? open() : close()))
  ipcMain.on('mini:state', (_e, state: unknown) => {
    lastState = (state as MiniRoomState | null) ?? null
    if (!lastState) close()
    else mini?.webContents.send('mini:state', lastState)
  })
  ipcMain.on('mini:action', (e, action: unknown) => {
    if (!mini || e.sender !== mini.webContents) return
    const a = action as MiniAction
    if (a?.kind === 'open') {
      const main = hooks.main()
      if (main) {
        if (main.isMinimized()) main.restore()
        main.show()
        main.focus()
      }
      return
    }
    hooks.main()?.webContents.send('mini:action', a)
  })
}
