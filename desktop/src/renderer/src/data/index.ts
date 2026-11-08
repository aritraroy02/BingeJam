// The desktop's Firebase data layer: same project, paths and formats as the Android app.
// Screens import from here; everything maps into the view models in ui/models.ts.

export { useMyProfile, checkUsername, createProfile, writePrivateProfile, UsernameTakenError, type MyProfileState, type UsernameCheck } from './profile.ts'
export {
  useFriends,
  useFriendRecords,
  useFriendRequests,
  sendFriendRequest,
  acceptFriendRequest,
  declineFriendRequest,
  requestToJoin,
  FriendRequestError,
  type FriendRequestVM
} from './friends.ts'
export {
  useChats,
  useChatData,
  useChatMessages,
  useChatHeader,
  sendMessage,
  markRead,
  deleteChat,
  setTyping,
  useTypingReporter,
  useFriendTyping,
  NotFriendsError
} from './chats.ts'
export {
  useGroupMembers,
  useAmGroupAdmin,
  createGroup,
  renameGroup,
  addGroupMembers,
  removeGroupMember,
  leaveGroup,
  useGroupInvites,
  acceptGroupInvite,
  declineGroupInvite
} from './groups.ts'
export { startOrJoinChatRoom, leaveChatRoom, pingMember, useActiveChatRoom, activeChatRoom } from './chatRooms.ts'
export { useHome, useTrending, trendingWatchLink, type TrendingKind } from './home.ts'
export { startDevicePresence, setInvisible, isInvisible, deviceId } from './devicePresence.ts'
export { notifyRelay, fetchTrending, fetchWatchLink, lookupNetflixId, avatarUrl, RELAY_URL, type RelayNote, type RelayRequest, type RelayResponse } from './relay.ts'
export { serverNow, serverClock, useServerNow, isServerClockSynced } from './clock.ts'
export { dmId, dmFriend } from './ids.ts'
export type { GroupInvite, Profile, Presence } from './parse.ts'
