import { get, ref, set } from 'firebase/database'
import { db } from '@/lib/firebase'
import { requireUid } from './live.ts'

// Personal invite links, the same as the phone's FriendLinks / FirebaseFriendInvites (2026-11-10):
//   https://syncit-watchparty.web.app/add/<username>/<token>, token in users_private/{uid}/inviteToken.
// A request carrying our token ("via") is accepted by the relay at once; acceptInvited() is the
// fallback the app runs when it sees one.

import { TOKEN_ALPHABET, TOKEN_LENGTH } from './inviteLinks.ts'
export { inviteLink, inviteText, parseInviteLink, TOKEN_ALPHABET, TOKEN_LENGTH } from './inviteLinks.ts'

function isToken(value: unknown): value is string {
  return typeof value === 'string' && value.length === TOKEN_LENGTH && [...value].every((c) => TOKEN_ALPHABET.includes(c))
}

function newToken(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(TOKEN_LENGTH))
  // 256 isn't a multiple of 57: draw again for bytes in the uneven tail so every letter is equally likely.
  let out = ''
  for (const b of bytes) {
    let v = b
    while (v >= 228) v = crypto.getRandomValues(new Uint8Array(1))[0]
    out += TOKEN_ALPHABET[v % TOKEN_ALPHABET.length]
  }
  return out
}

/** Our token, created the first time. */
export async function myInviteToken(): Promise<string> {
  const uid = requireUid()
  const current = (await get(ref(db, `users_private/${uid}/inviteToken`))).val()
  if (isToken(current)) return current
  return resetInviteToken()
}

/** A new token: links shared before stop adding friends automatically. */
export async function resetInviteToken(): Promise<string> {
  const uid = requireUid()
  const token = newToken()
  await set(ref(db, `users_private/${uid}/inviteToken`), token)
  return token
}
