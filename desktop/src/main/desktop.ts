import { app, BrowserWindow, globalShortcut, ipcMain, Menu, nativeImage, Notification, Tray, type NativeImage } from 'electron'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { BRAND } from '@shared/brand'
import { GLOBAL_SHORTCUTS, type DesktopPrefs, type GlobalShortcutId, type NotifyRequest } from '@shared/desktop'

// The app as a Windows citizen: tray icon (closing the window keeps BingeJam running so friends
// still see you online and messages still pop up), start with Windows, toast notifications,
// shortcuts that work while another app (the browser with the movie) has focus, and the title-bar
// colours for the light theme.

interface Hooks {
  window: () => BrowserWindow | null
  focus: () => void
  quit: () => void
  toggleMini: () => void
}

const DEFAULT_PREFS: DesktopPrefs = { closeToTray: true, globalShortcuts: true }

let tray: Tray | null = null
let prefs: DesktopPrefs = DEFAULT_PREFS
let hooks: Hooks

function prefsFile(): string {
  return join(app.getPath('userData'), 'desktop-prefs.json')
}

function loadPrefs(): DesktopPrefs {
  try {
    const raw = JSON.parse(readFileSync(prefsFile(), 'utf8')) as Partial<DesktopPrefs>
    return { ...DEFAULT_PREFS, ...raw }
  } catch {
    return DEFAULT_PREFS
  }
}

function savePrefs(): void {
  try {
    writeFileSync(prefsFile(), JSON.stringify(prefs, null, 2))
  } catch {
    // Not saved; still applied this session.
  }
}

export function appIcon(size?: number): NativeImage {
  const file = join(app.getAppPath(), 'build', 'icon.png')
  const image = existsSync(file) ? nativeImage.createFromPath(file) : nativeImage.createEmpty()
  return size && !image.isEmpty() ? image.resize({ width: size, height: size }) : image
}

/** Launched by Windows at sign-in: start in the tray, no window. */
export function startedHidden(): boolean {
  return process.argv.includes('--hidden')
}

export function shouldCloseToTray(): boolean {
  return prefs.closeToTray && tray !== null
}

// ---------- Start with Windows ----------

function loginItemSupported(): boolean {
  // A dev run (electron.exe + a path) would register the bare Electron binary: don't.
  return process.platform === 'win32' && app.isPackaged
}

function startWithWindows(): boolean | null {
  if (!loginItemSupported()) return null
  return app.getLoginItemSettings({ args: ['--hidden'] }).openAtLogin
}

function setStartWithWindows(on: boolean): boolean | null {
  if (!loginItemSupported()) return null
  app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] })
  rebuildTrayMenu()
  return startWithWindows()
}

// ---------- Tray ----------

function rebuildTrayMenu(): void {
  if (!tray) return
  const login = startWithWindows()
  tray.setContextMenu(
    Menu.buildFromTemplate([
      { label: `Open ${BRAND.name}`, click: () => hooks.focus() },
      { type: 'separator' },
      ...(login === null
        ? []
        : [{ label: 'Start with Windows', type: 'checkbox' as const, checked: login, click: (item: Electron.MenuItem) => setStartWithWindows(item.checked) }]),
      {
        label: 'Keep running when closed',
        type: 'checkbox',
        checked: prefs.closeToTray,
        click: (item) => {
          prefs = { ...prefs, closeToTray: item.checked }
          savePrefs()
          broadcastPrefs()
        }
      },
      { type: 'separator' },
      { label: `Quit ${BRAND.name}`, click: () => hooks.quit() }
    ])
  )
}

function createTray(): void {
  tray = new Tray(appIcon(16))
  tray.setToolTip(BRAND.name)
  tray.on('click', () => hooks.focus())
  rebuildTrayMenu()
}

/** Unread messages + requests on the tray tooltip and the taskbar overlay. */
function setBadge(count: number): void {
  tray?.setToolTip(count > 0 ? `${BRAND.name} · ${count} new` : BRAND.name)
  const win = hooks.window()
  if (!win) return
  if (count > 0) win.setOverlayIcon(badgeImage(), `${count} new`)
  else win.setOverlayIcon(null, '')
  app.setBadgeCount(count)
}

