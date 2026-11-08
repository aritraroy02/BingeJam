import { app, BrowserWindow, ipcMain, nativeTheme, screen, shell } from 'electron'
import { writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { BRAND } from '@shared/brand'

// BingeJam desktop: one window, one instance. Firebase (auth + Realtime Database) runs in the
// renderer like the web SDK expects; the main process owns the window, OS integration and IPC.

const APP_ID = 'com.bingejam.desktop'
const PROTOCOL = 'bingejam' // bingejam://… links (email sign-in hand-off, invites) open the app

let mainWindow: BrowserWindow | null = null

if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', (_event, argv) => {
    // A second launch (or a bingejam:// link) focuses the running window instead.
    focusMainWindow()
    const link = argv.find((a) => a.startsWith(`${PROTOCOL}://`))
    if (link) mainWindow?.webContents.send('app:deep-link', link)
  })
  app.whenReady().then(onReady)
}

function onReady(): void {
  app.setAppUserModelId(APP_ID)
  nativeTheme.themeSource = 'dark'
  if (process.defaultApp && process.argv.length >= 2) {
    app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [process.argv[1]])
  } else {
    app.setAsDefaultProtocolClient(PROTOCOL)
  }
  registerIpc()
  createMainWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow()
  })
}

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

function createMainWindow(): void {
  // The design's 1440×900, shrunk to fit smaller (or scaled-up) screens.
  const area = screen.getPrimaryDisplay().workAreaSize
  mainWindow = new BrowserWindow({
    width: Math.min(1440, Math.round(area.width * 0.94)),
    height: Math.min(900, Math.round(area.height * 0.94)),
    minWidth: 1100,
    minHeight: 700,
    show: false,
    title: BRAND.name,
    backgroundColor: '#08060B', // the design's base, so no white flash before the UI paints
    // The UI draws its own glass top area; Windows keeps its native minimise/maximise/close.
    titleBarStyle: 'hidden',
    titleBarOverlay: { color: '#00000000', symbolColor: '#FFFFFF', height: 32 },
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, '../preload/index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: true
    }
  })

  mainWindow.once('ready-to-show', () => mainWindow?.show())
  mainWindow.on('closed', () => {
    mainWindow = null
  })

  // Links (web pages, store listings) open in the user's browser, never inside the app.
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    if (isSafeExternalUrl(url)) void shell.openExternal(url)
    return { action: 'deny' }
  })
  mainWindow.webContents.on('will-navigate', (event, url) => {
    if (url !== mainWindow?.webContents.getURL()) {
      event.preventDefault()
      if (isSafeExternalUrl(url)) void shell.openExternal(url)
    }
  })

  // BJ_DEMO=1: the design's sample content, no Firebase (store screenshots, design reviews).
  const query: Record<string, string> = process.env['BJ_DEMO'] === '1' ? { demo: '1' } : {}
  // BJ_SCREEN=chats|group|room|streams|browser|settings|friends: open that screen (snapshots).
  if (process.env['BJ_SCREEN']) query.screen = process.env['BJ_SCREEN']
  const devUrl = process.env['ELECTRON_RENDERER_URL']
  if (!app.isPackaged && devUrl) {
    const url = new URL(devUrl)
    for (const [k, v] of Object.entries(query)) url.searchParams.set(k, v)
    void mainWindow.loadURL(url.toString())
  } else {
    void mainWindow.loadFile(join(__dirname, '../renderer/index.html'), { query })
  }

  // BJ_SNAPSHOT=<file.png>: save a screenshot once the entry animations have settled, then quit.
  // Lets a script compare the app with desktop/design/*.png without anyone at the screen.
  const snapshot = process.env['BJ_SNAPSHOT']
  if (snapshot) {
    mainWindow.webContents.once('did-finish-load', () => {
      setTimeout(async () => {
        const image = await mainWindow?.webContents.capturePage()
        if (image) writeFileSync(snapshot, image.toPNG())
        app.quit()
      }, Number(process.env['BJ_SNAPSHOT_DELAY'] ?? 2500))
    })
  }
}

function focusMainWindow(): void {
  if (!mainWindow) return
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
}

function isSafeExternalUrl(url: string): boolean {
  try {
    const { protocol } = new URL(url)
    return protocol === 'https:' || protocol === 'mailto:'
  } catch {
    return false
  }
}

function registerIpc(): void {
  ipcMain.handle('app:version', () => app.getVersion())
  ipcMain.handle('shell:open-external', (_event, url: unknown) => {
    if (typeof url === 'string' && isSafeExternalUrl(url)) return shell.openExternal(url)
    return undefined
  })
}
