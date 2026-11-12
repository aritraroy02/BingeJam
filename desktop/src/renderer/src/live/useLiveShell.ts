import { useEffect, useMemo, useState } from 'react'
import { LINKS } from '@shared/brand'
import type { ServiceId } from '@shared/player'
import {
  acceptFriendRequest,
  addGroupMembers,
  createGroup,
  declineFriendRequest,
  deleteChat,
  dmId,
  FriendRequestError,
  leaveChatRoom,
  leaveGroup,
  markRead,
  pingMember,
  renameGroup,
  requestToJoin,
  sendFriendRequest,
  sendMessage,
  setTyping,
  startOrJoinChatRoom,
  trendingWatchLink,
  useChatHeader,
  useChatMessages,
  useChats,
  useFriendRequests,
  useFriends,
  useGroupMembers,
  useHome,
  type Profile
} from '@/data'
import { agoShort } from '@/data/format'
import { blockGroup, blockUser, reportGroup, reportUser, unblockGroup, unblockUser, unfriend, useBlockedGroups, useBlockedPeople } from '@/data/safety'
import { checkUsername, createProfile, removePhoto, setBirthDate, updateProfile, uploadPhoto, useMyBirthDate } from '@/data/profile'
import { cancelFriendRequest } from '@/data/friends'
import { deleteMessageForEveryone, deleteMessageForMe } from '@/data/chats'
import { removeGroupMember, setGroupAdmin, setGroupSettings, useGroupInvited } from '@/data/groups'
import { useLive } from '@/data/live'
import { dmFriend } from '@/data/ids'
import { isGroupId } from '@/data/ids'
import { useProfiles } from '@/data/live'
import { useServerNow } from '@/data/clock'
import { signOut } from '@/lib/auth'
import type { ShellData } from '@/shell'
import { useRoomSync } from '@/sync/appRoom'
import { buildRoomVM, livePositionMs } from '@/sync/roomVm'
import type { BrowserId, FriendVM, GroupMemberVM, ProfileVM, TrendingVM } from '@/ui/models'
import { useDetectedBrowsers, useExtensionStatus } from './extension'
import { useVoiceDevices } from './voiceDevices'

// Real data for every screen (the signed-in counterpart of demo/useDemo.ts). Screens stay
// props-only; this hook is the only place that knows about Firebase, the extension and the room.

const SERVICE_HOME: Partial<Record<ServiceId, string>> = {
  NETFLIX: 'https://www.netflix.com/browse',
  JIOHOTSTAR: 'https://www.hotstar.com/in/home',
  PRIME_VIDEO: 'https://www.primevideo.com/'
}

function errorText(e: unknown, fallback: string): string {
  return e instanceof Error && e.message && !/permission|PERMISSION_DENIED/i.test(e.message) ? e.message : fallback
}

export interface LiveShellInput {
  profile: Profile
  chatId: string | null
  trendingKind: 'movies' | 'series'
  version: string
  /** Stops this PC's presence; awaited before signing out (the removal needs our token). */
  stopPresence: () => Promise<void>
  /** Whose profile dialog is open. */
  profileUid: string | null
}

