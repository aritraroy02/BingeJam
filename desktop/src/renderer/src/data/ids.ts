// Ids shared with the Android app (DmIds in UsernameRules.kt, RoomLinks.kt). Pure, unit-tested.

/** Firebase Auth uids are letters and digits only (never '_'), which is why a dmId can join two with '_'. */
const UID = /^[A-Za-z0-9]{1,128}$/

/** groups/$gid in the rules. Android uses push ids, which start with '-' and so never look like a dmId. */
const GROUP_ID = /^[A-Za-z0-9_-]{1,40}$/

export const ROOM_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'
export const ROOM_CODE_LENGTH = 6
const ROOM_CODE = /^[A-HJ-NP-Z2-9]{6}$/

/** dms/{dmId}: the two uids sorted (UTF-16 order, same as Kotlin's String.compareTo) and joined with "_". */
export function dmId(a: string, b: string): string {
  if (!a || !b || a === b) throw new Error('A DM needs two different users')
  return a < b ? `${a}_${b}` : `${b}_${a}`
}

/** The friend's uid when [chatId] is a 1:1 chat of [me]; null means it's a group id. */
export function dmFriend(chatId: string, me: string): string | null {
  const parts = chatId.split('_')
  if (parts.length !== 2) return null
  const [a, b] = parts
  if (!UID.test(a) || !UID.test(b) || !(a < b)) return null
  if (a === me) return b
  if (b === me) return a
  return null
}

export function isGroupId(chatId: string): boolean {
  return GROUP_ID.test(chatId)
}

export function isRoomCode(code: string): boolean {
  return ROOM_CODE.test(code)
}

/** 6 characters from the alphabet. 32 divides 256, so `byte % 32` is unbiased. */
export function newRoomCode(randomBytes: (n: number) => Uint8Array = cryptoBytes): string {
  const bytes = randomBytes(ROOM_CODE_LENGTH)
  let code = ''
  for (let i = 0; i < ROOM_CODE_LENGTH; i++) code += ROOM_CODE_ALPHABET[bytes[i] % ROOM_CODE_ALPHABET.length]
  return code
}

function cryptoBytes(n: number): Uint8Array {
  return globalThis.crypto.getRandomValues(new Uint8Array(n))
}
