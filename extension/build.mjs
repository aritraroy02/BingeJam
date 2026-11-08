// Builds the BingeJam browser extension. Plain Node, no dependencies.
//
//   node extension/build.mjs        (also run by `npm run dev` / `npm run build` in desktop/)
//
// Output (extension/dist/, git-ignored):
//   chromium/                     Load unpacked in Chrome, Edge, Brave, Opera. Keeps "key" = fixed id.
//   firefox/                      about:debugging → Load Temporary Add-on (pick manifest.json).
//   chromium.json                 { version, build } so the desktop app knows when to refresh its copy.
//   bingejam-edge-<v>.zip         Edge Add-ons upload (no "key": the store assigns its own id).
//   bingejam-firefox-<v>.zip      Mozilla signing upload (AMO, unlisted).
//
// The fixed id: src/manifest.json "key" is the public half of an RSA-2048 key pair generated once with
// Node's crypto.generateKeyPairSync; the private half was never written anywhere (an unpacked extension
// needs only the public key, and the stores sign with their own keys). Chromium's id is the first 32 hex
// digits of SHA-256(public key DER) with 0-f mapped to a-p; this script recomputes it and fails if it no
// longer matches EXTENSION_ID_UNPACKED in desktop/src/shared/extension.ts (the app allows only that id).
import { createHash } from 'node:crypto'
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'
import { deflateRawSync, crc32 } from 'node:zlib'

const here = dirname(fileURLToPath(import.meta.url))
const src = join(here, 'src')
const dist = join(here, 'dist')
const fonts = join(here, '..', 'web', 'fonts')
const sharedIds = join(here, '..', 'desktop', 'src', 'shared', 'extension.ts')

const manifest = JSON.parse(readFileSync(join(src, 'manifest.json'), 'utf8'))
const version = manifest.version

// ---------- id check ----------
function chromiumId(keyBase64) {
  const hex = createHash('sha256').update(Buffer.from(keyBase64, 'base64')).digest('hex').slice(0, 32)
  return [...hex].map((c) => String.fromCharCode(97 + parseInt(c, 16))).join('')
}
const id = chromiumId(manifest.key)
const expectedId = /EXTENSION_ID_UNPACKED\s*=\s*'([a-p]{32})'/.exec(readFileSync(sharedIds, 'utf8'))?.[1]
if (id !== expectedId) {
  console.error(`Extension id ${id} (from manifest "key") != EXTENSION_ID_UNPACKED ${expectedId}`)
  process.exit(1)
}
const geckoId = manifest.browser_specific_settings?.gecko?.id
const expectedGecko = /EXTENSION_ID_FIREFOX\s*=\s*'([^']+)'/.exec(readFileSync(sharedIds, 'utf8'))?.[1]
if (geckoId !== expectedGecko) {
  console.error(`Firefox id ${geckoId} != EXTENSION_ID_FIREFOX ${expectedGecko}`)
  process.exit(1)
}

// ---------- files ----------
function listFiles(dir, base = dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name)
    return statSync(p).isDirectory() ? listFiles(p, base) : [relative(base, p).split('\\').join('/')]
  })
}

// One build id for the content: changes whenever any shipped file changes.
const hash = createHash('sha256')
for (const f of listFiles(src).sort()) hash.update(f).update(readFileSync(join(src, f)))
const build = `${version}-${hash.digest('hex').slice(0, 10)}`

function variant(target, { keepKey }) {
  const m = structuredClone(manifest)
  if (target === 'chromium') {
    // Chrome < 121 rejects background.scripts in MV3; Chromium doesn't know browser_specific_settings.
    delete m.background.scripts
    delete m.browser_specific_settings
  } else {
    // Firefox runs background.scripts as an event page and doesn't use "key" / minimum_chrome_version.
    delete m.background.service_worker
    delete m.minimum_chrome_version
    delete m.key
  }
  if (!keepKey) delete m.key
  return m
}

