// Which browsers are installed, and the "Add to browser" routes (PLAN.md "Browser extension"):
//   Edge      → Edge Add-ons listing (placeholder until published: Load unpacked meanwhile)
//   Firefox   → Mozilla-signed .xpi (placeholder until signed: temporary add-on via about:debugging)
//   Chrome, Brave, Opera → copy the extension to <userData>\extension, open the browser's extensions
//               page, put the folder path on the clipboard; the person does Developer mode → Load
//               unpacked → paste the path.
// Never installs through registry policies (Chrome treats that as malware).
import { clipboard } from 'electron'
import { execFile, spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { EXTENSION_STORE, type AddToBrowserResult, type BrowserId, type InstallRoute } from '@shared/extension'
import { syncInstallDir } from './bundle'

const run = promisify(execFile)

interface BrowserDef {
  id: BrowserId
  name: string
  appPaths: string[] // exe names under …\CurrentVersion\App Paths
  known: string[] // install locations relative to env roots
  extensionsPage: string
}

const DEFS: BrowserDef[] = [
  {
    id: 'edge',
    name: 'Microsoft Edge',
    appPaths: ['msedge.exe'],
    known: ['%ProgramFiles(x86)%\\Microsoft\\Edge\\Application\\msedge.exe', '%ProgramFiles%\\Microsoft\\Edge\\Application\\msedge.exe'],
    extensionsPage: 'edge://extensions/'
  },
  {
    id: 'chrome',
    name: 'Google Chrome',
    appPaths: ['chrome.exe'],
    known: [
      '%ProgramFiles%\\Google\\Chrome\\Application\\chrome.exe',
      '%ProgramFiles(x86)%\\Google\\Chrome\\Application\\chrome.exe',
      '%LOCALAPPDATA%\\Google\\Chrome\\Application\\chrome.exe'
    ],
    extensionsPage: 'chrome://extensions/'
  },
  {
    id: 'firefox',
    name: 'Firefox',
    appPaths: ['firefox.exe'],
    known: ['%ProgramFiles%\\Mozilla Firefox\\firefox.exe', '%ProgramFiles(x86)%\\Mozilla Firefox\\firefox.exe', '%LOCALAPPDATA%\\Mozilla Firefox\\firefox.exe'],
    extensionsPage: 'about:debugging#/runtime/this-firefox'
  },
  {
    id: 'brave',
    name: 'Brave',
    appPaths: ['brave.exe'],
    known: [
      '%ProgramFiles%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe',
      '%ProgramFiles(x86)%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe',
      '%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe'
    ],
    extensionsPage: 'brave://extensions/'
  },
  {
    id: 'opera',
    name: 'Opera',
    appPaths: ['opera.exe', 'launcher.exe'],
    known: [
      '%LOCALAPPDATA%\\Programs\\Opera\\launcher.exe',
      '%LOCALAPPDATA%\\Programs\\Opera\\opera.exe',
      '%LOCALAPPDATA%\\Programs\\Opera GX\\launcher.exe',
      '%ProgramFiles%\\Opera\\launcher.exe'
    ],
    extensionsPage: 'opera://extensions'
  }
]

const APP_PATH_ROOTS = [
  'HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths',
  'HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths',
  'HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths'
]

function expand(p: string): string {
  return p.replace(/%([^%]+)%/g, (_m, name: string) => process.env[name] ?? '')
}

async function appPath(exe: string): Promise<string | null> {
  for (const root of APP_PATH_ROOTS) {
    try {
      const { stdout } = await run('reg.exe', ['query', `${root}\\${exe}`, '/ve'], { windowsHide: true })
      const m = /REG_(?:EXPAND_)?SZ\s+(.*)$/m.exec(stdout)
      const value = m ? expand(m[1]!.trim().replace(/^"|"$/g, '')) : null
      if (value && existsSync(value)) {
        // Opera's App Paths entry may name launcher.exe or opera.exe: only trust it inside an Opera folder.
        if (exe === 'launcher.exe' && !/opera/i.test(value)) continue
        return value
      }
    } catch {
      /* not registered here */
    }
  }
  return null
}

export interface FoundBrowser {
  id: BrowserId
  name: string
  exe: string
}

let cache: { at: number; list: FoundBrowser[] } | null = null

export async function findBrowsers(): Promise<FoundBrowser[]> {
  if (process.platform !== 'win32') return []
  if (cache && Date.now() - cache.at < 10000) return cache.list
  const list: FoundBrowser[] = []
  for (const def of DEFS) {
    let exe: string | null = null
    for (const name of def.appPaths) exe ??= await appPath(name)
    exe ??= def.known.map(expand).find((p) => p && existsSync(p)) ?? null
    if (exe) list.push({ id: def.id, name: def.name, exe })
  }
  cache = { at: Date.now(), list }
  return list
}

export function routeFor(id: BrowserId): InstallRoute {
  if (id === 'edge' && EXTENSION_STORE.edgeAddonsUrl) return 'edge-store'
  if (id === 'firefox') return EXTENSION_STORE.firefoxXpiUrl ? 'firefox-xpi' : 'firefox-temporary'
  return 'unpacked'
}

/** Starts the browser with one URL (also works when it's already running: it opens a tab there). */
function launch(exe: string, url: string): Promise<boolean> {
  return new Promise((resolve) => {
    try {
      const child = spawn(exe, [url], { detached: true, stdio: 'ignore', windowsHide: false })
      child.once('error', () => resolve(false))
      child.once('spawn', () => {
        child.unref()
        resolve(true)
      })
    } catch {
      resolve(false)
    }
  })
}

/** connected: this browser's extension is linked to the app right now (ticks the last step). */
export async function addToBrowser(id: BrowserId, connected: boolean): Promise<AddToBrowserResult> {
  const route = routeFor(id)
  const fail = (error: string): AddToBrowserResult => ({ browser: id, route, ok: false, path: null, steps: [], error })
  const found = (await findBrowsers()).find((b) => b.id === id)
  const def = DEFS.find((d) => d.id === id)
  if (!found || !def) return fail(`${def?.name ?? id} isn't installed`)

  if (route === 'edge-store') {
    const opened = await launch(found.exe, EXTENSION_STORE.edgeAddonsUrl!)
    return { browser: id, route, ok: opened, path: null, steps: [{ label: 'Click Get on the Edge Add-ons page', done: connected }], error: opened ? null : 'Could not start Edge' }
  }

  if (route === 'firefox-xpi') {
    // A local .xpi or an https URL: Firefox shows its own "Add BingeJam?" prompt.
    const opened = await launch(found.exe, EXTENSION_STORE.firefoxXpiUrl!)
    return { browser: id, route, ok: opened, path: null, steps: [{ label: 'Click Add in Firefox', done: connected }], error: opened ? null : 'Could not start Firefox' }
  }

  if (route === 'firefox-temporary') {
    const dir = syncInstallDir('firefox')
    if (!dir) return fail('The extension isn\'t built yet (node extension/build.mjs)')
    const manifest = join(dir, 'manifest.json')
    clipboard.writeText(manifest)
    const opened = await launch(found.exe, def.extensionsPage)
    return {
      browser: id,
      route,
      ok: opened,
      path: manifest,
      steps: [
        { label: 'Click Load Temporary Add-on…', done: connected },
        { label: 'Pick manifest.json (path copied)', done: connected },
        { label: 'Firefox removes it when it closes, until the signed version is out', done: false }
      ],
      error: opened ? null : 'Could not start Firefox'
    }
  }

  // Load unpacked (Chrome, Brave, Opera; Edge until its listing exists).
  const dir = syncInstallDir('chromium')
  if (!dir) return fail('The extension isn\'t built yet (node extension/build.mjs)')
  clipboard.writeText(dir)
  const opened = await launch(found.exe, def.extensionsPage)
  return {
    browser: id,
    route,
    ok: opened,
    path: dir,
    steps: [
      { label: 'Turn on Developer mode', done: connected },
      { label: 'Click Load unpacked', done: connected },
      { label: 'Pick the BingeJam folder (path copied)', done: connected }
    ],
    error: opened ? null : `Could not start ${def.name}`
  }
}
