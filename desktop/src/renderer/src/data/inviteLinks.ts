// Invite link format, shared with the phone's FriendLinks (2026-11-10). Pure, unit-tested.
//   https://syncit-watchparty.web.app/add/<username>/<token>

export const TOKEN_LENGTH = 12
export const TOKEN_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
const WEB_HOST = 'syncit-watchparty.web.app'

export function inviteLink(username: string, token: string): string {
  return `https://${WEB_HOST}/add/${username}/${token}`
}

export function inviteText(username: string, token: string): string {
  return `Add me on BingeJam and let's watch together: ${inviteLink(username, token)}`
}

const LINK = /(?:syncit:\/\/add\/|https?:\/\/syncit-watchparty\.web\.app\/add\/)([a-z0-9_][a-z0-9_.]{1,18}[a-z0-9_])\/([A-Za-z0-9]{12})(?![A-Za-z0-9])/

/** The username and token in a pasted invite link, or null. */
export function parseInviteLink(text: string): { username: string; token: string } | null {
  const m = LINK.exec(text)
  if (!m || m[1].includes('..') || ![...m[2]].every((c) => TOKEN_ALPHABET.includes(c))) return null
  return { username: m[1], token: m[2] }
}
