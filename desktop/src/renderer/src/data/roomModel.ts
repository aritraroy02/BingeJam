// What we write for rooms and chat-room links, exactly as Android (FirebaseSyncTransport.createRoom,
// ChatRoomLinks.start, FirebaseWatchInvites.inviteToRoom). Pure: the server-timestamp placeholder is
// passed in, so tests can see where it goes. Unit-tested.

export const MEMBER_NAME_MAX = 40

/** Pings are rate-limited by the relay (2 minutes per pair); we mirror it to show "Pinged · 1 min". */
export const PING_COOLDOWN_MS = 2 * 60_000

export function cleanMemberName(name: string): string {
  return name.trim().slice(0, MEMBER_NAME_MAX).trim() || 'Guest'
}

/** rooms/{code}/members/{uid} on create or join. */
export function memberFields<T>(name: string, ts: T): { name: string; online: true; joinedAt: T; lastSeen: T } {
  return { name: cleanMemberName(name), online: true, joinedAt: ts, lastSeen: ts }
}

/** rooms/{code} for a new room: rules only accept it if the code is free and we're host + member. */
export function newRoomPayload<T>(uid: string, name: string, ts: T): { hostUid: string; createdAt: T; members: Record<string, ReturnType<typeof memberFields<T>>> } {
  return { hostUid: uid, createdAt: ts, members: { [uid]: memberFields(name, ts) } }
}

/** previews/{code} right after creating (only the host may write it). */
export function initialPreview<T>(name: string, ts: T): { hostName: string; updatedAt: T } {
  return { hostName: cleanMemberName(name), updatedAt: ts }
}

/** dmRooms/{dmId} or groupRooms/{gid} when starting the chat's room: replaces any old link. */
export function linkStartPayload<T>(me: string, code: string, ts: T): { code: string; startedBy: string; startedAt: T; in: Record<string, true> } {
  return { code, startedBy: me, startedAt: ts, in: { [me]: true } }
}

/** invites/{friend}/{me}: the rules need us to be a member of that room. */
export function roomInvite<T>(fromUsername: string, fromDisplayName: string, code: string, ts: T): { fromUsername: string; fromDisplayName: string; roomCode: string; sentAt: T } {
  return { fromUsername, fromDisplayName, roomCode: code, sentAt: ts }
}

/** Ping button state for a member who isn't in the room, from how long ago we pinged them (monotonic). */
export function pingState(pingedAgoMs: number | null, agoShort: (ms: number) => string): 'can' | { pingedLabel: string } {
  if (pingedAgoMs === null || pingedAgoMs >= PING_COOLDOWN_MS) return 'can'
  return { pingedLabel: `Pinged · ${agoShort(pingedAgoMs)}` }
}
