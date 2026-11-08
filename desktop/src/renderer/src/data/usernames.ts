// Username format and name cleaning, same as Android (UsernameRules.kt, FirebaseProfileRepository,
// GroupChats.cleanName). The database rules enforce the format again. Pure, unit-tested.

export const USERNAME_MIN = 3
export const USERNAME_MAX = 20
export const DISPLAY_NAME_MAX = 40
export const GROUP_NAME_MAX = 50

const RESERVED = new Set(['admin', 'vyro', 'syncit', 'support', 'help', 'root', 'system', 'null', 'undefined'])
const ALLOWED = /^[a-z0-9_.]+$/

/** Lowercases, trims and drops a leading '@'. Doesn't fix anything else. */
export function normalizeUsername(candidate: string): string {
  return candidate.trim().replace(/^@/, '').trim().toLowerCase()
}

/** Null if [name] (already normalized) is a valid, unreserved username, else a reason to show. */
export function usernameProblem(name: string): string | null {
  if (name.length < USERNAME_MIN) return `At least ${USERNAME_MIN} characters`
  if (name.length > USERNAME_MAX) return `At most ${USERNAME_MAX} characters`
  if (!ALLOWED.test(name)) return "Only letters, numbers, '_' and '.'"
  if (name.startsWith('.') || name.endsWith('.')) return "Can't start or end with '.'"
  if (name.includes('..')) return "Can't have two dots in a row"
  if (RESERVED.has(name)) return 'That username is reserved'
  return null
}

export function isValidUsername(name: string): boolean {
  return usernameProblem(name) === null
}

/**
 * usernames/{key}: database keys can't contain '.', so it's stored as ','. Every '.' (the rules'
 * replace() replaces all of them; JavaScript's String.replace with a string would only do the first).
 */
export function usernameKey(username: string): string {
  return username.replaceAll('.', ',')
}

export function usernameFromKey(key: string): string {
  return key.replaceAll(',', '.')
}

/** Trimmed, inner whitespace collapsed, at most 40 characters; null when nothing is left. */
export function cleanDisplayName(raw: string): string | null {
  return collapse(raw, DISPLAY_NAME_MAX)
}

/** Group names: same cleaning, at most 50 characters (GroupChats.cleanName). */
export function cleanGroupName(raw: string): string | null {
  return collapse(raw, GROUP_NAME_MAX)
}

function collapse(raw: string, max: number): string | null {
  const clean = raw.trim().replace(/\s+/g, ' ').slice(0, max).trim()
  return clean.length > 0 ? clean : null
}
