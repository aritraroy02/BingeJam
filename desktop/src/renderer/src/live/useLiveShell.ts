import { useEffect, useMemo, useRef, useState } from 'react'
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
  setDeviceActivity,
  setDeviceGame,
  isDnd,
  setDnd,
  isInvisible,
  setInvisible,
  isSharingActivity,
  setSharingActivity,
  type DeviceActivity,
  type Profile
} from '@/data'
import { agoShort } from '@/data/format'
import { blockGroup, blockUser, reportGroup, reportUser, unblockGroup, unblockUser, unfriend, useBlockedGroups, useBlockedPeople } from '@/data/safety'
import { checkUsername, createProfile, removePhoto, setBirthDate, updateProfile, uploadPhoto, useMyBirthDate } from '@/data/profile'
import { cancelFriendRequest } from '@/data/friends'
import { inviteLink, inviteText, myInviteToken, resetInviteToken } from '@/data/invites'
import { toggleWatchlist, useWatchlist } from '@/data/watchlist'
import { uploadPendingCrashes } from '@/data/crashReports'
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
import { useAppearance } from './appearance'
import { useConnection, useDesktopPrefs, useNotifier } from './desktopIntegration'
import type { NavRequest } from '@/shell'
import type { MiniRoomState } from '@shared/desktop'
import { acceptJoin, declineInvite, declineJoin, dismissInvite, useJoinRequests, useRoomInvites } from '@/data/watchInvites'
import { sendRoomMessage, sendRoomReaction, useRoomChat, useRoomReactions } from '@/data/roomChat'
import { lookupNetflixId } from '@/data/relay'
import { isNavigableUrl, type OverlayState } from '@shared/extension'
import { ROOM_REACTIONS } from '@/screens/Room'
import { joinRoomByCode } from '@/data/chatRooms'
import { parseInviteLink } from '@/data/invites'
import { isRoomCode } from '@/data/ids'
import { playableRefFor } from '@shared/sync/roomParser'
import { useSentFriendRequests } from '@/data/friends'

// Real data for every screen (the signed-in counterpart of demo/useDemo.ts). Screens stay
// props-only; this hook is the only place that knows about Firebase, the extension and the room.

/** Browser services → presence activity app ids (ActivityApp.wire on the phone). */
const ACTIVITY_APP: Partial<Record<ServiceId, DeviceActivity['app']>> = {
  NETFLIX: 'netflix',
  JIOHOTSTAR: 'jiohotstar',
  PRIME_VIDEO: 'primevideo',
  YOUTUBE: 'youtube'
}

