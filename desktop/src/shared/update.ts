// In-app updates for the PC app (2026-11-23): what the main process tells the UI, and the pure part
// of the check (pick the newest PC release from the releases repo). Tested in update.test.ts.
//
// Releases live in the public repo aritraroy02/sync-it-releases next to the phone's, tagged
// pc-vX.Y.Z with one asset BingeJam-Setup-X.Y.Z.exe; the phone's v-tags are ignored here.

export const RELEASES_API = 'https://api.github.com/repos/aritraroy02/sync-it-releases/releases?per_page=30'
/** Installers are only taken from this repo's release downloads. */
export const DOWNLOAD_PREFIX = 'https://github.com/aritraroy02/sync-it-releases/releases/download/'

export type UpdateState =
  | { status: 'idle' }
  | { status: 'checking' }
  /** Checked: this is the newest version. */
  | { status: 'current'; checkedAt: number }
  | { status: 'downloading'; version: string; progress: number; notes: string }
  /** Downloaded and checked: one click restarts into it. */
  | { status: 'ready'; version: string; notes: string }
  | { status: 'error'; message: string }

export interface PcRelease {
  version: string
  url: string
  size: number
  /** Lowercase hex; null when the release doesn't say (then only the size is checked). */
  sha256: string | null
  /** The release notes' first lines, for "What's new". */
  notes: string
}

/** -1, 0 or 1 for X.Y.Z versions; anything malformed counts as 0.0.0. */
export function compareVersions(a: string, b: string): number {
  const parse = (v: string): number[] => {
    const m = /^(\d+)\.(\d+)\.(\d+)$/.exec(v.trim())
    return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : [0, 0, 0]
  }
  const [x, y] = [parse(a), parse(b)]
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i] < y[i] ? -1 : 1
  return 0
}

function notesOf(body: string): string {
  return body
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !/^sha-256:/i.test(l) && !/^install:/i.test(l))
    .slice(0, 4)
    .join('\n')
    .slice(0, 600)
}

/**
 * The newest published PC release newer than [current], with its installer; null when there's none.
 * [downloads]: where installers may come from (only a local test feed passes another one).
 */
export function pickUpdate(releases: unknown, current: string, downloads = DOWNLOAD_PREFIX): PcRelease | null {
  if (!Array.isArray(releases)) return null
  let best: PcRelease | null = null
  for (const r of releases) {
    if (!r || typeof r !== 'object') continue
    const o = r as Record<string, unknown>
    if (o.draft === true || o.prerelease === true || typeof o.tag_name !== 'string') continue
    const version = /^pc-v(\d+\.\d+\.\d+)$/.exec(o.tag_name)?.[1]
    if (!version || compareVersions(version, current) <= 0) continue
    if (best && compareVersions(version, best.version) <= 0) continue
    const assets = Array.isArray(o.assets) ? (o.assets as Record<string, unknown>[]) : []
    const asset = assets.find((a) => a && a.name === `BingeJam-Setup-${version}.exe`)
    const url = asset?.browser_download_url
    const size = asset?.size
    if (typeof url !== 'string' || !url.startsWith(downloads) || typeof size !== 'number' || size <= 0) continue
    const body = typeof o.body === 'string' ? o.body : ''
    // GitHub's own digest when it has one, else the "SHA-256: …" line our release notes carry.
    const digest = typeof asset?.digest === 'string' ? /^sha256:([0-9a-f]{64})$/i.exec(asset.digest)?.[1] : undefined
    const sha256 = (digest ?? /SHA-256:\s*([0-9a-f]{64})/i.exec(body)?.[1] ?? null)?.toLowerCase() ?? null
    best = { version, url, size, sha256, notes: notesOf(body) }
  }
  return best
}