function writeTarget(target) {
  const out = join(dist, target)
  mkdirSync(out, { recursive: true })
  for (const f of listFiles(src)) {
    if (f === 'manifest.json') continue
    mkdirSync(dirname(join(out, f)), { recursive: true })
    cpSync(join(src, f), join(out, f))
  }
  mkdirSync(join(out, 'fonts'), { recursive: true })
  cpSync(join(fonts, 'inter-latin.woff2'), join(out, 'fonts', 'inter-latin.woff2'))
  cpSync(join(fonts, 'Inter-OFL.txt'), join(out, 'fonts', 'Inter-OFL.txt'))
  writeFileSync(join(out, 'build.json'), JSON.stringify({ version, build }) + '\n')
  writeFileSync(join(out, 'manifest.json'), JSON.stringify(variant(target, { keepKey: true }), null, 2) + '\n')
  return out
}

// ---------- zip (stored paths with forward slashes, as the stores require) ----------
function zip(dir, file, manifestOverride) {
  const entries = []
  let offset = 0
  const chunks = []
  for (const name of listFiles(dir).sort()) {
    const data = name === 'manifest.json' && manifestOverride
      ? Buffer.from(JSON.stringify(manifestOverride, null, 2) + '\n')
      : readFileSync(join(dir, name))
    const deflated = deflateRawSync(data, { level: 9 })
    const useDeflate = deflated.length < data.length
    const body = useDeflate ? deflated : data
    const nameBuf = Buffer.from(name, 'utf8')
    const crc = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4) // version needed
    local.writeUInt16LE(0x0800, 6) // UTF-8 names
    local.writeUInt16LE(useDeflate ? 8 : 0, 8)
    local.writeUInt16LE(0, 10) // time
    local.writeUInt16LE(0x21, 12) // date 1980-01-01 (fixed: reproducible zips)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(body.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBuf.length, 26)
    local.writeUInt16LE(0, 28)
    chunks.push(local, nameBuf, body)
    entries.push({ nameBuf, crc, size: data.length, csize: body.length, method: useDeflate ? 8 : 0, offset })
    offset += local.length + nameBuf.length + body.length
  }
  const central = []
  for (const e of entries) {
    const c = Buffer.alloc(46)
    c.writeUInt32LE(0x02014b50, 0)
    c.writeUInt16LE(20, 4)
    c.writeUInt16LE(20, 6)
    c.writeUInt16LE(0x0800, 8)
    c.writeUInt16LE(e.method, 10)
    c.writeUInt16LE(0, 12)
    c.writeUInt16LE(0x21, 14)
    c.writeUInt32LE(e.crc, 16)
    c.writeUInt32LE(e.csize, 20)
    c.writeUInt32LE(e.size, 24)
    c.writeUInt16LE(e.nameBuf.length, 28)
    c.writeUInt32LE(e.offset, 42)
    central.push(c, e.nameBuf)
  }
  const centralBuf = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(centralBuf.length, 12)
  end.writeUInt32LE(offset, 16)
  writeFileSync(file, Buffer.concat([...chunks, centralBuf, end]))
}

// ---------- build ----------
if (!existsSync(join(fonts, 'inter-latin.woff2'))) {
  console.error(`Missing ${join(fonts, 'inter-latin.woff2')}`)
  process.exit(1)
}
rmSync(dist, { recursive: true, force: true })
const chromium = writeTarget('chromium')
const firefox = writeTarget('firefox')
writeFileSync(join(dist, 'chromium.json'), JSON.stringify({ version, build, id }) + '\n')
zip(chromium, join(dist, `bingejam-edge-${version}.zip`), variant('chromium', { keepKey: false }))
zip(firefox, join(dist, `bingejam-firefox-${version}.zip`))
console.log(`BingeJam extension ${build} → ${relative(process.cwd(), dist) || dist} (id ${id})`)