let badge: NativeImage | null = null
function badgeImage(): NativeImage {
  // A small red dot: the taskbar overlay is 16×16 and doesn't fit a number legibly.
  if (badge) return badge
  const size = 16
  const px = Buffer.alloc(size * size * 4)
  for (let y = 0; y < size; y++)
    for (let x = 0; x < size; x++) {
      const d = Math.hypot(x - 7.5, y - 7.5)
      const i = (y * size + x) * 4
      if (d <= 7.5) {
        // BGRA
        px[i] = 0x2e
        px[i + 1] = 0x10
        px[i + 2] = 0xc8
        px[i + 3] = d > 6.8 ? 160 : 255
      }
    }
  badge = nativeImage.createFromBitmap(px, { width: size, height: size })
  return badge
}

// ---------- Notifications ----------

const shown = new Map<string, Notification>()

function notify(req: NotifyRequest): void {
  if (!Notification.isSupported()) return
  // One toast per thread: a newer message from the same chat replaces the older toast.
  shown.get(req.tag)?.close()
  const n = new Notification({ title: req.title, body: req.body, icon: appIcon(64), silent: req.silent ?? false })
  n.on('click', () => {
    hooks.focus()
    hooks.window()?.webContents.send('notify:click', req.action)
  })
  n.on('close', () => {
    if (shown.get(req.tag) === n) shown.delete(req.tag)
  })
  shown.set(req.tag, n)
  n.show()
}

// ---------- Global shortcuts ----------

function registerShortcuts(): void {
  globalShortcut.unregisterAll()
  if (!prefs.globalShortcuts) return
  for (const [id, accel] of Object.entries(GLOBAL_SHORTCUTS) as [GlobalShortcutId, string][]) {
    try {
      globalShortcut.register(accel, () => {
        if (id === 'miniWindow') hooks.toggleMini()
        else hooks.window()?.webContents.send('shortcut:global', id)
      })
    } catch {
      // Taken by another app: that one shortcut just doesn't work.
    }
  }
}

function broadcastPrefs(): void {
  for (const w of BrowserWindow.getAllWindows()) w.webContents.send('desktop:prefs', prefs)
}

// ---------- Setup ----------

export function setupDesktop(h: Hooks): void {
  hooks = h
  prefs = loadPrefs()
  createTray()
  registerShortcuts()
  app.on('will-quit', () => globalShortcut.unregisterAll())

  ipcMain.handle('desktop:get', () => ({ prefs, startWithWindows: startWithWindows() }))
  ipcMain.handle('desktop:set-start-with-windows', (_e, on: unknown) => setStartWithWindows(on === true))
  ipcMain.handle('desktop:set-prefs', (_e, next: unknown) => {
    const n = (next ?? {}) as Partial<DesktopPrefs>
    prefs = {
      closeToTray: typeof n.closeToTray === 'boolean' ? n.closeToTray : prefs.closeToTray,
      globalShortcuts: typeof n.globalShortcuts === 'boolean' ? n.globalShortcuts : prefs.globalShortcuts
    }
    savePrefs()
    registerShortcuts()
    rebuildTrayMenu()
    broadcastPrefs()
    return prefs
  })
  ipcMain.on('notify:show', (_e, req: unknown) => {
    const r = req as Partial<NotifyRequest> | null
    if (!r || typeof r.title !== 'string' || typeof r.body !== 'string' || typeof r.tag !== 'string') return
    notify({ tag: r.tag.slice(0, 100), title: r.title.slice(0, 120), body: r.body.slice(0, 300), action: String(r.action ?? '').slice(0, 200), silent: r.silent === true })
  })
  ipcMain.on('notify:badge', (_e, count: unknown) => setBadge(typeof count === 'number' && count > 0 ? Math.min(999, Math.floor(count)) : 0))
  // Compact mode: Chromium's zoom scales the whole layout evenly (text, spacing, icons).
  ipcMain.on('window:zoom', (e, factor: unknown) => {
    if (typeof factor === 'number' && factor >= 0.7 && factor <= 1.2) e.sender.setZoomFactor(factor)
  })
  ipcMain.on('window:theme', (e, theme: unknown) => {
    const win = BrowserWindow.fromWebContents(e.sender)
    if (!win) return
    const light = theme === 'light'
    try {
      win.setTitleBarOverlay({ color: '#00000000', symbolColor: light ? '#1E1B18' : '#FFFFFF', height: 32 })
    } catch {
      // Window without an overlay (mini window).
    }
    win.setBackgroundColor(light ? '#F6F3EE' : '#121110')
  })
}
