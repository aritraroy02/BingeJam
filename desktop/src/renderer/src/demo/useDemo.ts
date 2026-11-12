import { useState } from 'react'
import type { ChatHeaderVM, ChatSummaryVM, GroupMemberVM, MessageVM, RoomVM, SettingsVM, StreamVM, FriendsVM, ProfileVM } from '@/ui/models'
import type { ShellData } from '@/shell'
import {
  DEMO_BROWSER,
  DEMO_CAPACITY,
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
  DEMO_STREAM,
  DEMO_STREAM_CHAT,
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
  const [stream, setStream] = useState<StreamVM>(DEMO_STREAM)
  const [streamChat, setStreamChat] = useState<MessageVM[]>(DEMO_STREAM_CHAT)
  const [settings, setSettings] = useState<SettingsVM>(DEMO_SETTINGS)
  const [friends, setFriends] = useState<FriendsVM>(DEMO_FRIENDS)

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
    stream: {
      source: (source) => setStream((s) => ({ ...s, source })),
      quality: (quality) => setStream((s) => ({ ...s, quality, capacityTitle: DEMO_CAPACITY[quality] })),
      start: () => setStream((s) => ({ ...s, streaming: true, liveHost: { ...DEMO_PEOPLE.me }, liveDetail: 'Your game · direct connection', viewers: 0 })),
      stop: () => setStream(DEMO_STREAM),
      send: (text) => setStreamChat((m) => [...m, myText(text)])
    },
    addToBrowser: () => undefined,
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
  return {
    me: DEMO_ME,
    home: DEMO_HOME,
    chats,
    header: (id) => headers[id] ?? null,
    messages: (id) => messages[id] ?? null,
    members: (id) => members[id] ?? null,
    friendPeople: people,
    room,
    roomChat,
    stream,
    streamChat,
    media: null,
    browser: DEMO_BROWSER,
    settings,
    friends,
    badges: { chats: unread || undefined, friends: friends.requests?.length || undefined },
    profile: demoProfile(profileUid, friends),
    myBirthDate: '2001-03-12',
    act
  }
}

/** A demo friend's profile for the profile dialog. */
function demoProfile(uid: string | null, friends: FriendsVM): ProfileVM | null {
  if (!uid) return null
  const f = friends.friends?.find((x) => x.id === uid)
  if (!f) return { id: uid, name: 'Someone', username: 'someone', bio: null, photoUrl: null, relation: 'none' }
  return { id: f.id, name: f.name, username: f.username ?? '', bio: 'Here for the plot twists', photoUrl: f.photoUrl ?? null, relation: 'friend', status: f.status, since: 'Friends since Mar 2026' }
}
