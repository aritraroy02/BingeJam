import { execFile } from 'node:child_process'
import { release } from 'node:os'
import { ipcMain } from 'electron'

// This PC as the owner and developers see it in App reports (2026-11-25): the hardware model and the
// Windows edition ("ASUS TUF Gaming A15", "Windows 11 Pro"). Never the computer's own name, which
// often carries a person's name.

export interface PcDevice {
  name: string
  os: string
}

let cached: Promise<PcDevice> | null = null

function read(): Promise<PcDevice> {
  const fallback: PcDevice = { name: 'Windows PC', os: `Windows ${release()}`.slice(0, 60) }
  if (process.platform !== 'win32') return Promise.resolve({ name: 'Computer', os: `${process.platform} ${release()}`.slice(0, 60) })
  return new Promise((resolve) => {
    const script =
      "$c = Get-CimInstance Win32_ComputerSystem -Property Manufacturer,Model; $p = Get-CimInstance Win32_ComputerSystemProduct -Property Version; $o = Get-CimInstance Win32_OperatingSystem -Property Caption; $c.Manufacturer + '|' + $c.Model + '|' + $o.Caption + '|' + $p.Version"
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script], { windowsHide: true, timeout: 15_000 }, (err, stdout) => {
      if (err) return resolve(fallback)
      const [maker = '', model = '', caption = '', product = ''] = stdout.trim().split('|').map((s) => s.trim())
      // Lenovo puts a machine-type code in Model ("82EY") and the real name in the product version.
      const friendly = /^lenovo/i.test(maker) && product && !/^(none|0|default string)$/i.test(product) ? titleCase(product) : model
      resolve({ name: deviceName(maker, friendly) || fallback.name, os: caption.replace(/^Microsoft\s+/i, '').slice(0, 60) || fallback.os })
    })
  })
}

/** "ASUS TUF Gaming A15": the maker once, without "Inc." and the like; placeholders dropped. */
export function deviceName(maker: string, model: string): string {
  const placeholder = /^(to be filled by o\.?e\.?m\.?|system product name|default string|system manufacturer|o\.?e\.?m\.?)$/i
  const m = placeholder.test(maker) ? '' : maker.replace(/,?\s*(inc\.?|corporation|corp\.?|co\.,? ltd\.?|ltd\.?|computer inc\.?)$/i, '').trim()
  const short = /^asustek/i.test(m)
    ? 'ASUS'
    : /^micro-star/i.test(m)
      ? 'MSI'
      : /^hewlett-packard|^hp$/i.test(m)
        ? 'HP'
        : /^lenovo$/i.test(m)
          ? 'Lenovo'
          : /^dell$/i.test(m)
            ? 'Dell'
            : m
  const mod = placeholder.test(model) ? '' : model.trim()
  if (!mod) return short.slice(0, 80)
  return (mod.toLowerCase().startsWith(short.toLowerCase()) || !short ? mod : `${short} ${mod}`).slice(0, 80)
}

/** "IDEAPAD GAMING 3-15ARH05" → "IdeaPad Gaming 3-15ARH05": words in capitals read better; codes stay. */
function titleCase(text: string): string {
  return text
    .split(' ')
    .map((w) => (/^[A-Z]{3,}$/.test(w) ? (w === 'IDEAPAD' ? 'IdeaPad' : w === 'THINKPAD' ? 'ThinkPad' : w.charAt(0) + w.slice(1).toLowerCase()) : w))
    .join(' ')
}

export function setupDeviceInfo(): void {
  ipcMain.handle('system:device', () => (cached ??= read()))
}
