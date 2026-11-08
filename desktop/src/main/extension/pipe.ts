// Helpers shared by the app's pipe server and the native messaging host. Plain Node: the host runs
// under ELECTRON_RUN_AS_NODE, so nothing here may import 'electron'.
import { userInfo } from 'node:os'

/** \\.\pipe\bingejam-<user>: one per Windows user (pipes are machine-wide). */
export function pipePath(): string {
  let user = 'user'
  try {
    user = userInfo().username || user
  } catch {
    /* keep default */
  }
  return `\\\\.\\pipe\\bingejam-${user.replace(/[^A-Za-z0-9_.-]/g, '_')}`
}

/** Pipe frames: one JSON object per line (JSON.stringify never emits a raw newline). */
export const MAX_LINE = 2 * 1024 * 1024

export function lineReader(onLine: (value: unknown) => void, onTooLong: () => void): (chunk: Buffer) => void {
  let buf = ''
  return (chunk) => {
    buf += chunk.toString('utf8')
    let nl: number
    while ((nl = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, nl)
      buf = buf.slice(nl + 1)
      if (!line.trim()) continue
      try {
        onLine(JSON.parse(line))
      } catch {
        /* skip a bad line */
      }
    }
    if (buf.length > MAX_LINE) {
      buf = ''
      onTooLong()
    }
  }
}

export function toLine(value: unknown): string {
  return JSON.stringify(value) + '\n'
}

/** What the app writes next to the host launcher (native-host/host-config.json). */
export interface HostConfig {
  pipe: string
  /** chrome-extension://<id>/ origins and Firefox extension ids the host accepts. */
  allowed: string[]
  /** How to start the app when the popup's "Open app" is pressed and the app isn't running. */
  app: { exe: string; args: string[] }
}
