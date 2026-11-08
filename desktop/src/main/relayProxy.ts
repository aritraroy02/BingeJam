import { ipcMain } from 'electron'

// Calls to the push relay (push-relay/, Cloudflare Worker) go through the main process. The relay was
// written for the phone and answers no CORS preflight, so Chromium blocks a renderer fetch that carries
// an Authorization header. Node's fetch has no CORS. This proxy only ever reaches the relay itself.

const RELAY_ORIGIN = 'https://vyro-push-relay.aritraroy02.workers.dev'
const ALLOWED_HEADERS = new Set(['authorization', 'content-type'])
const MAX_BODY_BYTES = 64 * 1024
const MAX_RESPONSE_BYTES = 2 * 1024 * 1024
const TIMEOUT_MS = 15_000

export interface RelayRequest {
  method: 'GET' | 'POST'
  url: string
  headers: Record<string, string>
  body?: string
}

export interface RelayResponse {
  status: number
  body: string
}

function validate(input: unknown): RelayRequest {
  const r = input as Partial<RelayRequest> | null
  if (!r || (r.method !== 'GET' && r.method !== 'POST')) throw new Error('bad method')
  if (typeof r.url !== 'string' || new URL(r.url).origin !== RELAY_ORIGIN) throw new Error('not the relay')
  const headers: Record<string, string> = {}
  for (const [k, v] of Object.entries(r.headers ?? {})) {
    if (ALLOWED_HEADERS.has(k.toLowerCase()) && typeof v === 'string') headers[k] = v
  }
  if (r.body !== undefined && (typeof r.body !== 'string' || Buffer.byteLength(r.body) > MAX_BODY_BYTES)) throw new Error('bad body')
  return { method: r.method, url: r.url, headers, body: r.body }
}

export function registerRelayProxy(): void {
  ipcMain.handle('relay:fetch', async (_event, input: unknown): Promise<RelayResponse> => {
    const r = validate(input)
    const res = await fetch(r.url, { method: r.method, headers: r.headers, body: r.body, signal: AbortSignal.timeout(TIMEOUT_MS) })
    const text = await res.text()
    if (Buffer.byteLength(text) > MAX_RESPONSE_BYTES) throw new Error('relay response too large')
    return { status: res.status, body: text }
  })
}
