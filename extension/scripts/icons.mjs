// Renders the extension icons (16/32/48/128 PNG) from the BingeJam logo with headless Edge or Chrome.
//   node extension/scripts/icons.mjs
// Logo = desktop/src/renderer/src/ui/Icon.tsx LogoMark on the brand gradient #FF2E4D → #C2410C.
// Uses the DevTools protocol (Node's built-in WebSocket) so the corners come out transparent.
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'icons')
const BROWSERS = [
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe'
]
const exe = process.env.BROWSER ?? BROWSERS.find(existsSync)
if (!exe) throw new Error('No Edge/Chrome found; set BROWSER=path\\to\\browser.exe')

const GLYPH =
  '<path fill="#fff" d="M24,8V2L16,10l8,8V12c6.62,0 12,5.38 12,12 0,2.02 -0.5,3.94 -1.4,5.6l2.92,2.92C39.08,30.06 40,27.14 40,24c0,-8.84 -7.16,-16 -16,-16zM24,36c-6.62,0 -12,-5.38 -12,-12 0,-2.02 0.5,-3.94 1.4,-5.6L10.48,15.48C8.92,17.94 8,20.86 8,24c0,8.84 7.16,16 16,16v6l8,-8 -8,-8v6z"/><path fill="#fff" d="M20,17L30,24 20,31z"/>'

// size → tile inset, corner radius, glyph size. Small sizes get a bigger glyph so it stays readable;
// 128 keeps 8 px of air like the store guidelines ask.
const SPECS = {
  16: { inset: 0, radius: 4, glyph: 15 },
  32: { inset: 0, radius: 8, glyph: 27 },
  48: { inset: 1, radius: 11, glyph: 36 },
  128: { inset: 8, radius: 26, glyph: 80 }
}

function svg(size) {
  const { inset, radius, glyph } = SPECS[size]
  const tile = size - inset * 2
  const g = (size - glyph) / 2
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FF2E4D"/><stop offset="1" stop-color="#C2410C"/></linearGradient></defs>
<rect x="${inset}" y="${inset}" width="${tile}" height="${tile}" rx="${radius}" fill="url(#g)"/>
<svg x="${g}" y="${g}" width="${glyph}" height="${glyph}" viewBox="0 0 48 48">${GLYPH}</svg></svg>`
}

const profile = mkdtempSync(join(tmpdir(), 'bj-icons-'))
const port = 9300 + Math.floor(Math.random() * 500)
const proc = spawn(exe, ['--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, '--force-device-scale-factor=1', '--hide-scrollbars', '--no-first-run', 'about:blank'], { stdio: 'ignore' })

try {
  let target
  for (let i = 0; i < 50 && !target; i++) {
    await new Promise((r) => setTimeout(r, 200))
    try {
      target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === 'page')
    } catch {
      /* not up yet */
    }
  }
  if (!target) throw new Error('Browser did not start')
  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((r, j) => ((ws.onopen = r), (ws.onerror = j)))
  let seq = 0
  const pending = new Map()
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data)
    if (m.id && pending.has(m.id)) pending.get(m.id)(m)
  }
  const send = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++seq
      pending.set(id, (m) => (m.error ? reject(new Error(m.error.message)) : resolve(m.result)))
      ws.send(JSON.stringify({ id, method, params }))
    })

  await send('Page.enable')
  await send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } })
  mkdirSync(out, { recursive: true })
  for (const size of Object.keys(SPECS).map(Number)) {
    await send('Emulation.setDeviceMetricsOverride', { width: size, height: size, deviceScaleFactor: 1, mobile: false })
    const html = `<!doctype html><html><body style="margin:0;background:transparent;overflow:hidden"><style>svg{display:block}html{overflow:hidden}</style>${svg(size)}</body></html>`
    await send('Page.navigate', { url: 'data:text/html;base64,' + Buffer.from(html).toString('base64') })
    await new Promise((r) => setTimeout(r, 300))
    const { data } = await send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: 0, width: size, height: size, scale: 1 } })
    writeFileSync(join(out, `icon-${size}.png`), Buffer.from(data, 'base64'))
    console.log(`icon-${size}.png`)
  }
  ws.close()
} finally {
  proc.kill()
  await new Promise((r) => setTimeout(r, 500))
  rmSync(profile, { recursive: true, force: true, maxRetries: 5 })
}