export function useLiveShell({ profile, chatId, trendingKind, version, stopPresence, profileUid }: LiveShellInput): ShellData {
  const uid = profile.uid
  const [notice, setNotice] = useState<string | null>(null)
  const [viewing, setViewing] = useState<string | null>(null)
  const [roomStarting, setRoomStarting] = useState(false)

  // ---------- Lists ----------
  const home = useHome(trendingKind)
  const chats = useChats()
  const friends = useFriends()
  const requests = useFriendRequests()
  const blockedPeople = useBlockedPeople()
  const blockedGroups = useBlockedGroups()
  const myBirthDate = useMyBirthDate()
  const [profileBusy, setProfileBusy] = useState(false)

  // ---------- The profile dialog ----------
  const viewed = useProfiles(useMemo(() => (profileUid ? [profileUid] : []), [profileUid]))
  const sentToViewed = useLive(profileUid ? `sentFriendRequests/${uid}/${profileUid}` : null)
  const friendSince = useLive(profileUid ? `friends/${uid}/${profileUid}/since` : null)
  const profileVM = useMemo((): ProfileVM | null => {
    if (!profileUid || !viewed.has(profileUid)) return null
    const p = viewed.get(profileUid)
    if (!p) return { id: profileUid, name: 'Unknown', username: '', bio: null, photoUrl: null, relation: 'none', missing: true }
    const friend = friends?.find((f) => f.id === profileUid)
    const relation: ProfileVM['relation'] =
      profileUid === uid
        ? 'me'
        : blockedPeople?.some((b) => b.uid === profileUid)
          ? 'blocked'
          : friend
            ? 'friend'
            : requests?.some((r) => r.fromUid === profileUid)
              ? 'incoming'
              : sentToViewed?.exists
                ? 'requested'
                : 'none'
    const since = typeof friendSince?.val === 'number' ? new Date(friendSince.val) : null
    return {
      id: profileUid,
      name: p.displayName,
      username: p.username,
      bio: p.bio,
      photoUrl: p.photoUrl,
      relation,
      status: friend ? friend.status : null,
      since: since ? `Friends since ${since.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })}` : null
    }
  }, [profileUid, viewed, friends, blockedPeople, requests, sentToViewed, friendSince, uid])
  const now = useServerNow()

  // ---------- The open chat ----------
  const header = useChatHeader(chatId)
  const messages = useChatMessages(chatId)
  const members = useGroupMembers(chatId && isGroupId(chatId) ? chatId : null)
  const friendIds = useMemo(() => (friends ?? []).map((f) => f.id), [friends])
  const invitedSet = useGroupInvited(chatId && isGroupId(chatId) && !dmFriend(chatId, uid) ? chatId : null, friendIds)
  const invited = useMemo(() => [...invitedSet], [invitedSet])

  // Read receipts: whenever the chat on screen gets new messages.
  useEffect(() => {
    if (viewing && viewing === chatId && messages) void markRead(viewing).catch(() => undefined)
  }, [viewing, chatId, messages])

  // ---------- Extension + room ----------
  const ext = useExtensionStatus()
  const linked = ext?.connections ?? []
  const browser = linked[0]?.browser ?? null
  const detected = useDetectedBrowsers(linked.map((c) => c.browser).join(','))
  const room = useRoomSync(uid)
  const roomChat = room.active?.chatId ?? null
  const roomHeader = useChatHeader(roomChat)
  const roomMembers = useGroupMembers(roomChat && isGroupId(roomChat) ? roomChat : null)
  const roomMessages = useChatMessages(roomChat)
  const roomPhotos = useProfiles(useMemo(() => room.state.members.map((m) => m.uid), [room.state.members]))
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!room.state.playing) return
    const t = setInterval(() => setTick((n) => n + 1), 1_000)
    return () => clearInterval(t)
  }, [room.state.playing])

  const roomVM = useMemo(() => {
    if (!room.active) return null
    const chatPeople: GroupMemberVM[] =
      roomMembers ??
      (roomHeader?.kind === 'dm'
        ? roomHeader.people.map((p) => ({ ...p, detail: p.online ? 'Online' : 'Not in the room', inRoom: false, isMe: false, ping: 'can' as const }))
        : [])
    const photos = new Map<string, string | null>()
    for (const [id, p] of roomPhotos) photos.set(id, p?.photoUrl ?? null)
    return buildRoomVM({
      state: room.state,
      code: room.active.code,
      chatId: room.active.chatId,
      chatName: roomHeader?.name ?? null,
      chatKind: roomHeader?.kind ?? null,
      chatPeople,
      photos,
      browser,
      waitForBuffering: ext?.settings.waitForBuffering ?? true,
      localNow: performance.now()
    })
    // `tick` moves the position on once a second while playing.
  }, [room.active, room.state, roomHeader, roomMembers, roomPhotos, browser, ext?.settings.waitForBuffering, tick])

  // ---------- Settings ----------
  const voice = useVoiceDevices()

  // ---------- Actions ----------
  function fail(fallback: string): (e: unknown) => void {
    return (e) => {
      console.warn(fallback, e)
      setNotice(errorText(e, fallback))
    }
  }

  function command(type: 'play' | 'pause' | { seekTo: number }): void {
    const tabId = room.state.tabId
    if (tabId === null) {
      // No tab to control: a "3, 2, 1, play" for everyone is all we can offer.
      if (type === 'play' || type === 'pause') void room.session.startCountdown()
      else setNotice('Open the movie in your browser first')
      return
    }
    const cmd =
      typeof type === 'string' ? { type } : { type: 'seek' as const, positionMs: Math.max(0, Math.round(type.seekTo)) }
    // Same path as a click in the tab: the engine sees the change and tells the room.
    void window.bingejam.extension.send({ kind: 'command', message: { tabId, command: cmd, commandId: `ui-${performance.now().toFixed(0)}` } })
  }

  async function enterRoom(id: string): Promise<void> {
    setRoomStarting(true)
    try {
      await startOrJoinChatRoom(id)
    } catch (e) {
      fail("Couldn't open the room")(e)
    } finally {
      setRoomStarting(false)
    }
  }

  const friendRequestVMs = useMemo(
    () => requests?.map((r) => ({ id: r.fromUid, name: r.name, username: r.username, photoUrl: r.photoUrl, sentLabel: agoShort(Math.max(0, now - r.sentAt)) })) ?? null,
    [requests, now]
  )

  const unread = chats?.reduce((n, c) => n + c.unread, 0) ?? 0

  return {
    me: { uid, name: profile.displayName, username: profile.username, photoUrl: profile.photoUrl },
    home,
    chats,
    header: (id) => (id === chatId ? header : null),
    messages: (id) => (id === chatId ? messages : null),
    members: (id) => (id === chatId ? members : null),
    friendPeople: friends?.map((f) => ({ id: f.id, name: f.name, username: f.username, photoUrl: f.photoUrl, online: f.online })) ?? null,
    room: roomVM,
    roomStarting,
    roomChat: roomMessages,
    stream: {
      liveHost: null,
      liveDetail: '',
      stats: '',
      viewers: 0,
      source: 'game',
      quality: '1080p60',
      capacityTitle: '1080p · 60 needs about 8 Mbps upload per viewer',
      capacityDetail: 'Streaming arrives in a later update.'
    },
    streamChat: [],
    media: null,
    browser: {
      found: (detected ?? []).map((b) => ({ id: b.id, name: b.name })),
      connected: linked[0]
        ? { browser: linked[0].browser, detail: `Version ${linked[0].extensionVersion} · ${linked[0].tabs.length} streaming tab${linked[0].tabs.length === 1 ? '' : 's'}` }
        : null,
      popup:
        roomVM && room.active
          ? {
              roomCode: room.active.code,
              people: `${roomVM.members.length} in the room`,
              title: roomVM.title,
              position: clock(livePositionMs(room.state, performance.now())),
              inSync: roomVM.allInSync
            }
          : null
    },
    settings: {
      account: { uid, name: profile.displayName, username: profile.username, email: null, photoUrl: profile.photoUrl, bio: profile.bio },
      extension: { connected: linked.length > 0, detail: browser ? `Linked in ${browser}` : 'Add it from Settings' },
      voice: voice.vm,
      startWithWindows: null,
      version
    },
    friends: {
      friends,
      requests: friendRequestVMs,
      sent: [],
      blockedPeople: blockedPeople?.map((b) => ({ id: b.uid, name: b.name, username: b.username, photoUrl: b.photoUrl })) ?? null,
      blockedGroups
    },
    badges: { chats: unread || undefined, friends: requests?.length || undefined },
    profile: profileVM,
    invited,
    myBirthDate: myBirthDate.state === 'set' ? myBirthDate.value : null,
    profileBusy,
    notice,
    act: {
      send: (id, text) => void sendMessage(id, text).catch(fail("Couldn't send that")),
      typing: (id, typing) => setTyping(id, typing),
      ping: (id, who) =>
        void pingMember(id, who).then(
          (r) => setNotice(r === 'sent' ? 'Pinged' : 'You pinged them a moment ago'),
          fail("Couldn't ping them")
        ),
      rename: (id, name) => void renameGroup(id, name).catch(fail("Couldn't rename the group")),
      newGroup: (name, ids) => createGroup(name, ids).catch((e: unknown) => (fail("Couldn't create the group")(e), null)),
      addPeople: (id, ids) =>
        void addGroupMembers(id, ids).then(
          (r) => r.invited.length > 0 && setNotice(`Invited ${r.invited.length}: they join when they accept`),
          fail("Couldn't add them")
        ),
      deleteChat: (id) => void (isGroupId(id) ? leaveGroup(id) : deleteChat(id)).catch(fail("Couldn't do that")),
      groups: {
        setAdmin: (id, who, admin) =>
          void setGroupAdmin(id, who, admin).then(
            () => setNotice(admin ? 'Made group admin' : 'Dismissed as admin'),
            fail(admin ? "Couldn't make them admin" : "Couldn't dismiss them as admin")
          ),
        remove: (id, who) => void removeGroupMember(id, who).then(() => setNotice('Removed from the group'), fail("Couldn't remove them")),
        settings: (id, send, editInfo) => void setGroupSettings(id, send, editInfo).catch(fail("Couldn't change the group settings"))
      },
      messages: {
        forward: (text, ids) =>
          void Promise.all(ids.map((friendUid) => sendMessage(dmId(uid, friendUid), text, true))).then(
            () => setNotice(ids.length === 1 ? 'Forwarded' : `Forwarded to ${ids.length} friends`),
            fail("Couldn't forward it")
          ),
        deleteForMe: (id, messageId) => void deleteMessageForMe(id, messageId).catch(fail("Couldn't delete it")),
        deleteForEveryone: (id, messageId) => void deleteMessageForEveryone(id, messageId).catch(fail("Couldn't delete it"))
      },
      profile: {
        add: (username) =>
          void sendFriendRequest(username).then(
            (r) => setNotice(r === 'accepted' ? "You're friends now" : `Request sent to @${username}`),
            fail("Couldn't send the request")
          ),
        accept: (who) => void acceptFriendRequest(who).then(() => setNotice("You're friends now"), fail("Couldn't accept")),
        cancel: (who) => void cancelFriendRequest(who).then(() => setNotice('Request cancelled'), fail("Couldn't cancel")),
        unblock: (who) => void unblockUser(who).then(() => setNotice('Unblocked'), fail("Couldn't unblock them")),
        unfriend: (who) => void unfriend(who).then(() => setNotice('Removed from your friends'), fail("Couldn't remove them")),
        block: (who) => void blockUser(who).then(() => setNotice('Blocked'), fail("Couldn't block them")),
        report: (who, name, reason, details, block) =>
          void reportUser(who, name, reason, details, block).then(
            () => setNotice(block ? 'Reported and blocked' : 'Thanks. Your report was sent.'),
            fail("Couldn't send the report")
          )
      },
      editProfile: {
        save: async (v) => {
          setProfileBusy(true)
          try {
            const username = v.username.trim()
            if (username !== profile.username) {
              const check = await checkUsername(username)
              if (check.state === 'invalid') throw new Error(check.reason)
              if (check.state === 'taken') throw new Error(`@${username} is taken`)
              await createProfile(username, v.name)
            }
            await updateProfile(v.name, v.bio)
            const current = myBirthDate.state === 'set' ? myBirthDate.value : ''
            if (v.birthDate && v.birthDate !== current) await setBirthDate(v.birthDate)
            setNotice('Profile saved')
            return true
          } catch (e) {
            fail("Couldn't save your profile")(e)
            return false
          } finally {
            setProfileBusy(false)
          }
        },
        pickPhoto: (file) => {
          setProfileBusy(true)
          void uploadPhoto(file)
            .then(() => setNotice('Picture updated'), fail("Couldn't change your picture"))
            .finally(() => setProfileBusy(false))
        },
        removePhoto: () => void removePhoto().then(() => setNotice('Picture removed'), fail("Couldn't remove your picture"))
      },
      safety: {
        block: (id) => {
          const friendUid = dmFriend(id, uid)
          if (!friendUid) return
          void blockUser(friendUid).then(() => setNotice('Blocked'), fail("Couldn't block them"))
        },
        blockGroup: (id) => {
          const name = header?.chatId === id ? header.name : 'Group'
          void blockGroup(id, name).then(() => setNotice(`Exited and blocked ${name}`), fail("Couldn't block the group"))
        },
        report: (id, reason, details, block) => {
          const name = header?.chatId === id ? header.name : ''
          const friendUid = dmFriend(id, uid)
          const done = friendUid ? reportUser(friendUid, name, reason, details, block) : reportGroup(id, name, reason, details, block)
          void done.then(() => setNotice(block ? 'Reported and blocked' : 'Thanks. Your report was sent.'), fail("Couldn't send the report"))
        },
        unblockUser: (who) => void unblockUser(who).then(() => setNotice('Unblocked'), fail("Couldn't unblock them")),
        unblockGroup: (gid) => void unblockGroup(gid).then(() => setNotice('Unblocked. Its admins can add you again.'), fail("Couldn't unblock the group"))
      },
      startRoom: (id) => void enterRoom(id),
      joinRoom: (id) => void enterRoom(id),
      leaveRoom: () => void leaveChatRoom().catch(fail("Couldn't leave the room")),
      viewing: setViewing,
      dismissNotice: () => setNotice(null),
      dmWith: (friendUid) => dmId(uid, friendUid),
      openTitle: (item: TrendingVM) =>
        void trendingWatchLink(item.id).then(
          (link) => (link ? window.bingejam.openExternal(link) : setNotice(`Couldn't find ${item.title} on ${item.service}`)),
          fail("Couldn't open that title")
        ),
      friendAction: (f: FriendVM) => {
        if (f.action === 'join' || f.action === 'watch' || f.action === 'jam')
          void requestToJoin(f.id).then(() => setNotice(`Asked ${f.name} to let you in`), fail("Couldn't ask to join"))
      },
      room: {
        send: (text) => roomChat && void sendMessage(roomChat, text).catch(fail("Couldn't send that")),
        togglePlay: () => command(room.state.playing ? 'pause' : 'play'),
        seekBy: (delta) => command({ seekTo: livePositionMs(room.state, performance.now()) + delta }),
        seekTo: (pos) => command({ seekTo: pos }),
        subtitles: () => setNotice('Subtitles are chosen in the player'),
        waitForBuffering: () => setNotice('Change this in the BingeJam button in your browser'),
        // Re-announce our own position: the engine broadcasts it as a seek everyone follows.
        syncAllToMe: () => command({ seekTo: livePositionMs(room.state, performance.now()) }),
        invite: () => {
          if (!room.active) return
          void navigator.clipboard.writeText(LINKS.joinLink(room.active.code)).then(
            () => setNotice('Invite link copied'),
            () => setNotice(`Room code ${room.active?.code}`)
          )
        },
        showPlayer: () => {
          const url = room.state.service ? SERVICE_HOME[room.state.service] : SERVICE_HOME.NETFLIX
          if (room.state.tabId === null && url) void window.bingejam.openExternal(url)
        },
        toggleMute: () => setNotice('Voice chat comes to the PC app soon'),
        joinVoice: () => setNotice('Voice chat comes to the PC app soon')
      },
      stream: { source: () => undefined, quality: () => undefined, start: () => setNotice('Streaming arrives in a later update'), stop: () => undefined, send: () => undefined },
      addToBrowser: (id: BrowserId) =>
        void window.bingejam.browsers.addTo(id).then(
          (r) =>
            setNotice(
              r.ok
                ? r.path
                  ? 'Folder path copied. Turn on Developer mode, click Load unpacked and paste it.'
                  : 'Follow the steps in your browser'
                : r.error ?? "Couldn't open that browser"
            ),
          fail("Couldn't open that browser")
        ),
      settings: {
        signOut: () =>
          void (async () => {
            await leaveChatRoom().catch(() => undefined)
            await stopPresence()
            await signOut()
          })(),
        inputDevice: voice.setInput,
        outputDevice: voice.setOutput,
        pushToTalkKey: voice.setPushToTalk,
        startWithWindows: () => undefined
      },
      friends: {
        add: async (username) => {
          const name = username.trim().replace(/^@/, '')
          try {
            const r = await sendFriendRequest(name)
            return {
              ok: true,
              message: r === 'accepted' ? `You and @${name} are friends now` : r === 'alreadyPending' ? `Already sent to @${name}` : `Request sent to @${name}`
            }
          } catch (e) {
            return { ok: false, message: e instanceof FriendRequestError ? e.message : "Couldn't send the request. Try again." }
          }
        },
        accept: (who) => void acceptFriendRequest(who).catch(fail("Couldn't accept")),
        decline: (who) => void declineFriendRequest(who).catch(fail("Couldn't decline")),
        cancel: (who) => void cancelFriendRequest(who).catch(fail("Couldn't cancel"))
      }
    }
  }
}

function clock(ms: number): string {
  const s = Math.floor(ms / 1000)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = String(s % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`
}
