import { push, ref, serverTimestamp, update, get } from 'firebase/database'
import { db } from '@/lib/firebase'
import { requireUid } from './live.ts'

// Crash reports from the PC (2026-11-12), into the same encrypted crashes/ the phone uses (App reports).
// Caught here: errors and rejected promises in the window. Each report waits in localStorage (at most
// 10, 7 days) until we're signed in and a staff phone has published its key, then goes up encrypted:
// gzip → AES-256-GCM → the AES key wrapped with RSA-OAEP (SHA-1) for every staff key. "v": 2 tells the
// phone to unwrap with SHA-1 OAEP (Web Crypto can't use the phone's SHA-256 + MGF1-SHA-1 mix).

const STORE = 'bj.crashes.pending'
const MAX_PENDING = 10
const KEEP_MS = 7 * 24 * 60 * 60 * 1000
const MAX_LOG_LINES = 200

let screenName = 'Start'
const logs: string[] = []

export function setCrashScreen(name: string): void {
  screenName = name
}

function scrub(text: string): string {
  return text
    .replace(/bearer\s+\S+/gi, 'Bearer [token]')
    .replace(/https?:\/\/\S+/g, '[link]')
    .replace(/[\w.+-]+@[\w-]+\.[\w.-]+/g, '[email]')
    .replace(/[A-Za-z0-9_\-:]{32,}/g, '[token]')
}

function time(): string {
  return new Date().toTimeString().slice(0, 8)
}

/** Keeps BingeJam's own warnings and errors (scrubbed), for the "logs before it happened" section. */
function captureConsole(): void {
  for (const level of ['warn', 'error'] as const) {
    const original = console[level].bind(console)
    console[level] = (...args: unknown[]) => {
      const line = args.map((a) => (a instanceof Error ? `${a.name}: ${a.message}` : typeof a === 'string' ? a : safeJson(a))).join(' ')
      logs.push(`${time()} ${level === 'warn' ? 'W' : 'E'} ${scrub(line).slice(0, 400)}`)
      if (logs.length > MAX_LOG_LINES) logs.shift()
      original(...args)
    }
  }
}

function safeJson(v: unknown): string {
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

async function sha1Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-1', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** "Error: …" plus the frames, with our own code first and long runs of library frames folded. */
function cleanStack(error: Error): { where: string; stack: string } {
  const lines = (error.stack ?? '').split('\n').slice(1).map((l) => l.trim())
  const ours = lines.find((l) => /\/src\/|renderer\/assets\/index/.test(l)) ?? lines[0] ?? ''
  return { where: scrub(ours.replace(/^at /, '')).slice(0, 200), stack: scrub([`${error.name}: ${error.message}`, ...lines.slice(0, 40).map((l) => `    ${l}`)].join('\n')).slice(0, 12_000) }
}

async function save(error: unknown, kind: string): Promise<void> {
  const err = error instanceof Error ? error : new Error(typeof error === 'string' ? error : safeJson(error))
  const { where, stack } = cleanStack(err)
  const title = err.name || 'Error'
  const report = {
    kind: 'crash',
    sig: (await sha1Hex(`crash|${title}|${where}`)).slice(0, 12),
    title,
    message: scrub(err.message ?? '').slice(0, 500),
    where,
    stack: `Source: ${kind}\n${stack}`.slice(0, 12_000),
    logs: logs.join('\n').slice(-24_000),
    screen: screenName.slice(0, 60),
    app: `desktop ${(await window.bingejam?.version?.().catch(() => '')) || ''}`.trim().slice(0, 20),
    code: 0,
    model: 'Windows PC',
    android: navigator.userAgent.match(/Windows NT [\d.]+/)?.[0] ?? 'Windows',
    at: Date.now()
  }
  try {
    const pending = load()
    pending.push(report)
    localStorage.setItem(STORE, JSON.stringify(pending.slice(-MAX_PENDING)))
  } catch {
    // Storage off: this one is lost.
  }
}

function load(): { at: number }[] {
  try {
    const list = JSON.parse(localStorage.getItem(STORE) ?? '[]') as { at: number }[]
    return list.filter((r) => Date.now() - r.at < KEEP_MS)
  } catch {
    return []
  }
}

/** Call once at start-up. */
export function installCrashCatcher(): void {
  captureConsole()
  window.addEventListener('error', (e) => void save(e.error ?? e.message, 'window error'))
  window.addEventListener('unhandledrejection', (e) => void save(e.reason, 'unhandled promise'))
}

function b64(bytes: ArrayBuffer | Uint8Array): string {
  const u = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes)
  let s = ''
  for (const b of u) s += String.fromCharCode(b)
  return btoa(s)
}

async function gzip(text: string): Promise<ArrayBuffer> {
  const stream = new Blob([text]).stream().pipeThrough(new CompressionStream('gzip'))
  return new Response(stream).arrayBuffer()
}

/** Sends the waiting reports, encrypted for every staff phone. Nothing goes up before a key exists. */
export async function uploadPendingCrashes(username: string | null): Promise<void> {
  const pending = load()
  if (pending.length === 0) return
  const uid = requireUid()
  const keysSnap = await get(ref(db, 'staffKeys'))
  const keys: { id: string; spki: string }[] = []
  keysSnap.forEach((person) => {
    person.forEach((k) => {
      const v = k.val() as { key?: unknown } | null
      if (k.key && typeof v?.key === 'string') keys.push({ id: k.key, spki: v.key })
    })
  })
  if (keys.length === 0) return
  const imported = await Promise.all(
    keys.slice(0, 10).map(async (k) => ({
      id: k.id,
      key: await crypto.subtle.importKey('spki', Uint8Array.from(atob(k.spki), (c) => c.charCodeAt(0)), { name: 'RSA-OAEP', hash: 'SHA-1' }, false, ['encrypt'])
    }))
  )
  const left = [...pending]
  while (left.length > 0) {
    const report = { ...left[0], uid, ...(username ? { username } : {}) }
    const aes = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt'])
    const iv = crypto.getRandomValues(new Uint8Array(12))
    const box = await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, aes, await gzip(JSON.stringify(report)))
    const raw = await crypto.subtle.exportKey('raw', aes)
    const wrapped: Record<string, string> = {}
    for (const k of imported) wrapped[k.id] = b64(await crypto.subtle.encrypt({ name: 'RSA-OAEP' }, k.key, raw))
    const id = push(ref(db, 'crashes')).key
    await update(ref(db), {
      [`crashes/${id}`]: { v: 2, iv: b64(iv), box: b64(box), keys: wrapped, at: left[0].at, uploadedAt: serverTimestamp(), uid },
      [`crashLimits/${uid}`]: serverTimestamp()
    })
    left.shift()
    try {
      localStorage.setItem(STORE, JSON.stringify(left))
    } catch {
      // Storage off: it may go up twice; harmless.
    }
    if (left.length > 0) await new Promise((r) => setTimeout(r, 6_000))
  }
}
