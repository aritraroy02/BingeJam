import { spawn, type ChildProcess } from 'node:child_process'
import { createInterface } from 'node:readline'
import { app, BrowserWindow, ipcMain } from 'electron'
import { parseMusicLine, sameSong, type NowPlaying } from '@shared/music'
import { musicHelperScript } from './musicScript'

// "Listening to … on Spotify", like Discord (2026-11-25): a small PowerShell helper stays open and
// every POLL_SECONDS reads Windows' media sessions (GlobalSystemMediaTransportControlsSessionManager,
// the list the volume flyout shows). It prints a line only when Spotify's song or play state changes,
// and only Spotify's: what plays in browsers or other apps never leaves the helper. The renderer
// shares it with friends only when the person shares their activity.

const POLL_SECONDS = 3
/** The helper quit (an old Windows without media sessions, or it crashed): try again this much later. */
const RESTART_MS = [10_000, 60_000, 5 * 60_000]

let current: NowPlaying | null = null
let child: ChildProcess | null = null
let restarts = 0
let stopped = false

function broadcast(): void {
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.isDestroyed()) w.webContents.send('music:current', current)
  }
}

function start(): void {
  if (stopped) return
  const startedAt = Date.now()
  const proc = spawn('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', musicHelperScript(process.pid, POLL_SECONDS)], { windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] })
  child = proc
  createInterface({ input: proc.stdout! }).on('line', (line) => {
    const next = parseMusicLine(line)
    if (sameSong(next, current)) return
    current = next
    broadcast()
  })
  proc.on('error', (e) => console.warn('Music helper did not start', e))
  proc.on('exit', () => {
    if (child === proc) child = null
    if (current) {
      current = null
      broadcast()
    }
    if (stopped) return
    // Ran a good while: a one-off. Quit at once again and again: back off.
    if (Date.now() - startedAt > 10 * 60_000) restarts = 0
    const wait = RESTART_MS[Math.min(restarts, RESTART_MS.length - 1)]
    restarts++
    setTimeout(start, wait)
  })
}

export function setupMusic(): void {
  if (process.platform !== 'win32') return
  ipcMain.handle('music:current', () => current)
  start()
  app.on('will-quit', stopMusic)
}

export function stopMusic(): void {
  stopped = true
  child?.kill()
  child = null
}
