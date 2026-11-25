import { useState } from 'react'
import { useAppearance } from '@/live/appearance'
import type {
  ChannelVM,
  ChatHeaderVM,
  ChatSummaryVM,
  GroupChannelsVM,
  GroupMemberVM,
  MessageVM,
  RoomVM,
  SettingsVM,
  FriendsVM,
  ProfileVM,
  VoiceConnectionVM
} from '@/ui/models'
import { DEMO_CHANNEL_MESSAGES, DEMO_CHANNELS, DEMO_VOICE } from './channels'
import { DEMO_SOURCES, demoScreen } from './screens'
import { useUpdateState } from '@/live/updates'
import type { ShellData } from '@/shell'
import {
  DEMO_BROWSER,
  DEMO_CHATS,
  DEMO_FRIENDS,
  DEMO_HEADERS,
  DEMO_HOME,
  DEMO_ME,
  DEMO_MEMBERS,
  DEMO_MESSAGES,
  DEMO_PEOPLE,
  DEMO_ROOM,
  DEMO_ROOM_CHAT,
  DEMO_SETTINGS,
  demoNewRoom
} from './sample'

// Demo mode with buttons that do something: sending adds a bubble, Ping turns into "Pinged",
// Start room opens a room, and so on. All in memory; nothing leaves the window.

let seq = 0
const newId = (prefix: string): string => `${prefix}-${Date.now().toString(36)}-${seq++}`

function myText(text: string): MessageVM {
  return { kind: 'text', id: newId('m'), fromMe: true, senderId: DEMO_ME.uid, senderName: 'You', text, sentAt: Date.now() }
}

