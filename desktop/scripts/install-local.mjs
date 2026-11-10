// Installs the built app for this Windows user, without an installer or admin rights:
//   %LOCALAPPDATA%\Programs\BingeJam\BingeJam.exe   (Electron's runtime, renamed)
//     resources\app\{package.json, out\, build\icon.png}
//     resources\extension\{chromium, firefox}         (what "Add to browser" hands out)
// plus Start menu and desktop shortcuts. Run `npm run build` first (it also builds the extension).
//   node scripts/install-local.mjs              install / update
//   node scripts/install-local.mjs --uninstall  remove the app and shortcuts (keeps %APPDATA%\BingeJam)
// A real installer (electron-builder) comes with the first public release.

import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import { join, resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
const name = pkg.productName
const target = join(process.env.LOCALAPPDATA, 'Programs', name)
const exe = join(target, `${name}.exe`)
const shortcuts = [
  join(process.env.APPDATA, 'Microsoft', 'Windows', 'Start Menu', 'Programs', `${name}.lnk`),
  join(process.env.USERPROFILE, 'Desktop', `${name}.lnk`)
]

function powershell(script) {
  execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { stdio: 'inherit' })
}

function running() {
  try {
    return execFileSync('tasklist.exe', ['/FI', `IMAGENAME eq ${name}.exe`, '/NH'], { encoding: 'utf8' }).includes(`${name}.exe`)
  } catch {
    return false
  }
}

if (process.platform !== 'win32') throw new Error('Windows only')
if (running()) {
  console.error(`${name} is running. Quit it (tray / window) and run this again.`)
  process.exit(1)
}

if (process.argv.includes('--uninstall')) {
  rmSync(target, { recursive: true, force: true })
  for (const s of shortcuts) rmSync(s, { force: true })
  console.log(`Removed ${target} and its shortcuts. Settings in %APPDATA%\\${name} were kept.`)
  process.exit(0)
}

const out = join(root, 'out')
const runtime = join(root, 'node_modules', 'electron', 'dist')
const extension = join(root, '..', 'extension', 'dist')
for (const [what, dir] of [['out (npm run build)', join(out, 'main', 'index.js')], ['Electron', join(runtime, 'electron.exe')], ['the extension build', join(extension, 'chromium', 'manifest.json')]]) {
  if (!existsSync(dir)) throw new Error(`Missing ${what}: ${dir}`)
}

rmSync(target, { recursive: true, force: true })
cpSync(runtime, target, { recursive: true })
renameSync(join(target, 'electron.exe'), exe)
rmSync(join(target, 'resources', 'default_app.asar'), { force: true })

const app = join(target, 'resources', 'app')
mkdirSync(app, { recursive: true })
writeFileSync(
  join(app, 'package.json'),
  JSON.stringify({ name: pkg.name, productName: name, version: pkg.version, description: pkg.description, main: pkg.main }, null, 2)
)
cpSync(out, join(app, 'out'), { recursive: true })
cpSync(join(root, 'build', 'icon.png'), join(app, 'build', 'icon.png'))
for (const t of ['chromium', 'firefox']) cpSync(join(extension, t), join(target, 'resources', 'extension', t), { recursive: true })
cpSync(join(root, 'build', 'icon.ico'), join(target, 'icon.ico'))

const ps = (s) => `'${s.replace(/'/g, "''")}'`
powershell(
  [
    '$w = New-Object -ComObject WScript.Shell',
    ...shortcuts.map(
      (s) =>
        `$l = $w.CreateShortcut(${ps(s)}); $l.TargetPath = ${ps(exe)}; $l.WorkingDirectory = ${ps(target)}; $l.IconLocation = ${ps(join(target, 'icon.ico'))}; $l.Description = ${ps(pkg.description)}; $l.Save()`
    )
  ].join('; ')
)
console.log(`Installed ${name} ${pkg.version} to ${target}`)
