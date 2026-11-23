import { app, BrowserWindow, ipcMain, net } from 'electron'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { createReadStream, createWriteStream, existsSync, mkdirSync, readFileSync, renameSync, rmSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { DOWNLOAD_PREFIX, pickUpdate, RELEASES_API, type PcRelease, type UpdateState } from '@shared/update'

// In-app updates (2026-11-23): the app finds a newer PC release on GitHub and says so at once
// ("A new version is available · Update") while it downloads the installer in the background (checked
// against the release's SHA-256). Update runs it silently (/S) and reopens BingeJam (--force-run); pressed
// mid-download, it installs as soon as the download is checked. No browser, no admin rights, and no SmartScreen prompt: a
// file the app downloads itself carries no "downloaded from the internet" mark.
//
// Only in the installed app. For testing, BJ_UPDATE_FEED points at a JSON file (or URL) shaped like
// GitHub's releases list and BJ_UPDATE_DOWNLOADS at where its installers may come from.

const FIRST_CHECK_MS = 15_000
const CHECK_EVERY_MS = 60 * 60_000
/** Coming back to the window checks again if the last check is older than this (GitHub allows 60/hour). */
const FOCUS_CHECK_AFTER_MS = 15 * 60_000

let state: UpdateState = { status: 'idle' }
let ready: { release: PcRelease; file: string } | null = null
let busy = false
/** Update was pressed before the download finished: install as soon as it's checked. */
let installWhenReady = false
let lastCheckAt = 0
let quitApp: () => void = () => undefined

function publish(next: UpdateState): void {
  state = next
  // A window that's closing can't take it; that must never break the update itself.
  for (const w of BrowserWindow.getAllWindows()) {
    if (w.isDestroyed()) continue
    try {
      w.webContents.send('update:state', state)
    } catch (e) {
      console.warn('Update state not sent', e)
    }
  }
}

async function loadFeed(): Promise<unknown> {
  const feed = process.env['BJ_UPDATE_FEED']
  if (feed && !/^https?:\/\//.test(feed)) return JSON.parse(readFileSync(feed, 'utf8')) as unknown
  const res = await net.fetch(feed || RELEASES_API, { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'BingeJam-desktop' } })
  if (!res.ok) throw new Error(`releases ${res.status}`)
  return (await res.json()) as unknown
}

async function sha256Of(file: string): Promise<string> {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(file)) hash.update(chunk as Buffer)
  return hash.digest('hex')
}

/** The installer is ours and whole: the right size and, when the release says, the right SHA-256. */
async function intact(file: string, release: PcRelease): Promise<boolean> {
  if (!existsSync(file) || statSync(file).size !== release.size) return false
  return release.sha256 === null || (await sha256Of(file)) === release.sha256
}

async function download(release: PcRelease): Promise<string> {
  const dir = join(app.getPath('temp'), 'BingeJam-update')
  mkdirSync(dir, { recursive: true })
  const file = join(dir, `BingeJam-Setup-${release.version}.exe`)
  if (await intact(file, release)) return file
  const part = `${file}.part`
  rmSync(part, { force: true })
  publish({ status: 'downloading', version: release.version, progress: 0, notes: release.notes })
  const res = await net.fetch(release.url)
  if (!res.ok || !res.body) throw new Error(`download ${res.status}`)
  const out = createWriteStream(part)
  const hash = createHash('sha256')
  const reader = res.body.getReader()
  let got = 0
  let shown = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      hash.update(value)
      got += value.length
      if (!out.write(value)) await once(out, 'drain')
      const progress = Math.min(1, got / release.size)
      if (progress - shown >= 0.02) {
        shown = progress
        publish({ status: 'downloading', version: release.version, progress, notes: release.notes })
      }
    }
  } finally {
    await new Promise<void>((resolve) => out.end(() => resolve()))
  }
  if (got !== release.size || (release.sha256 !== null && hash.digest('hex') !== release.sha256)) {
    rmSync(part, { force: true })
    throw new Error('The download was damaged')
  }
  rmSync(file, { force: true })
  renameSync(part, file)
  return file
}

/** [manual]: from Settings → Check for updates; automatic checks stay quiet unless there's an update. */
async function check(manual: boolean): Promise<void> {
  if (busy || state.status === 'ready') return
  busy = true
  lastCheckAt = Date.now()
  let downloading = false
  if (manual) publish({ status: 'checking' })
  try {
    const release = pickUpdate(await loadFeed(), app.getVersion(), process.env['BJ_UPDATE_DOWNLOADS'] || DOWNLOAD_PREFIX)
    if (!release) {
      publish({ status: 'current', checkedAt: Date.now() })
      return
    }
    downloading = true
    const file = await download(release)
    ready = { release, file }
    publish({ status: 'ready', version: release.version, notes: release.notes })
    if (installWhenReady) install()
  } catch (e) {
    installWhenReady = false
    console.warn('Update check failed', e)
    publish(manual || downloading ? { status: 'error', message: "Couldn't update. Check your connection and try again." } : { status: 'idle' })
  } finally {
    busy = false
  }
}

/** Silent install over this copy; the installer waits for us to exit, then reopens BingeJam. */
function install(): void {
  if (!ready) return
  const update = ready
  // If Windows can't start it (now or a moment later), stay open, drop the file and say so.
  const fail = (e: unknown): void => {
    console.warn('Update installer did not start', e)
    installWhenReady = false
    ready = null
    rmSync(update.file, { force: true })
    publish({ status: 'error', message: "Couldn't start the update. Try again." })
  }
  let child: ReturnType<typeof spawn>
  try {
    child = spawn(update.file, ['/S', '--force-run'], { detached: true, stdio: 'ignore', windowsHide: true })
  } catch (e) {
    fail(e)
    return
  }
  // Quit only once Windows has started the installer.
  child.once('spawn', () => {
    child.unref()
    setTimeout(quitApp, 200)
  })
  child.once('error', fail)
}

export function setupUpdater({ quit }: { quit: () => void }): void {
  quitApp = quit
  ipcMain.handle('update:get', () => state)
  ipcMain.handle('update:check', () => void check(true))
  // true = installing now, or as soon as the download in progress is checked; false = nothing to install.
  ipcMain.handle('update:install', () => {
    if (ready) {
      install()
      return true
    }
    if (state.status === 'downloading') {
      installWhenReady = true
      return true
    }
    return false
  })
  const enabled = app.isPackaged || !!process.env['BJ_UPDATE_FEED']
  if (!enabled || process.platform !== 'win32') return
  setTimeout(() => void check(false), FIRST_CHECK_MS)
  setInterval(() => void check(false), CHECK_EVERY_MS)
  app.on('browser-window-focus', () => {
    if (Date.now() - lastCheckAt > FOCUS_CHECK_AFTER_MS) void check(false)
  })
}