const APP_NAME: Record<DeviceActivity['app'], string> = { netflix: 'Netflix', jiohotstar: 'JioHotstar', primevideo: 'Prime Video', youtube: 'YouTube' }

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
  const [navRequest, setNavRequest] = useState<NavRequest | null>(null)
  const navId = useRef(0)
  const go = (to: NavRequest['to'], chatId?: string): void => setNavRequest({ id: ++navId.current, to, chatId })
  const [miniOpen, setMiniOpen] = useState(false)

  // ---------- Lists ----------
  const home = useHome(trendingKind)
  const chats = useChats()
  const friends = useFriends()
  const requests = useFriendRequests()
  const blockedPeople = useBlockedPeople()
  const blockedGroups = useBlockedGroups()
  const myBirthDate = useMyBirthDate()
  const watchlist = useWatchlist()
  const sent = useSentFriendRequests()
  const invites = useRoomInvites()
  const joinRequests = useJoinRequests()
  const appearance = useAppearance()
  const desktop = useDesktopPrefs()
  const connection = useConnection()

  // ---------- Status (Online / Do not disturb / Invisible), shared activity, the running game ----------
  const [myStatus, setMyStatus] = useState<'online' | 'dnd' | 'invisible'>(() => (isInvisible(uid) ? 'invisible' : isDnd(uid) ? 'dnd' : 'online'))
  const [sharing, setSharing] = useState(() => isSharingActivity(uid))
  const [game, setGame] = useState<{ name: string; since: number } | null>(null)
  useEffect(() => {
    let live = true
    void window.bingejam.games?.current().then((g) => live && setGame(g))
    const off = window.bingejam.games?.onChange((g) => setGame(g))
    return () => {
      live = false
      off?.()
    }
  }, [])
  useEffect(() => setDeviceGame(uid, game?.name ?? null), [game?.name, uid, sharing, myStatus])
  async function changeStatus(next: 'online' | 'dnd' | 'invisible'): Promise<void> {
    // The flag first, so the reconnect that ends Invisible already writes the right status.
    await setDnd(next === 'dnd', uid)
    await setInvisible(next === 'invisible', uid)
    setMyStatus(next)
    setNotice(next === 'invisible' ? "You're invisible: friends see you offline" : next === 'dnd' ? 'Do not disturb: no pop-ups' : "You're online")
  }

  // Crash reports waiting on this PC go up (encrypted) once we're signed in.
  useEffect(() => {
    void uploadPendingCrashes(profile.username).catch(() => undefined)
  }, [uid])

  // ---------- Invite link ----------
  const [inviteToken, setInviteToken] = useState<string | null>(null)
  useEffect(() => {
    void myInviteToken().then(setInviteToken, () => undefined)
  }, [uid])
  // Fallback for the relay: a request carrying our token becomes a friendship (FriendInvites.acceptInvited).
  useEffect(() => {
    if (!inviteToken || !requests) return
    for (const r of requests) if (r.via === inviteToken) void acceptFriendRequest(r.fromUid).catch(() => undefined)
  }, [inviteToken, requests])
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
  // "room:<code>" = a room joined by its code alone (no chat behind it).
  const roomChat = room.active && !room.active.chatId.startsWith('room:') ? room.active.chatId : null
  const roomCode = room.active?.code ?? null
  const roomHeader = useChatHeader(roomChat)
  const roomMembers = useGroupMembers(roomChat && isGroupId(roomChat) ? roomChat : null)
  // The room's own chat (chats/{code}), shared with phones in the room.
  const roomMessages = useRoomChat(roomCode)
  const roomReactions = useRoomReactions(roomCode)
  const roomPhotos = useProfiles(useMemo(() => room.state.members.map((m) => m.uid), [room.state.members]))
  const [tick, setTick] = useState(0)
  useEffect(() => {
    if (!room.state.playing) return
    const t = setInterval(() => setTick((n) => n + 1), 1_000)
    return () => clearInterval(t)
  }, [room.state.playing])

  const hostTitle = room.state.hostPreview
  const local = room.state.localTab
  // A member whose browser isn't on the host's title yet (no tab, another service or another id).
  const hostRef = hostTitle?.playableRef ?? null
  const localRef = local ? playableRefFor(local.service, local.itemId) : null
  const offTitle =
    !room.state.isHost &&
    hostTitle?.service != null &&
    hostTitle.title != null &&
    (local === null || local.service !== hostTitle.service || (hostRef !== null && localRef !== null && hostRef !== localRef) || room.state.status === 'otherTitle')
  const openTitleLabel = offTitle && hostTitle?.title ? `Play ${hostTitle.title.length > 32 ? `${hostTitle.title.slice(0, 30)}…` : hostTitle.title} in your browser` : undefined

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
      localNow: performance.now(),
      openTitle: openTitleLabel
    })
    // `tick` moves the position on once a second while playing.
  }, [room.active, room.state, roomHeader, roomMembers, roomPhotos, browser, ext?.settings.waitForBuffering, tick, openTitleLabel])

  /** The link that opens the host's title: their exact id when published, else a Netflix lookup / search. */
  async function hostTitleUrl(): Promise<string | null> {
    const h = room.state.hostPreview
    if (!h?.service) return null
    if (h.playableRef && isNavigableUrl(h.playableRef)) return h.playableRef
    if (h.service === 'NETFLIX' && h.title) {
      const id = await lookupNetflixId(h.title, h.durationMs)
      return id ? `https://www.netflix.com/watch/${id}` : `https://www.netflix.com/search?q=${encodeURIComponent(h.title)}`
    }
    if (h.service === 'YOUTUBE' && h.title) return `https://www.youtube.com/results?search_query=${encodeURIComponent(h.title)}`
    return SERVICE_HOME[h.service] ?? null
  }

  /** In this PC's tab on that service when there is one (the extension navigates it), else a new browser tab. */
  async function openHostTitle(): Promise<void> {
    const url = await hostTitleUrl()
    if (!url) {
      setNotice("Couldn't find the host's title")
      return
    }
    const tab = room.state.localTab
    const sameSite = tab !== null && tab.service === room.state.hostPreview?.service
    if (sameSite && (await window.bingejam.extension.navigate(tab.tabId, url))) return
    await window.bingejam.openExternal(url)
  }

  // Follow the host to the next episode (extension popup toggle): when the host's title link
  // changes and our tab is on that service with another id, open the new one there.
  const followed = useRef<string | null>(null)
  useEffect(() => {
    const ref = room.state.hostPreview?.playableRef ?? null
    const tab = room.state.localTab
    if (!ref || !ext?.settings.followHostEpisodes || room.state.isHost || !tab) return
    if (followed.current === ref) return
    followed.current = ref
    if (tab.service !== room.state.hostPreview?.service || playableRefFor(tab.service, tab.itemId) === ref) return
    void window.bingejam.extension.navigate(tab.tabId, ref)
  }, [room.state.hostPreview?.playableRef, room.state.localTab?.itemId, ext?.settings.followHostEpisodes, room.state.isHost])

  // ---------- Overlay over the video (room chat + reactions) ----------
  const overlayState = useMemo((): OverlayState | null => {
    if (!roomCode) return null
    return {
      code: roomCode,
      chat: (roomMessages ?? []).slice(-6).flatMap((m) => (m.kind === 'text' ? [{ id: m.id, name: (m.senderName ?? '').split(' ')[0], text: m.text, fromMe: m.fromMe }] : [])),
      reactions: (roomReactions ?? []).slice(-12).map((r) => ({ id: r.id, name: r.name, emoji: r.emoji })),
      palette: [...ROOM_REACTIONS]
    }
  }, [roomCode, roomMessages, roomReactions])
  const overlayKey = useRef('')
  useEffect(() => {
    const key = JSON.stringify(overlayState)
    if (key === overlayKey.current) return
    overlayKey.current = key
    window.bingejam.extension.overlay(overlayState)
  }, [overlayState])

  // ---------- "Watching X" for friends (devices/{id}/activity) ----------
  const myActivity = useMemo((): DeviceActivity | null => {
    const tabs = linked.flatMap((c) => c.tabs)
    const tab =
      tabs.find((t) => t.tabId === room.state.tabId) ?? tabs.find((t) => t.playing) ?? tabs.find((t) => linked.some((c) => c.activeTabId === t.tabId)) ?? null
    const app = tab ? ACTIVITY_APP[tab.service] : undefined
    if (!tab || !app || (!tab.title && !tab.playing)) return null
    const others = room.state.members.filter((m) => !m.isMe && m.online).map((m) => m.name.split(' ')[0])
    return {
      app,
      title: tab.title,
      subtitle: tab.subtitle,
      playing: tab.playing,
      durationMs: tab.tabId === room.state.tabId ? room.state.durationMs : null,
      inWatchParty: room.active !== null,
      watchingWith: others.length > 0 ? others.join(', ').slice(0, 100) : null
    }
  }, [ext, room.state.tabId, room.state.members, room.state.durationMs, room.active])
  useEffect(() => setDeviceActivity(uid, myActivity), [myActivity, uid, sharing, myStatus])
  const myActivityLine = game
    ? `Playing ${game.name}`
    : myActivity
      ? `${myActivity.playing ? 'Watching' : 'Paused'} ${myActivity.title ?? ''} · ${APP_NAME[myActivity.app]}`.replace('  ', ' ')
      : null

  // ---------- Settings ----------
  const voice = useVoiceDevices()

  // ---------- Notifications + taskbar badge ----------
  useNotifier({ enabled: desktop.notifications && myStatus !== 'dnd', chats, requests, invites, joinRequests, viewing })
  useEffect(
    () =>
      window.bingejam.desktop.onNotifyClick((action) => {
        const [kind, arg] = [action.slice(0, action.indexOf(':')), action.slice(action.indexOf(':') + 1)]
        if (action === 'friends') go('friends')
        else if (kind === 'chat') go('chat', arg)
        else if (kind === 'invite') joinInvite(arg)
        else go('home') // join requests: the banner is on every screen
      }),
    [invites]
  )

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

  /** Accept a room invite: join its room (linked to our 1:1 chat with them) and open it. */
  function joinInvite(fromUid: string): void {
    const v = invites?.find((x) => x.fromUid === fromUid)
    if (!v) return
    setRoomStarting(true)
    go('room', dmId(uid, fromUid))
    void joinRoomByCode(v.roomCode, dmId(uid, fromUid))
      .then(() => dismissInvite(fromUid))
      .catch((e: unknown) => {
        fail("Couldn't join the room")(e)
        if (e instanceof Error && e.message === 'That room has ended') void dismissInvite(fromUid).catch(() => undefined)
      })
      .finally(() => setRoomStarting(false))
  }

  /** Let a friend in: into the room we're in, or a new room in our 1:1 chat. */
  function letIn(fromUid: string): void {
    void (async () => {
      const code = room.active?.code ?? (await startOrJoinChatRoom(dmId(uid, fromUid)))
      await acceptJoin(fromUid, code)
      if (!room.active) go('room', dmId(uid, fromUid))
      setNotice('Let in. They join in a moment.')
    })().catch(fail("Couldn't let them in"))
  }

  /** bingejam://join/CODE (a /join link) and bingejam://add/<username>/<token> (an invite link). */
  function openLink(link: string): void {
    let url: URL
    try {
      url = new URL(link)
    } catch {
      return
    }
    const parts = `${url.host}${url.pathname}`.split('/').filter(Boolean)
    if (parts[0] === 'join' && parts[1] && isRoomCode(parts[1].toUpperCase())) {
      const code = parts[1].toUpperCase()
      const from = invites?.find((v) => v.roomCode === code)?.fromUid
      setRoomStarting(true)
      go('room', from ? dmId(uid, from) : undefined)
      void joinRoomByCode(code, from ? dmId(uid, from) : null)
        .catch(fail("Couldn't join the room"))
        .finally(() => setRoomStarting(false))
    } else if (parts[0] === 'add' && parts[1]) {
      const parsed = parseInviteLink(`https://x/add/${parts.slice(1).join('/')}`)
      const name = parsed?.username ?? parts[1]
      void sendFriendRequest(name, parsed?.token).then(
        (r) => {
          setNotice(r === 'accepted' ? `You and @${name} are friends now` : `Request sent to @${name}`)
          go('friends')
        },
        fail("Couldn't add them")
      )
    }
  }
  const openLinkRef = useRef(openLink)
  openLinkRef.current = openLink
  useEffect(() => {
    void window.bingejam.takeDeepLink().then((l) => l && openLinkRef.current(l))
    return window.bingejam.onDeepLink((l) => openLinkRef.current(l))
  }, [])

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

  // ---------- Mini window + shortcuts in any app ----------
  const roomActions = useRef({ togglePlay: () => {}, syncToMe: () => {}, seekBy: (_d: number) => {}, send: (_t: string) => {}, react: (_e: string) => {}, toggleMute: () => {} })
  roomActions.current = {
    togglePlay: () => command(room.state.playing ? 'pause' : 'play'),
    syncToMe: () => command({ seekTo: livePositionMs(room.state, performance.now()) }),
    seekBy: (d) => command({ seekTo: livePositionMs(room.state, performance.now()) + d }),
    send: (t) => roomCode && void sendRoomMessage(roomCode, profile.displayName, t).catch(fail("Couldn't send that")),
    react: (e) => roomCode && void sendRoomReaction(roomCode, profile.displayName, e).catch(fail("Couldn't react")),
    toggleMute: () => setNotice('Join voice in the room first')
  }
  useEffect(() => window.bingejam.mini.onOpen(setMiniOpen), [])
  useEffect(
    () =>
      window.bingejam.extension.onOverlayInput((input) => {
        if (input.kind === 'send') roomActions.current.send(input.text)
        else roomActions.current.react(input.emoji)
      }),
    []
  )
  useEffect(
    () =>
      window.bingejam.mini.onAction((a) => {
        const r = roomActions.current
        if (a.kind === 'togglePlay') r.togglePlay()
        else if (a.kind === 'syncToMe') r.syncToMe()
        else if (a.kind === 'seekBy') r.seekBy(a.deltaMs)
        else if (a.kind === 'send') r.send(a.text)
        else if (a.kind === 'react') r.react(a.emoji)
        else if (a.kind === 'toggleMute') r.toggleMute()
      }),
    []
  )
  useEffect(
    () =>
      window.bingejam.desktop.onGlobalShortcut((id) => {
        if (!room.active && id !== 'toggleMute') return
        const r = roomActions.current
        if (id === 'playPause') r.togglePlay()
        else if (id === 'syncToMe') r.syncToMe()
        else if (id === 'toggleMute') r.toggleMute()
      }),
    [room.active]
  )
  // What the mini window shows (it closes when we leave the room).
  const miniState = useMemo((): MiniRoomState | null => {
    if (!roomVM || !room.active) return null
    return {
      code: room.active.code,
      title: roomVM.title,
      service: roomVM.service,
      playing: roomVM.playing,
      positionMs: roomVM.positionMs,
      durationMs: roomVM.durationMs,
      syncLabel: roomVM.syncLabel,
      allInSync: roomVM.allInSync,
      hasTab: room.state.tabId !== null,
      muted: roomVM.voice.muted,
      inVoice: roomVM.voice.joined,
      members: roomVM.members.map((m) => ({ id: m.id, name: m.name, label: m.syncLabel, health: m.health, isMe: m.isMe })),
      chat: (roomMessages ?? []).slice(-30).flatMap((m) => (m.kind === 'text' ? [{ id: m.id, name: m.senderName ?? '', text: m.text, fromMe: m.fromMe }] : []))
    }
  }, [roomVM, room.active, room.state.tabId, roomMessages])
  useEffect(() => {
    window.bingejam.mini.state(miniState)
  }, [miniState])

  const friendRequestVMs = useMemo(
    () => requests?.map((r) => ({ id: r.fromUid, name: r.name, username: r.username, photoUrl: r.photoUrl, sentLabel: agoShort(Math.max(0, now - r.sentAt)) })) ?? null,
    [requests, now]
  )

  const unread = chats?.reduce((n, c) => n + c.unread, 0) ?? 0

  return {
    me: { uid, name: profile.displayName, username: profile.username, photoUrl: profile.photoUrl, status: myStatus, activity: myActivityLine, shareActivity: sharing },
    home,
    chats,
    header: (id) => (id === chatId ? header : null),
    messages: (id) => (id === chatId ? messages : null),
    members: (id) => (id === chatId ? members : null),
    friendPeople: friends?.map((f) => ({ id: f.id, name: f.name, username: f.username, photoUrl: f.photoUrl, online: f.online })) ?? null,
    room: roomVM,
    roomStarting,
    roomChat: roomMessages,
    navRequest,
    miniOpen,
    connection,
    alerts: {
      invites: (invites ?? []).map((v) => ({
        fromUid: v.fromUid,
        name: v.fromName,
        photoUrl: friends?.find((f) => f.id === v.fromUid)?.photoUrl ?? null,
        detail: v.title ? `Watch ${v.title} together` : 'Join their room'
      })),
      joins: (joinRequests ?? []).map((j) => ({
        fromUid: j.fromUid,
        name: j.fromName,
        photoUrl: friends?.find((f) => f.id === j.fromUid)?.photoUrl ?? null,
        detail: room.active ? 'Into the room you are in' : 'A new room with them'
      }))
    },
    prefs: {
      theme: appearance.theme,
      density: appearance.density,
      closeToTray: desktop.prefs?.closeToTray ?? null,
      globalShortcuts: desktop.prefs?.globalShortcuts ?? null,
      notifications: desktop.notifications
    },
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
      startWithWindows: desktop.startWithWindows,
      version
    },
    friends: {
      friends,
      requests: friendRequestVMs,
      sent: sent ?? null,
      blockedPeople: blockedPeople?.map((b) => ({ id: b.uid, name: b.name, username: b.username, photoUrl: b.photoUrl })) ?? null,
      blockedGroups
    },
    badges: { chats: unread || undefined, friends: requests?.length || undefined },
    profile: profileVM,
    invited,
    watchlist,
    invite: { link: inviteToken ? inviteLink(profile.username, inviteToken) : null },
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
      toggleWatchlist: (item, saved) =>
        void toggleWatchlist(item, saved).then(
          (now) => setNotice(now ? 'Saved to your watchlist' : 'Removed from your watchlist'),
          fail("Couldn't update your watchlist")
        ),
      invite: {
        copy: () => {
          if (!inviteToken) return
          void navigator.clipboard.writeText(inviteText(profile.username, inviteToken)).then(() => setNotice('Invite link copied'), fail("Couldn't copy it"))
        },
        reset: () =>
          void resetInviteToken().then((t) => {
            setInviteToken(t)
            setNotice('New link ready. Old links now send you a request instead.')
          }, fail("Couldn't reset your link"))
      },
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
      status: {
        set: (s) => void changeStatus(s).catch(fail("Couldn't change your status")),
        shareActivity: (on) => {
          setSharingActivity(on, uid)
          setSharing(on)
          setNotice(on ? 'Friends can see what you watch and play' : "Friends won't see what you watch or play")
        }
      },
      alerts: {
        join: joinInvite,
        declineInvite: (from) => void declineInvite(from).catch(fail("Couldn't answer")),
        letIn,
        declineJoin: (from) => void declineJoin(from).catch(fail("Couldn't answer"))
      },
      preferences: {
        theme: appearance.setTheme,
        density: appearance.setDensity,
        closeToTray: (on) => desktop.setPrefs({ closeToTray: on }),
        globalShortcuts: (on) => desktop.setPrefs({ globalShortcuts: on }),
        notifications: desktop.setNotifications
      },
      startRoom: (id) => void enterRoom(id),
      joinRoom: (id) => void enterRoom(id),
      leaveRoom: () => void leaveChatRoom().catch(fail("Couldn't leave the room")),
      viewing: setViewing,
      dismissNotice: () => setNotice(null),
      showNotice: setNotice,
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
        send: (text) => roomCode && void sendRoomMessage(roomCode, profile.displayName, text).catch(fail("Couldn't send that")),
        react: (emoji) => roomCode && void sendRoomReaction(roomCode, profile.displayName, emoji).catch(fail("Couldn't react")),
        toggleMini: () => window.bingejam.mini.open(!miniOpen),
        openTitle: () => void openHostTitle().catch(fail("Couldn't open the title")),
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
        startWithWindows: desktop.setStartWithWindows
      },
      friends: {
        add: async (username) => {
          // "username#token" comes from a pasted invite link: the token makes it an instant friendship.
          const [raw, via] = username.trim().replace(/^@/, '').split('#')
          const name = raw
          try {
            const r = await sendFriendRequest(name, via || undefined)
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
