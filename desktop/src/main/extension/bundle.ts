// The extension build the app ships, and the copy it keeps for "Load unpacked".
//   dev:      <repo>\extension\dist\{chromium,firefox}   (built by extension/build.mjs; npm run dev does it)
//   packaged: <resources>\extension\{chromium,firefox}   (packaging adds it later as extraResources)
//   override: BJ_EXTENSION_DIST=<folder holding chromium/ and firefox/>
// The browser loads the copy in <userData>\extension (Chromium) / <userData>\extension-firefox, so
// the folder the person picked once stays the same while the app rewrites it on updates.
import { app } from 'electron'
import { cpSync, existsSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

export type Target = 'chromium' | 'firefox'

export interface BuildInfo {
  version: string
  build: string
}

function distRoots(): string[] {
  const roots: string[] = []
  const env = process.env['BJ_EXTENSION_DIST']
  if (env) roots.push(env)
  if (app.isPackaged) roots.push(join(process.resourcesPath, 'extension'))
  roots.push(join(app.getAppPath(), '..', 'extension', 'dist'))
  return roots
}

export function builtDir(target: Target): string | null {
  for (const root of distRoots()) {
    const dir = join(root, target)
    if (existsSync(join(dir, 'manifest.json'))) return dir
  }
  return null
}

export function readBuild(dir: string | null): BuildInfo | null {
  if (!dir) return null
  try {
    const info = JSON.parse(readFileSync(join(dir, 'build.json'), 'utf8')) as Partial<BuildInfo>
    return typeof info.version === 'string' && typeof info.build === 'string' ? { version: info.version, build: info.build } : null
  } catch {
    return null
  }
}

export function bundledBuild(): BuildInfo | null {
  return readBuild(builtDir('chromium'))
}

export function installDir(target: Target): string {
  return join(app.getPath('userData'), target === 'chromium' ? 'extension' : 'extension-firefox')
}

/**
 * Makes the userData copy match the shipped build. Returns the folder, or null if nothing is built.
 * onlyIfPresent: on app start, refresh a copy the person already uses but don't create one.
 */
export function syncInstallDir(target: Target, onlyIfPresent = false): string | null {
  const from = builtDir(target)
  const to = installDir(target)
  if (!from) return existsSync(join(to, 'manifest.json')) ? to : null
  if (onlyIfPresent && !existsSync(to)) return null
  const want = readBuild(from)
  const have = readBuild(to)
  if (want && have && want.build === have.build) return to
  // Rewrite in place (same folder path): the browser keeps pointing at it.
  mkdirSync(to, { recursive: true })
  for (const name of ['background.js', 'content.js', 'popup.html', 'popup.css', 'popup.js', 'icons', 'fonts']) {
    rmSync(join(to, name), { recursive: true, force: true })
  }
  cpSync(from, to, { recursive: true, force: true })
  writeFileSync(join(to, 'README.txt'), 'BingeJam browser extension. The BingeJam app keeps this folder up to date; don\'t edit it.\r\n')
  return to
}
