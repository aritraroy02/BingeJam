// BingeJam native messaging host (built to out/main/native-host.js).
//
// The browser starts it through native-host\bingejam-host.cmd, which runs the app's own Electron binary
// with ELECTRON_RUN_AS_NODE=1 (the app itself can't be the host: its single-instance lock would hand
// the launch to the running window and exit). It is a dumb relay:
//
//   browser ⇄ stdin/stdout (native messaging: 4-byte little-endian length + UTF-8 JSON)
//           ⇄ host ⇄ \\.\pipe\bingejam-<user> (one JSON per line) ⇄ the running app
//
// It lives while the browser keeps the port open (stdin EOF = exit), retries the pipe while the app is
// closed and tells the extension { kind: 'link.host', appLinked }. Plain Node only: no 'electron'.
import { spawn } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { connect, type Socket } from 'node:net'
import { lineReader, toLine, type HostConfig } from './pipe'

const MAX_FROM_BROWSER = 64 * 1024 * 1024 // Chromium's limit browser → host
const MAX_TO_BROWSER = 1024 * 1024 // Chromium's limit host → browser
const RETRY_MS = [500, 1000, 2000, 3000, 5000]

const log = (...a: unknown[]): void => {
  // stderr only: stdout belongs to the browser.
  process.stderr.write(`[bingejam-host] ${a.map(String).join(' ')}\n`)
}

// argv: [exe, script, configPath, ...browser args]. Chromium passes "chrome-extension://<id>/"
// (+ --parent-window=N); Firefox passes the host manifest path and the extension id.
const [configPath, ...browserArgs] = process.argv.slice(2)
let config: HostConfig
try {
  config = JSON.parse(readFileSync(configPath ?? '', 'utf8')) as HostConfig
} catch (e) {
  log('no config', configPath, e)
  process.exit(1)
}

// The browser already enforces allowed_origins / allowed_extensions; this is a second check.
const caller = browserArgs.find((a) => config.allowed.includes(a))
if (!caller) {
  log('caller not allowed:', browserArgs.join(' '))
  process.exit(1)
}

// ---------- browser side ----------
function toBrowser(msg: unknown): void {
  const json = Buffer.from(JSON.stringify(msg), 'utf8')
  if (json.length > MAX_TO_BROWSER) {
    log('dropped message over 1 MB')
    return
  }
  const header = Buffer.alloc(4)
  header.writeUInt32LE(json.length, 0)
  process.stdout.write(Buffer.concat([header, json]))
}

let input = Buffer.alloc(0)
process.stdin.on('data', (chunk: Buffer) => {
  input = Buffer.concat([input, chunk])
  while (input.length >= 4) {
    const len = input.readUInt32LE(0)
    if (len > MAX_FROM_BROWSER) {
      log('frame too large; exiting')
      process.exit(1)
    }
    if (input.length < 4 + len) break
    const body = input.subarray(4, 4 + len)
    input = input.subarray(4 + len)
    try {
      fromBrowser(JSON.parse(body.toString('utf8')))
    } catch {
      log('bad frame')
    }
  }
})
// The browser closed the port (tab/popup gone, extension idle, browser quit): we're done.
process.stdin.on('end', () => process.exit(0))
process.stdin.on('close', () => process.exit(0))

function fromBrowser(msg: unknown): void {
  if (!msg || typeof msg !== 'object') return
  const kind = (msg as { kind?: unknown }).kind
  if (kind === 'link.openApp' && !linked) {
    launchApp()
    return
  }
  // Forwarded in the same tick: no delay to add to a snapshot's ageMs. While the app is closed,
  // messages are dropped; the extension resends its state when we report appLinked.
  if (linked && socket) socket.write(toLine(msg))
}

// ---------- app side ----------
let socket: Socket | null = null
let linked = false
let attempt = 0
let reported: boolean | null = null

function report(appLinked: boolean): void {
  if (reported === appLinked) return
  reported = appLinked
  toBrowser({ kind: 'link.host', appLinked })
}

function connectPipe(): void {
  const s = connect(config.pipe)
  socket = s
  s.on('connect', () => {
    linked = true
    attempt = 0
    report(true)
  })
  s.on('data', lineReader(
    (msg) => toBrowser(msg),
    () => log('pipe line too long')
  ))
  s.on('error', () => {
    /* close follows */
  })
  s.on('close', () => {
    if (socket !== s) return
    socket = null
    linked = false
    report(false)
    setTimeout(connectPipe, RETRY_MS[Math.min(attempt++, RETRY_MS.length - 1)])
  })
}

let launchedAt = 0
function launchApp(): void {
  const now = Date.now()
  if (now - launchedAt < 10000) return // one start per 10 s, however often "Open app" is pressed
  launchedAt = now
  const env = { ...process.env }
  delete env['ELECTRON_RUN_AS_NODE'] // the app must start as Electron, not as Node
  try {
    const child = spawn(config.app.exe, config.app.args, { detached: true, stdio: 'ignore', env })
    child.on('error', (e) => log('launch failed', e))
    child.unref()
  } catch (e) {
    log('launch failed', e)
  }
}

connectPipe()
