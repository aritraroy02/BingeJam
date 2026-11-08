import { get, ref, serverTimestamp, update } from 'firebase/database'
import { auth, db } from '@/lib/firebase'
import { isPermissionDenied, requireUid, useLive, useUid } from './live.ts'
import { parseProfile, type Profile } from './parse.ts'
import { cleanDisplayName, normalizeUsername, usernameKey, usernameProblem } from './usernames.ts'

// Profiles (users/{uid}) and unique usernames (usernames/{key} → uid), exactly as Android's
// FirebaseProfileRepository: the rules only let a name be claimed in the same atomic update that
// points our profile at it, and a rename releases the old name in that same update.

export type MyProfileState =
  | { state: 'loading' }
  /** Signed in, but no username yet: show the pick-a-username screen. */
  | { state: 'missing' }
  /** Couldn't read (rules, token, network). Never treat as "missing": an existing user must not be sent to setup. */
  | { state: 'error' }
  | { state: 'ready'; profile: Profile }

export function useMyProfile(): MyProfileState {
  const uid = useUid()
  const snap = useLive(uid ? `users/${uid}` : null)
  if (uid === undefined || (uid && !snap)) return { state: 'loading' }
  if (!uid || !snap) return { state: 'missing' }
  if (snap.denied) return { state: 'error' }
  const profile = parseProfile(uid, snap.val)
  return profile ? { state: 'ready', profile } : { state: 'missing' }
}

export type UsernameCheck = { state: 'invalid'; reason: string } | { state: 'available'; username: string } | { state: 'taken' }

/** Exact read of usernames/{key} (readable one by one; listing is denied). Ours counts as available. */
export async function checkUsername(candidate: string): Promise<UsernameCheck> {
  const name = normalizeUsername(candidate)
  const reason = usernameProblem(name)
  if (reason) return { state: 'invalid', reason }
  const owner = (await get(ref(db, `usernames/${usernameKey(name)}`))).val()
  return owner === null || owner === auth.currentUser?.uid ? { state: 'available', username: name } : { state: 'taken' }
}

export class UsernameTakenError extends Error {
  constructor(username: string) {
    super(`@${username} is taken`)
    this.name = 'UsernameTakenError'
  }
}

/** photoUrl values the rules accept: our relay avatar or a Google photo (anything else rejects the whole update). */
function acceptablePhoto(uid: string, url: string | null | undefined): string | null {
  if (!url || url.length > 500) return null
  if (url.startsWith(`https://vyro-push-relay.aritraroy02.workers.dev/avatar/${uid}?v=`) || url.startsWith('https://lh3.googleusercontent.com/')) return url
  return null
}

/**
 * First setup (or a rename): one multi-path update, the same as Android's claimUsername:
 * usernames/{new} = uid (+ old name released), users/{uid}/username, displayName, photoUrl if
 * absent and a Google photo, createdAt (server time) if absent. Also writes users_private like
 * Android does at sign-in, best effort.
 */
export async function createProfile(username: string, displayName: string): Promise<void> {
  const uid = requireUid()
  const name = normalizeUsername(username)
  const reason = usernameProblem(name)
  if (reason) throw new Error(reason)
  const clean = cleanDisplayName(displayName)
  if (!clean) throw new Error('Enter a name')

  const existing = (await get(ref(db, `users/${uid}`))).val() as Record<string, unknown> | null
  const oldName = typeof existing?.username === 'string' ? existing.username : null
  const changes: Record<string, unknown> = {}
  if (oldName !== name) {
    changes[`usernames/${usernameKey(name)}`] = uid
    if (oldName) changes[`usernames/${usernameKey(oldName)}`] = null
  }
  changes[`users/${uid}/username`] = name
  changes[`users/${uid}/displayName`] = clean
  if (existing?.photoUrl === undefined) {
    const photo = acceptablePhoto(uid, auth.currentUser?.photoURL)
    if (photo) changes[`users/${uid}/photoUrl`] = photo
  }
  if (existing?.createdAt === undefined) changes[`users/${uid}/createdAt`] = serverTimestamp()
  try {
    await update(ref(db), changes)
  } catch (e) {
    if (!isPermissionDenied(e)) throw e
    // Denied: most likely someone claimed it between our check and the write.
    const owner = await get(ref(db, `usernames/${usernameKey(name)}`)).then((s) => s.val(), () => null)
    if (owner && owner !== uid) throw new UsernameTakenError(name)
    throw e
  }
  await writePrivateProfile()
}

/**
 * users_private/{uid} {email, provider, createdAt-once}: owner-only (FirebaseAuthService.writePrivateProfile).
 * Android writes it on every sign-in; lib/auth could call this after completeEmailLink too.
 */
export async function writePrivateProfile(): Promise<void> {
  const user = auth.currentUser
  if (!user || user.isAnonymous) return
  try {
    const base = `users_private/${user.uid}`
    const exists = (await get(ref(db, `${base}/createdAt`))).exists()
    const provider = user.providerData.some((p) => p.providerId === 'google.com') ? 'google' : 'emailLink'
    const changes: Record<string, unknown> = { provider }
    if (user.email) changes.email = user.email.slice(0, 254)
    if (!exists) changes.createdAt = serverTimestamp()
    await update(ref(db, base), changes)
  } catch (e) {
    console.warn("Couldn't write users_private", e)
  }
}
