// A friend's status line and action, following the phone (ActivityText, HomeViewModel.buildRows,
// RoomWording). Pure, unit-tested.
import type { FriendAction, FriendVM } from '../ui/models.ts'
import { APPS, isJam } from './catalog.ts'
import { lastSeenText } from './format.ts'
import type { Activity, Presence, Profile } from './parse.ts'

/** The activity to show, or null: offline friends' activity may be stale (ActivityText.visibleActivity). */
export function visibleActivity(p: Presence | null): Activity | null {
  return p?.online ? p.activity : null
}

/** "S2:E4 · Midnight Signal" for movies (Netflix puts S/E in the subtitle), the title otherwise. */
function what(a: Activity): string | null {
  const title = a.title?.trim() || null
  const subtitle = a.subtitle?.trim() || null
  if (APPS[a.app].kind === 'movie') return [subtitle, title].filter(Boolean).join(' · ') || null
  return title
}

/** Friend cards: "S2:E4 · Midnight Signal · Netflix", "Neon Streets · Spotify", "(paused)" when paused. */
export function activityShort(a: Activity): string {
  const app = APPS[a.app].name
  const w = what(a)
  const line = w ? `${w} · ${app}` : `On ${app}`
  return a.playing ? line : `${line} (paused)`
}

/** Chat header: "Watching Midnight Signal · Netflix", "Listening to Neon Streets · Spotify". */
export function activityLong(a: Activity): string {
  const { name, kind } = APPS[a.app]
  const w = what(a)
  let line: string
  if (kind === 'music') line = w ? `Listening to ${w} · ${name}` : `Listening on ${name}`
  else line = w ? `Watching ${w} · ${name}` : `Watching ${name}`
  return a.playing ? line : `${line} (paused)`
}

/** "Playing VALORANT" while online on a PC that's running a game. */
export function gameLine(p: Presence | null): string | null {
  return p?.online && p.game ? `Playing ${p.game.name}` : null
}

/** Activity while online, else a game, else "Do not disturb" / "Online", else "Last seen …" (ActivityText.statusLine). */
export function friendStatus(p: Presence | null, now: number): string {
  if (!p) return 'Offline'
  const a = visibleActivity(p)
  if (a) return activityShort(a)
  const g = gameLine(p)
  if (g) return g
  if (p.online) return p.dnd ? 'Do not disturb' : 'Online'
  return lastSeenText(p.lastSeen, now)
}

export function headerStatus(p: Presence | null, now: number): string {
  if (!p) return 'Offline'
  const a = visibleActivity(p)
  if (a) return activityLong(a)
  const g = gameLine(p)
  if (g) return g
  if (p.online) return p.dnd ? 'Do not disturb' : 'Online'
  return lastSeenText(p.lastSeen, now)
}

/** "Ask to join" for a watch party, "Ask to jam" for music/YouTube; nothing to join → null. */
export function friendAction(p: Presence | null): FriendAction {
  const a = visibleActivity(p)
  if (!a) return null
  return isJam(APPS[a.app].kind) ? 'jam' : 'join'
}

export interface FriendRecord {
  uid: string
  profile: Profile
  presence: Presence | null
}

/**
 * Playing something first, then online, then everyone else; most recently seen first among
 * offline friends; names alphabetically otherwise (HomeViewModel.buildRows).
 */
export function buildFriendVMs(friends: readonly FriendRecord[], now: number): FriendVM[] {
  const rank = (f: FriendRecord): number => (visibleActivity(f.presence) || gameLine(f.presence) ? 0 : f.presence?.online ? 1 : 2)
  return [...friends]
    .sort((a, b) => {
      const r = rank(a) - rank(b)
      if (r !== 0) return r
      if (rank(a) === 2) {
        const seen = (b.presence?.lastSeen ?? 0) - (a.presence?.lastSeen ?? 0)
        if (seen !== 0) return seen
      }
      return a.profile.displayName.toLowerCase().localeCompare(b.profile.displayName.toLowerCase())
    })
    .map((f) => ({
      id: f.uid,
      name: f.profile.displayName,
      username: f.profile.username,
      photoUrl: f.profile.photoUrl,
      online: f.presence?.online === true,
      dnd: f.presence?.online === true && f.presence.dnd === true,
      status: friendStatus(f.presence, now),
      // Game streams ("Live ·", action "watch") don't exist in the data yet.
      action: friendAction(f.presence)
    }))
}
