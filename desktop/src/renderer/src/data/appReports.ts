import { useMemo } from 'react'
import { ref, set, update } from 'firebase/database'
import { auth, db } from '@/lib/firebase'
import { requireUid, useLive, useUid } from './live.ts'

// App reports on the PC (2026-11-12), the same data and rules as the phone's (FirebaseAppReports):
//   staff/{emailKey} = "developer" (the owner is fixed by email in the rules and the relay)
//   users, users_private, presence: readable by the owner only
//   crashes/{id}: end-to-end encrypted for staff phones; the PC sees how many, not what's in them

export const OWNER_EMAIL = 'aritraditya.roy@gmail.com'

export type StaffRole = 'owner' | 'developer'

/** Database key for an email: lower case, dots as commas. */
export function emailKey(email: string): string {
  return email.trim().toLowerCase().replaceAll('.', ',')
}

function verifiedEmail(): string | null {
  const user = auth.currentUser
  return user && !user.isAnonymous && user.emailVerified && user.email ? user.email.trim().toLowerCase() : null
}

/** Our role, or null for everyone else (App reports stays hidden). undefined while loading. */
export function useStaffRole(): StaffRole | null | undefined {
  const uid = useUid()
  const email = uid ? verifiedEmail() : null
  const isOwner = email === OWNER_EMAIL
  const snap = useLive(email && !isOwner ? `staff/${emailKey(email)}` : null)
  if (uid === undefined) return undefined
  if (!email) return null
  if (isOwner) return 'owner'
  if (!snap) return undefined
  return snap.val === 'developer' ? 'developer' : null
}

export interface AppUserVM {
  uid: string
  name: string
  username: string
  photoUrl: string | null
  bio: string | null
  email: string | null
  birthDate: string | null
  provider: string | null
  joinedAt: number
  online: boolean
  lastSeen: number | null
  appVersion: string | null
  device: string | null
  android: string | null
}

type Obj = Record<string, unknown>
const o = (v: unknown): Obj | null => (v && typeof v === 'object' ? (v as Obj) : null)
const s = (v: unknown): string | null => (typeof v === 'string' ? v : null)
const n = (v: unknown): number | null => (typeof v === 'number' ? v : null)

/** Everyone who joined, newest first (owner only); null while loading or not allowed. */
export function useAppUsers(enabled: boolean): AppUserVM[] | null {
  const users = useLive(enabled ? 'users' : null)
  const priv = useLive(enabled ? 'users_private' : null)
  const presence = useLive(enabled ? 'presence' : null)
  return useMemo(() => {
    if (!users || !priv || !presence || users.denied) return null
    const p = o(priv.val) ?? {}
    const pr = o(presence.val) ?? {}
    return users.children
      .map(([uid, v]): AppUserVM => {
        const u = o(v) ?? {}
        const x = o(p[uid]) ?? {}
        const d = o(x.device) ?? {}
        const live = o(pr[uid]) ?? {}
        // presence/{uid}/online is the phone; any device online counts.
        const devices = Object.values(o(live.devices) ?? {}).map(o)
        const online = live.online === true || devices.some((dv) => dv?.online === true)
        return {
          uid,
          name: s(u.displayName) ?? '',
          username: s(u.username) ?? '',
          photoUrl: s(u.photoUrl),
          bio: s(u.bio),
          email: s(x.email),
          birthDate: s(x.dob),
          provider: s(x.provider),
          joinedAt: n(u.createdAt) ?? 0,
          online,
          lastSeen: n(live.lastSeen),
          appVersion: s(d.app),
          device: s(d.model),
          android: s(d.android)
        }
      })
      .sort((a, b) => b.joinedAt - a.joinedAt)
  }, [users, priv, presence])
}

/** Developers' emails (owner only). */
export function useDevelopers(enabled: boolean): string[] | null {
  const snap = useLive(enabled ? 'staff' : null)
  return useMemo(
    () => (snap && !snap.denied ? snap.children.filter(([, v]) => v === 'developer').map(([k]) => k.replaceAll(',', '.')).sort() : null),
    [snap]
  )
}

/** How many crash reports there are in the last 7 days (they're encrypted for staff phones). */
export function useCrashCount(enabled: boolean): number | null {
  const snap = useLive(enabled ? 'crashes' : null)
  return snap && !snap.denied ? snap.children.length : null
}

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

export async function addDeveloper(email: string): Promise<void> {
  requireUid()
  const clean = email.trim().toLowerCase()
  if (!EMAIL.test(clean)) throw new Error("That doesn't look like an email address")
  if (clean === OWNER_EMAIL) throw new Error("That's the owner")
  await set(ref(db, `staff/${emailKey(clean)}`), 'developer')
}

/** Takes away their access and their keys, so new reports aren't encrypted for them any more. */
export async function removeDeveloper(email: string): Promise<void> {
  requireUid()
  const key = emailKey(email)
  await update(ref(db), { [`staff/${key}`]: null, [`staffKeys/${key}`]: null })
}

