import { desktopCapturer, ipcMain, session } from 'electron'
import { isDrmWindow, type ScreenSource } from '@shared/screen'

// Screen share (Go Live in voice channels, 2026-11-22). The UI shows its own picker from
// screen:sources, says which one was picked (screen:choose), then calls getDisplayMedia; the handler
// below hands Chromium that source, plus the PC's sound when asked (Windows loopback; the renderer asks
// Chromium to leave the app's own audio, the call, out of it).

let picked: { id: string; audio: boolean } | null = null

export function setupScreenShare(): void {
  ipcMain.handle('screen:sources', async (): Promise<ScreenSource[]> => {
    const sources = await desktopCapturer.getSources({ types: ['screen', 'window'], thumbnailSize: { width: 352, height: 198 }, fetchWindowIcons: true })
    return sources
      .filter((s) => !s.thumbnail.isEmpty())
      .map((s) => {
        const kind = s.id.startsWith('screen:') ? 'screen' : 'window'
        return {
          id: s.id,
          name: s.name,
          kind,
          thumbnail: s.thumbnail.toDataURL(),
          icon: s.appIcon && !s.appIcon.isEmpty() ? s.appIcon.toDataURL() : null,
          blocked: kind === 'window' && isDrmWindow(s.name)
        }
      })
  })

  ipcMain.handle('screen:choose', (_event, input: unknown) => {
    const o = input as { id?: unknown; audio?: unknown } | null
    picked = o && typeof o.id === 'string' ? { id: o.id, audio: o.audio === true } : null
  })

  session.defaultSession.setDisplayMediaRequestHandler((request, callback) => {
    const pick = picked
    picked = null
    if (!pick) {
      // Only our own picker starts a share.
      callback(null)
      return
    }
    void desktopCapturer
      .getSources({ types: ['screen', 'window'] })
      .then((sources) => {
        const source = sources.find((s) => s.id === pick.id)
        if (!source || (source.id.startsWith('window:') && isDrmWindow(source.name))) {
          callback(null)
          return
        }
        callback(pick.audio && request.audioRequested ? { video: source, audio: 'loopback' } : { video: source })
      })
      .catch(() => callback(null))
  })
}
