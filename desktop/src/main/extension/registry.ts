// Registers the native messaging host for this Windows user (HKCU only, no admin, no policies).
// Files go to <userData>\native-host\: the .cmd launcher, host-config.json and one host manifest per
// browser family. Runs on every app start and only writes what changed, so moving or updating the app
// just repoints the registration.
import { app } from 'electron'
import { execFile } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { EXTENSION_ID_EDGE_STORE, EXTENSION_ID_FIREFOX, EXTENSION_ID_UNPACKED, NATIVE_HOST_NAME } from '@shared/extension'
import { pipePath, type HostConfig } from './pipe'

const run = promisify(execFile)

/** Registry keys browsers read. Chrome's key is also read by Brave, Opera and Vivaldi on Windows. */
const KEYS: { key: string; family: 'chromium' | 'firefox' }[] = [
  { key: `HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\${NATIVE_HOST_NAME}`, family: 'chromium' },
  { key: `HKCU\\Software\\Microsoft\\Edge\\NativeMessagingHosts\\${NATIVE_HOST_NAME}`, family: 'chromium' },
  { key: `HKCU\\Software\\Chromium\\NativeMessagingHosts\\${NATIVE_HOST_NAME}`, family: 'chromium' },
  // Harmless if Brave only reads Chrome's key (it does on current builds).
  { key: `HKCU\\Software\\BraveSoftware\\Brave-Browser\\NativeMessagingHosts\\${NATIVE_HOST_NAME}`, family: 'chromium' },
  { key: `HKCU\\Software\\Mozilla\\NativeMessagingHosts\\${NATIVE_HOST_NAME}`, family: 'firefox' }
]

export function chromiumOrigins(): string[] {
  return [EXTENSION_ID_UNPACKED, EXTENSION_ID_EDGE_STORE]
    .filter((id): id is string => !!id)
    .map((id) => `chrome-extension://${id}/`)
}

/** Where the built host script is: next to the main bundle (out/main/native-host.js). */
function hostScript(): string {
  return join(__dirname, 'native-host.js')
}

function writeIfChanged(file: string, content: string): void {
  try {
    if (existsSync(file) && readFileSync(file, 'utf8') === content) return
  } catch {
    /* rewrite */
  }
  writeFileSync(file, content, 'utf8')
}

/** cmd.exe expands %VAR% even inside quotes; double the % in literal paths. */
const cmdQuote = (s: string): string => `"${s.replace(/%/g, '%%')}"`

async function regDefault(key: string): Promise<string | null> {
  try {
    const { stdout } = await run('reg.exe', ['query', key, '/ve'], { windowsHide: true })
    const m = /REG_SZ\s+(.*)$/m.exec(stdout)
    return m ? m[1]!.trim() : null
  } catch {
    return null
  }
}

export interface Registration {
  dir: string
  manifests: { chromium: string; firefox: string }
}

export async function registerNativeHost(): Promise<Registration> {
  if (process.platform !== 'win32') throw new Error('Native host registration is Windows-only for now')
  const dir = join(app.getPath('userData'), 'native-host')
  mkdirSync(dir, { recursive: true })

  const config: HostConfig = {
    pipe: pipePath(),
    allowed: [...chromiumOrigins(), EXTENSION_ID_FIREFOX],
    // Dev runs Electron with the app folder; a packaged app is just its exe.
    app: { exe: process.execPath, args: process.defaultApp ? [app.getAppPath()] : [] }
  }
  writeIfChanged(join(dir, 'host-config.json'), JSON.stringify(config, null, 2) + '\n')

  // The launcher: the app's Electron binary as plain Node, running the host script. Browsers start
  // .cmd hosts hidden (no console window). Nothing may print to stdout before the host takes over.
  const launcher = join(dir, 'bingejam-host.cmd')
  writeIfChanged(
    launcher,
    [
      '@echo off',
      'set ELECTRON_RUN_AS_NODE=1',
      `${cmdQuote(process.execPath)} ${cmdQuote(hostScript())} "%~dp0host-config.json" %*`,
      ''
    ].join('\r\n')
  )

  const description = 'BingeJam: links the browser extension to the BingeJam app'
  const chromium = join(dir, `${NATIVE_HOST_NAME}.chromium.json`)
  const firefox = join(dir, `${NATIVE_HOST_NAME}.firefox.json`)
  writeIfChanged(
    chromium,
    JSON.stringify({ name: NATIVE_HOST_NAME, description, path: launcher, type: 'stdio', allowed_origins: chromiumOrigins() }, null, 2) + '\n'
  )
  writeIfChanged(
    firefox,
    JSON.stringify({ name: NATIVE_HOST_NAME, description, path: launcher, type: 'stdio', allowed_extensions: [EXTENSION_ID_FIREFOX] }, null, 2) + '\n'
  )

  for (const { key, family } of KEYS) {
    const value = family === 'chromium' ? chromium : firefox
    if ((await regDefault(key)) === value) continue
    await run('reg.exe', ['add', key, '/ve', '/t', 'REG_SZ', '/d', value, '/f'], { windowsHide: true })
  }
  return { dir, manifests: { chromium, firefox } }
}

/** For an uninstaller (not wired yet): removes the HKCU keys. */
export async function unregisterNativeHost(): Promise<void> {
  for (const { key } of KEYS) {
    try {
      await run('reg.exe', ['delete', key, '/f'], { windowsHide: true })
    } catch {
      /* not there */
    }
  }
}
