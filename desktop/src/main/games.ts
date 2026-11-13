import { execFile } from 'node:child_process'
import { BrowserWindow, ipcMain } from 'electron'
import { detectGame, type RunningProcess } from '@shared/games'

// "Playing VALORANT", like Discord: every POLL_MS the running programs (name + path) are matched
// against known games and game library folders (src/shared/games.ts). Only the game's name ever
// leaves this PC, and only when the person shares their activity (the renderer decides).

const POLL_MS = 20_000

let current: { name: string; since: number } | null = null
let timer: NodeJS.Timeout | null = null
let running = false

function listProcesses(): Promise<RunningProcess[]> {
  return new Promise((resolve) => {
    // Win32_Process gives every process's executable path (Get-Process doesn't for elevated ones).
    const script =
      "Get-CimInstance Win32_Process -Property Name,ExecutablePath | ForEach-Object { $_.Name + '|' + $_.ExecutablePath }"
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, timeout: 15_000, maxBuffer: 4 * 1024 * 1024 }, (err, stdout) => {
      if (err) return resolve([])
      resolve(
        stdout
          .split(/\r?\n/)
          .filter(Boolean)
          .map((line) => {
            const i = line.indexOf('|')
            return { name: line.slice(0, i), path: line.slice(i + 1) || null }
          })
      )
    })
  })
}

function broadcast(): void {
  for (const w of BrowserWindow.getAllWindows()) w.webContents.send('games:current', current)
}

async function poll(): Promise<void> {
  if (running) return
  running = true
  try {
    const game = detectGame(await listProcesses())
    if ((game ?? null) !== (current?.name ?? null)) {
      // Date.now() is display-only here ("for 20 min"); nothing syncs on it.
      current = game ? { name: game, since: Date.now() } : null
      broadcast()
    }
  } finally {
    running = false
  }
}

export function setupGames(): void {
  if (process.platform !== 'win32') return
  ipcMain.handle('games:current', () => current)
  void poll()
  timer = setInterval(() => void poll(), POLL_MS)
}

export function stopGames(): void {
  if (timer) clearInterval(timer)
  timer = null
}
