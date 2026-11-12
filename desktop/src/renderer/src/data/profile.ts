import { get, ref, serverTimestamp, set, update } from 'firebase/database'
import { auth, db } from '@/lib/firebase'
import { isPermissionDenied, requireUid, useLive, useUid } from './live.ts'
import { parseProfile, type Profile } from './parse.ts'
import { cleanDisplayName, normalizeUsername, usernameKey, usernameProblem } from './usernames.ts'
import { parseBirthDate } from './birthDate.ts'

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

// ---------- Date of birth (users_private/{uid}/dob, like the phone since 0.7.0) ----------

export type BirthDateState = { state: 'loading' } | { state: 'missing' } | { state: 'set'; value: string }

/** Ours only; a refused or failed read stays 'loading' (never send an existing user back to setup by mistake). */
export function useMyBirthDate(): BirthDateState {
  const uid = useUid()
  const snap = useLive(uid ? `users_private/${uid}/dob` : null)
  if (!uid || !snap || snap.denied) return { state: 'loading' }
  return typeof snap.val === 'string' ? { state: 'set', value: snap.val } : { state: 'missing' }
}

/** Writes "YYYY-MM-DD" (checked by the caller with birthDateProblem). users_private must exist first. */
export async function setBirthDate(value: string): Promise<void> {
  const uid = requireUid()
  if (!parseBirthDate(value)) throw new Error('Pick your date of birth')
  await writePrivateProfile()
  await set(ref(db, `users_private/${uid}/dob`), value)
}

// ---------- Edit profile (2026-11-12, same as the phone's Edit profile) ----------

const MAX_BIO = 160

/** Name and bio; username changes go through createProfile (it swaps the usernames entry atomically). */
export async function updateProfile(displayName: string, bio: string | null): Promise<void> {
  const uid = requireUid()
  const clean = cleanDisplayName(displayName)
  if (!clean) throw new Error('Enter a name')
  const b = bio?.trim().slice(0, MAX_BIO) || null
  await update(ref(db, `users/${uid}`), { displayName: clean, bio: b })
}

/** The relay keeps stills up to 300 KB and GIFs up to 1 MB. */
const MAX_STILL_BYTES = 300_000
const MAX_GIF_BYTES = 1_048_576
const MAX_SIDE = 512

/** A GIF stays as it is (animated); anything else becomes a JPEG of at most 512 px and 300 KB. */
async function preparePhoto(file: File): Promise<Uint8Array> {
  if (file.type === 'image/gif') {
    if (file.size > MAX_GIF_BYTES) throw new Error('GIFs can be up to 1 MB')
    return new Uint8Array(await file.arrayBuffer())
  }
  const bitmap = await createImageBitmap(file).catch(() => {
    throw new Error("That file isn't a picture")
  })
  const scale = Math.min(1, MAX_SIDE / Math.max(bitmap.width, bitmap.height))
  const canvas = document.createElement('canvas')
  canvas.width = Math.max(1, Math.round(bitmap.width * scale))
  canvas.height = Math.max(1, Math.round(bitmap.height * scale))
  canvas.getContext('2d')?.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
  bitmap.close()
  for (const quality of [0.86, 0.75, 0.6, 0.45]) {
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
    if (blob && blob.size <= MAX_STILL_BYTES) return new Uint8Array(await blob.arrayBuffer())
  }
  throw new Error('That picture is too big')
}

function avatarError(status: number, body: string): Error {
  if (status === 429 || body.includes('too_soon')) return new Error('Wait a few seconds before changing it again')
  if (status === 413) return new Error('That picture is too big')
  if (status === 415) return new Error("That file isn't a picture")
  return new Error(`The picture didn't upload (${status})`)
}

/** Uploads a new profile picture to the relay and puts it on our profile. */
export async function uploadPhoto(file: File): Promise<void> {
  const uid = requireUid()
  const token = await auth.currentUser?.getIdToken()
  if (!token) throw new Error('Sign in first')
  const bytes = await preparePhoto(file)
  const res = await window.bingejam.relayAvatar({ method: 'PUT', token, bytes })
  if (res.status < 200 || res.status > 299) throw avatarError(res.status, res.body)
  const url = (JSON.parse(res.body) as { url?: unknown }).url
  if (typeof url !== 'string' || !acceptablePhoto(uid, url)) throw new Error("The picture didn't upload")
  await set(ref(db, `users/${uid}/photoUrl`), url)
}

/** Takes the picture off our profile and deletes our upload (a Google photo isn't stored by us). */
export async function removePhoto(): Promise<void> {
  const uid = requireUid()
  await set(ref(db, `users/${uid}/photoUrl`), null)
  const token = await auth.currentUser?.getIdToken()
  if (token) await window.bingejam.relayAvatar({ method: 'DELETE', token }).catch(() => undefined)
}