export function useDemo(profileUid: string | null = null): ShellData {
  const [chats, setChats] = useState<ChatSummaryVM[]>(DEMO_CHATS)
  const [headers, setHeaders] = useState<Record<string, ChatHeaderVM>>(DEMO_HEADERS)
  const [messages, setMessages] = useState<Record<string, MessageVM[]>>(DEMO_MESSAGES)
  const [members, setMembers] = useState<Record<string, GroupMemberVM[]>>(DEMO_MEMBERS)
  const [room, setRoom] = useState<RoomVM | null>(null)
  const [roomChat, setRoomChat] = useState<MessageVM[]>(DEMO_ROOM_CHAT)
  const [settings, setSettings] = useState<SettingsVM>(DEMO_SETTINGS)
  const [friends, setFriends] = useState<FriendsVM>(DEMO_FRIENDS)
  const [channels, setChannels] = useState<Record<string, GroupChannelsVM>>(DEMO_CHANNELS)
  const [channelMessages, setChannelMessages] = useState<Record<string, MessageVM[]>>(DEMO_CHANNEL_MESSAGES)
  // In voice from the start only for the channel and voice snapshots.
  // ?screen=stream: already watching Riya's screen (snapshots).
  const startWatching = /screen=stream/.test(window.location.search)
  const [voice, setVoice] = useState<VoiceConnectionVM | null>(() =>
    /screen=(voice|channel|stream|golive)/.test(window.location.search)
      ? startWatching
        ? { ...DEMO_VOICE, watching: { uid: DEMO_PEOPLE.riya.id, name: 'Riya Sen', status: 'playing' } }
        : DEMO_VOICE
      : null
  )
  const [voiceMedia, setVoiceMedia] = useState<{ watching: MediaStream | null; preview: MediaStream | null }>(() => ({
    watching: startWatching ? demoScreen('Skyline Racers · Riya') : null,
    preview: null
  }))
  /** Mark us live (or not) in the voice channel we're in. */
  function setMeLive(live: boolean): void {
    setChannels((all) => {
      const next: Record<string, GroupChannelsVM> = {}
      for (const [id, g] of Object.entries(all))
        next[id] = { ...g, voice: g.voice.map((v) => ({ ...v, people: (v.people ?? []).map((p) => (p.isMe ? { ...p, live } : p)) })) }
      return next
    })
  }

  /** A group's channels: Movie Night's sample, or just #general and General for the others. */
  function channelsOf(chatId: string): GroupChannelsVM | null {
    if (headers[chatId]?.kind !== 'group') return null
    return (
      channels[chatId] ?? {
        chatId,
        canManage: true,
        text: [{ id: 'general', name: 'general', kind: 'text', builtIn: true }],
        voice: [{ id: 'lounge', name: 'General', kind: 'voice', builtIn: true, people: [], elapsed: null, status: null }]
      }
    )
  }
  function patchChannels(chatId: string, fn: (c: GroupChannelsVM) => GroupChannelsVM): void {
    const cur = channelsOf(chatId)
    if (cur) setChannels((all) => ({ ...all, [chatId]: fn(cur) }))
  }
  /** Us in or out of a voice channel (the demo keeps only one connection, like the app). */
  function placeMe(chatId: string | null, channelId: string | null): void {
    setChannels((all) => {
      const next: Record<string, GroupChannelsVM> = {}
      for (const [id, g] of Object.entries(all)) {
        next[id] = {
          ...g,
          voice: g.voice.map((v): ChannelVM => {
            const others = (v.people ?? []).filter((p) => !p.isMe)
            const here = id === chatId && v.id === channelId
            return {
              ...v,
              connected: here,
              people: here ? [...others, { id: DEMO_ME.uid, name: 'You', isMe: true, muted: false, deafened: false, speaking: false }] : others,
              elapsed: here ? v.elapsed ?? '0:00' : others.length > 0 ? v.elapsed : null
            }
          })
        }
      }
      return next
    })
  }

  const people = [DEMO_PEOPLE.riya, DEMO_PEOPLE.kabir, DEMO_PEOPLE.meera, DEMO_PEOPLE.zoe, DEMO_PEOPLE.ananya]

  function touchChat(chatId: string, preview: string): void {
    setChats((cs) => cs.map((c) => (c.chatId === chatId ? { ...c, preview, timeLabel: 'now', sortKey: Date.now(), unread: 0 } : c)))
  }
  function patchRoom(fn: (r: RoomVM) => RoomVM): void {
    setRoom((r) => (r ? fn(r) : r))
  }

  const act: ShellData['act'] = {
    send: (chatId, text) => {
      setMessages((all) => ({ ...all, [chatId]: [...(all[chatId] ?? []), myText(text)] }))
      touchChat(chatId, `You: ${text}`)
    },
    typing: () => undefined,
    ping: (chatId, uid) => {
      const pinged = (m: GroupMemberVM): GroupMemberVM => (m.id === uid ? { ...m, ping: { pingedLabel: 'Pinged · now' } } : m)
      setMembers((all) => ({ ...all, [chatId]: (all[chatId] ?? []).map(pinged) }))
      patchRoom((r) => ({ ...r, notHere: r.notHere.map(pinged) }))
    },
    rename: (chatId, name) => {
      setHeaders((h) => ({ ...h, [chatId]: { ...h[chatId], name } }))
      setChats((cs) => cs.map((c) => (c.chatId === chatId ? { ...c, name } : c)))
    },
    newGroup: (name, ids) => {
      const chatId = newId('g-demo')
      const picked = people.filter((p) => ids.includes(p.id))
      const count = picked.length + 1
      setChats((cs) => [
        { chatId, kind: 'group', name, people: picked.slice(0, 2), preview: 'You created the group', timeLabel: 'now', unread: 0, liveRoom: null, sortKey: Date.now() },
        ...cs
      ])
      setHeaders((h) => ({
        ...h,
        [chatId]: { chatId, kind: 'group', name, people: picked.slice(0, 2), subtitle: count === 1 ? 'Just you' : `${count} members`, room: null, canRename: true }
      }))
      setMessages((all) => ({ ...all, [chatId]: [] }))
      setMembers((all) => ({
        ...all,
        [chatId]: [
          { ...DEMO_PEOPLE.me, detail: 'Admin', inRoom: false, isMe: true, ping: null },
          ...picked.map((p) => ({ ...p, detail: p.online ? 'Online' : 'Offline', inRoom: false, isMe: false, ping: null }))
        ]
      }))
      return chatId
    },
    addPeople: (chatId, ids) => {
      const picked = people.filter((p) => ids.includes(p.id))
      setMembers((all) => {
        const list = [...(all[chatId] ?? []), ...picked.map((p) => ({ ...p, detail: p.online ? 'Online' : 'Offline', inRoom: false, isMe: false, ping: null }))]
        setHeaders((h) => {
          const cur = h[chatId]
          const others = list.filter((m) => !m.isMe)
          return { ...h, [chatId]: { ...cur, people: cur.people.length >= 2 ? cur.people : others.slice(0, 2), subtitle: `${list.length} members` } }
        })
        return { ...all, [chatId]: list }
      })
    },
    deleteChat: (chatId) => setChats((cs) => cs.filter((c) => c.chatId !== chatId)),
    startRoom: (chatId) => {
      const header = headers[chatId]
      if (!header) return
      const r = demoNewRoom(header)
      setRoom(r)
      setRoomChat(messages[chatId] ?? [])
      setHeaders((h) => ({
        ...h,
        [chatId]: { ...header, room: { code: r.code, inCount: 1, startedAtLabel: 'now', summary: 'Nothing playing yet', names: [DEMO_PEOPLE.me], meInRoom: true } }
      }))
      setChats((cs) => cs.map((c) => (c.chatId === chatId ? { ...c, liveRoom: { inCount: 1 } } : c)))
    },
    joinRoom: (chatId) => {
      if (room?.chatId === chatId) return
      setRoom(chatId === DEMO_ROOM.chatId ? DEMO_ROOM : demoNewRoom(headers[chatId]))
      setHeaders((h) => {
        const cur = h[chatId]
        return cur?.room ? { ...h, [chatId]: { ...cur, room: { ...cur.room, meInRoom: true } } } : h
      })
    },
    leaveRoom: () => {
      const chatId = room?.chatId
      setRoom(null)
      if (!chatId) return
      setHeaders((h) => {
        const cur = h[chatId]
        if (!cur?.room) return h
        // A room you started alone ends when you leave; a group's room stays open for the others.
        const alone = cur.room.inCount <= 1
        return { ...h, [chatId]: { ...cur, room: alone ? null : { ...cur.room, meInRoom: false } } }
      })
      setChats((cs) => cs.map((c) => (c.chatId === chatId && c.liveRoom && c.liveRoom.inCount <= 1 ? { ...c, liveRoom: null } : c)))
    },
    room: {
      send: (text) => setRoomChat((m) => [...m, myText(text)]),
      togglePlay: () => patchRoom((r) => ({ ...r, playing: !r.playing })),
      seekBy: (d) => patchRoom((r) => ({ ...r, positionMs: Math.max(0, Math.min(r.durationMs ?? Infinity, r.positionMs + d)) })),
      seekTo: (ms) => patchRoom((r) => ({ ...r, positionMs: ms })),
      subtitles: () => patchRoom((r) => ({ ...r, settings: { ...r.settings, subtitles: r.settings.subtitles ? null : 'English' } })),
      waitForBuffering: (on) => patchRoom((r) => ({ ...r, settings: { ...r.settings, waitForBuffering: on } })),
      syncAllToMe: () => patchRoom((r) => ({ ...r, members: r.members.map((m) => ({ ...m, syncLabel: 'In sync', health: 'inSync' as const })) })),
      invite: () => undefined,
      showPlayer: () => undefined,
      toggleMute: () =>
        patchRoom((r) => ({
          ...r,
          voice: { ...r.voice, muted: !r.voice.muted, members: r.voice.members.map((m) => (m.isMe ? { ...m, muted: !r.voice.muted } : m)) }
        })),
      joinVoice: () => patchRoom((r) => ({ ...r, voice: { ...r.voice, joined: true } }))
    },
    addToBrowser: () => undefined,
    invite: { copy: () => undefined, reset: () => undefined },
    toggleWatchlist: () => undefined,
    channels: {
      send: (chatId, channelId, text) => setChannelMessages((all) => ({ ...all, [`${chatId}#${channelId}`]: [...(all[`${chatId}#${channelId}`] ?? []), myText(text)] })),
      create: (chatId, name, kind) =>
        patchChannels(chatId, (c) => {
          const clean = kind === 'text' ? name.toLowerCase().replace(/[^a-z0-9 _-]/g, '').trim().replace(/[ -]+/g, '-') : name
          const id = newId('ch')
          return kind === 'text'
            ? { ...c, text: [...c.text, { id, name: clean, kind, builtIn: false }] }
            : { ...c, voice: [...c.voice, { id, name: clean, kind, builtIn: false, people: [], elapsed: null, status: null }] }
        }),
      rename: (chatId, channelId, name) =>
        patchChannels(chatId, (c) => ({
          ...c,
          text: c.text.map((x) => (x.id === channelId ? { ...x, name } : x)),
          voice: c.voice.map((x) => (x.id === channelId ? { ...x, name } : x))
        })),
      remove: (chatId, channelId) => {
        patchChannels(chatId, (c) => ({ ...c, text: c.text.filter((x) => x.id !== channelId), voice: c.voice.filter((x) => x.id !== channelId) }))
        if (voice?.target.kind === 'channel' && voice.target.channelId === channelId) setVoice(null)
      },
      setStatus: (chatId, channelId, text) =>
        patchChannels(chatId, (c) => ({ ...c, voice: c.voice.map((x) => (x.id === channelId ? { ...x, status: text.trim() || null } : x)) }))
    },
    voice: {
      joinChannel: (chatId, channelId) => {
        const c = channelsOf(chatId)?.voice.find((v) => v.id === channelId)
        placeMe(chatId, channelId)
        setVoice({ ...DEMO_VOICE, channelName: c?.name ?? 'Voice', placeName: headers[chatId]?.name ?? 'Group', target: { kind: 'channel', chatId, channelId } })
      },
      leave: () => {
        placeMe(null, null)
        setVoice(null)
      },
      toggleMute: () => setVoice((v) => (v ? { ...v, muted: !v.muted, deafened: v.muted ? false : v.deafened } : v)),
      toggleDeafen: () => setVoice((v) => (v ? { ...v, deafened: !v.deafened, muted: !v.deafened } : v)),
      listSources: async () => {
        await new Promise((r) => setTimeout(r, 300))
        return DEMO_SOURCES
      },
      startShare: (sourceId, quality) => {
        const name = DEMO_SOURCES.find((s) => s.id === sourceId)?.name ?? 'Your screen'
        voiceMedia.preview?.getTracks().forEach((t) => t.stop())
        setVoiceMedia((m) => ({ ...m, preview: demoScreen(name) }))
        setVoice((v) => (v ? { ...v, sharing: { quality: quality === '4k60' ? '4K · 60' : quality.replace(/p(\d+)$/, 'p · $1'), viewers: 2 } } : v))
        setMeLive(true)
      },
      stopShare: () => {
        voiceMedia.preview?.getTracks().forEach((t) => t.stop())
        setVoiceMedia((m) => ({ ...m, preview: null }))
        setVoice((v) => (v ? { ...v, sharing: null } : v))
        setMeLive(false)
      },
      watch: (uid) => {
        const name = Object.values(channels).flatMap((g) => g.voice.flatMap((v) => v.people ?? [])).find((p) => p.id === uid)?.name ?? 'Friend'
        setVoiceMedia((m) => ({ ...m, watching: demoScreen(`Skyline Racers · ${name.split(' ')[0]}`) }))
        setVoice((v) => (v ? { ...v, watching: { uid, name, status: 'playing' } } : v))
      },
      stopWatching: () => {
        voiceMedia.watching?.getTracks().forEach((t) => t.stop())
        setVoiceMedia((m) => ({ ...m, watching: null }))
        setVoice((v) => (v ? { ...v, watching: null } : v))
      }
    },
    groups: {
      setAdmin: (chatId, uid, admin) => setMembers((all) => ({ ...all, [chatId]: (all[chatId] ?? []).map((m) => (m.id === uid ? { ...m, admin } : m)) })),
      remove: (chatId, uid) => setMembers((all) => ({ ...all, [chatId]: (all[chatId] ?? []).filter((m) => m.id !== uid) })),
      settings: (chatId, onlyAdminsSend, onlyAdminsEdit) =>
        setHeaders((all) => {
          const h = all[chatId]
          return h ? { ...all, [chatId]: { ...h, group: { amAdmin: true, onlyAdminsSend, onlyAdminsEdit } } } : all
        })
    },
    messages: {
      forward: () => undefined,
      deleteForMe: (chatId, messageId) => setMessages((all) => ({ ...all, [chatId]: (all[chatId] ?? []).filter((m) => m.id !== messageId) })),
      deleteForEveryone: (chatId, messageId) =>
        setMessages((all) => ({ ...all, [chatId]: (all[chatId] ?? []).map((m) => (m.id === messageId && m.kind === 'text' ? { ...m, deleted: true, text: '' } : m)) }))
    },
    profile: {
      add: () => undefined,
      accept: () => undefined,
      cancel: () => undefined,
      unblock: () => undefined,
      unfriend: () => undefined,
      block: () => undefined,
      report: () => undefined
    },
    editProfile: {
      save: async () => true,
      pickPhoto: () => undefined,
      removePhoto: () => undefined
    },
    safety: {
      block: (chatId) => setChats((cs) => cs.filter((c) => c.chatId !== chatId)),
      blockGroup: (chatId) => setChats((cs) => cs.filter((c) => c.chatId !== chatId)),
      report: () => undefined,
      unblockUser: () => undefined,
      unblockGroup: () => undefined
    },
    settings: {
      signOut: () => undefined,
      inputDevice: (inputId) => setSettings((s) => ({ ...s, voice: { ...s.voice, inputId } })),
      outputDevice: (outputId) => setSettings((s) => ({ ...s, voice: { ...s.voice, outputId } })),
      pushToTalkKey: (pushToTalkKey) => setSettings((s) => ({ ...s, voice: { ...s.voice, pushToTalkKey } })),
      startWithWindows: (on) => setSettings((s) => ({ ...s, startWithWindows: on }))
    },
    friends: {
      add: async (username) => {
        await new Promise((r) => setTimeout(r, 400))
        if (username === DEMO_ME.username) return { ok: false, message: "That's you." }
        if (username === 'nobody') return { ok: false, message: 'No one has that username. Check the spelling with your friend.' }
        setFriends((f) => ({ ...f, sent: [...(f.sent ?? []), { id: newId('demo-u'), name: `@${username}`, username, sentLabel: 'Sent just now' }] }))
        return { ok: true, message: `Request sent to @${username}.` }
      },
      accept: (uid) =>
        setFriends((f) => {
          const r = f.requests?.find((x) => x.id === uid)
          return {
            ...f,
            requests: (f.requests ?? []).filter((x) => x.id !== uid),
            friends: r ? [...(f.friends ?? []), { id: r.id, name: r.name, username: r.username, status: 'Online', online: true, action: null }] : f.friends
          }
        }),
      decline: (uid) => setFriends((f) => ({ ...f, requests: (f.requests ?? []).filter((x) => x.id !== uid) })),
      cancel: (uid) => setFriends((f) => ({ ...f, sent: (f.sent ?? []).filter((x) => x.id !== uid) }))
    }
  }

  const unread = chats.reduce((n, c) => n + c.unread, 0)
  const appearance = useAppearance()
  // The real updater (it stays idle unless a test feed is set; see desktop/README).
  const update = useUpdateState()
  return {
    me: DEMO_ME,
    home: DEMO_HOME,
    chats,
    header: (id) => headers[id] ?? null,
    messages: (id) => messages[id] ?? null,
    members: (id) => members[id] ?? null,
    channels: channelsOf,
    channelMessages: (id, channelId) => channelMessages[`${id}#${channelId}`] ?? [],
    voice,
    voiceMedia,
    update,
    friendPeople: people,
    room,
    roomChat,
    browser: DEMO_BROWSER,
    settings,
    friends,
    badges: { chats: unread || undefined, friends: friends.requests?.length || undefined },
    profile: demoProfile(profileUid, friends),
    invite: { link: 'https://syncit-watchparty.web.app/add/alex/Kq7mPz2xWb4R' },
    watchlist: [],
    myBirthDate: '2001-03-12',
    prefs: { theme: appearance.theme, density: appearance.density, closeToTray: true, globalShortcuts: true, notifications: true },
    alerts: DEMO_ALERTS,
    act: {
      ...act,
      update: { check: () => void window.bingejam.update?.check(), install: () => void window.bingejam.update?.install() },
      preferences: {
        theme: appearance.setTheme,
        density: appearance.setDensity,
        closeToTray: () => undefined,
        globalShortcuts: () => undefined,
        notifications: () => undefined
      },
      alerts: { join: () => undefined, declineInvite: () => undefined, letIn: () => undefined, declineJoin: () => undefined },
      room: { ...act.room, react: () => undefined, toggleMini: () => undefined }
    }
  }
}

/** A demo friend's profile for the profile dialog. */
function demoProfile(uid: string | null, friends: FriendsVM): ProfileVM | null {
  if (!uid) return null
  const f = friends.friends?.find((x) => x.id === uid)
  if (!f) return { id: uid, name: 'Someone', username: 'someone', bio: null, photoUrl: null, relation: 'none' }
  return { id: f.id, name: f.name, username: f.username ?? '', bio: 'Here for the plot twists', photoUrl: f.photoUrl ?? null, relation: 'friend', status: f.status, since: 'Friends since Mar 2026' }
}

/** BJ_ALERTS=1 shows the invite / join-request banners in demo snapshots. */
const DEMO_ALERTS = new URLSearchParams(window.location.search).get('alerts') === '1'
  ? {
      invites: [{ fromUid: 'riya', name: 'Riya', photoUrl: null, detail: 'Watch Midnight Signal together' }],
      joins: [{ fromUid: 'kabir', name: 'Kabir', photoUrl: null, detail: 'Into the room you are in' }]
    }
  : { invites: [], joins: [] }
