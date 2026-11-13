// Contracts between the renderer and the main process for the Windows integration
// (src/main/desktop.ts): tray, start with Windows, notifications, global shortcuts, mini window.

export interface DesktopPrefs {
  /** Closing the window hides it to the tray (default on). */
  closeToTray: boolean
  /** The system-wide shortcuts below (default on). */
  globalShortcuts: boolean
}

/** Work while another app has focus (the browser with the movie). Electron accelerator strings. */
export const GLOBAL_SHORTCUTS = {
  playPause: 'CommandOrControl+Shift+Space',
  syncToMe: 'CommandOrControl+Shift+S',
  toggleMute: 'CommandOrControl+Shift+M',
  miniWindow: 'CommandOrControl+Shift+B'
} as const

export type GlobalShortcutId = keyof typeof GLOBAL_SHORTCUTS

/** How shortcuts read on screen ("Ctrl+Shift+Space"). */
export function shortcutLabel(accel: string): string {
  return accel.replace('CommandOrControl', 'Ctrl')
}

export interface NotifyRequest {
  /** One toast per tag: "dm:<chatId>", "friend:<uid>", "invite:<uid>". */
  tag: string
  title: string
  body: string
  /** Given back on click (notify:click): "chat:<chatId>", "friends", "room:<code>". */
  action: string
  silent?: boolean
}

/** What the mini room window shows; the main window sends it on every change. */
export interface MiniRoomState {
  code: string
  title: string
  service: string | null
  playing: boolean
  positionMs: number
  durationMs: number | null
  syncLabel: string
  allInSync: boolean
  hasTab: boolean
  muted: boolean
  inVoice: boolean
  members: { id: string; name: string; label: string; health: string; isMe: boolean }[]
  chat: { id: string; name: string; text: string; fromMe: boolean }[]
}

/** Mini window → main window. */
export type MiniAction =
  | { kind: 'togglePlay' }
  | { kind: 'seekBy'; deltaMs: number }
  | { kind: 'syncToMe' }
  | { kind: 'toggleMute' }
  | { kind: 'send'; text: string }
  | { kind: 'react'; emoji: string }
  | { kind: 'open' }
