import type {
  BrowserSetupVM,
  ChatHeaderVM,
  ChatSummaryVM,
  FriendsVM,
  GroupMemberVM,
  HomeVM,
  MessageVM,
  PersonVM,
  RoomVM,
  SettingsVM,
  StreamVM
} from '@/ui/models'

// Demo mode (`BJ_DEMO=1`): the design's sample content with made-up names and titles, for store
// screenshots and design reviews. Nothing here touches Firebase or real people.
// The ids are picked so the avatar colours match the mock-ups (Riya green, Kabir orange, Meera pink,
// Zoe indigo): see AVATAR_GRADIENTS in ui/primitives.tsx.

export const DEMO_ME = { uid: 'demo-alex', name: 'Alex Sharma', username: 'alex', photoUrl: null }

const riya: PersonVM = { id: 'demo-riya-4', name: 'Riya Sen', username: 'riya.sen', online: true }
const kabir: PersonVM = { id: 'demo-kabir-3', name: 'Kabir Mehta', username: 'kabir', online: true }
const meera: PersonVM = { id: 'demo-meera', name: 'Meera Kapoor', username: 'meera.k', online: true }
const zoe: PersonVM = { id: 'demo-zoe-2', name: 'Zoe Williams', username: 'zoe', online: true }
const ananya: PersonVM = { id: 'demo-ananya-8', name: 'Ananya Iyer', username: 'ananya', online: false }
const me: PersonVM = { id: DEMO_ME.uid, name: DEMO_ME.name, username: DEMO_ME.username, online: true }
export const DEMO_PEOPLE = { riya, kabir, meera, zoe, ananya, me }

export const DEMO_HOME: HomeVM = {
  continueWatching: {
    title: 'The Last Monsoon',
    subtitle: 'With Riya and Kabir · stopped at 1:12:40 · Prime Video',
    progress: 0.58,
    leftLabel: '51 min left',
    people: [riya, kabir],
    gradient: 'linear-gradient(165deg, #3B0A12, #9F1239 55%, #FDBA74 140%)'
  },
  liveRoom: { chatId: 'g-demo-movie-night', chatName: 'Movie Night' },
  trending: [
    { id: 't1', title: 'Midnight Signal', service: 'Netflix', rating: 8.1, gradient: 'linear-gradient(170deg, #0F172A, #1E40AF 60%, #F43F5E 150%)' },
    { id: 't2', title: 'Paper Kites', service: 'JioHotstar', rating: 7.6, gradient: 'linear-gradient(170deg, #052E16, #15803D 55%, #FACC15 150%)' },
    { id: 't3', title: 'Salt & Ember', service: 'Prime Video', rating: 7.9, gradient: 'linear-gradient(170deg, #2E1065, #7E22CE 55%, #F472B6 150%)' },
    { id: 't4', title: 'Northbound', service: 'Netflix', rating: 6.8, gradient: 'linear-gradient(170deg, #1C1917, #92400E 55%, #FB923C 150%)' }
  ],
  friends: [
    { ...riya, status: 'Midnight Signal · S2:E4 · Netflix', action: 'join' },
    { ...kabir, status: 'Live · Skyline Racers', streaming: true, action: 'watch' },
    { ...meera, status: 'Neon Streets · Spotify', action: 'jam' },
    { ...zoe, status: 'Online', action: null }
  ],
  onlineCount: 4
}

// ---------- Chats ----------

const MIN = 60_000
const DAY = 24 * 60 * MIN
/** Today 9:02 pm local, so the times in tooltips read like the design. */
const TODAY_2102 = (() => {
  const d = new Date()
  d.setHours(21, 2, 0, 0)
  return d.getTime()
})()

export const MOVIE_NIGHT = 'g-demo-movie-night'
export const RIYA_DM = 'demo-alex_demo-riya-4'
const KABIR_DM = 'demo-alex_demo-kabir-3'
const COLLEGE_GANG = 'demo-college-gang-7'
const MEERA_DM = 'demo-alex_demo-meera'
/** A group made with nobody but its creator (allowed: "Just you · add people"). */
export const ROAD_TRIP = 'g-demo-road-trip'

export const DEMO_CHATS: ChatSummaryVM[] = [
  { chatId: MOVIE_NIGHT, kind: 'group', name: 'Movie Night', people: [riya, kabir], preview: 'Meera: Save me a seat', timeLabel: '9:02', unread: 0, liveRoom: { inCount: 3 }, sortKey: TODAY_2102 },
  { chatId: RIYA_DM, kind: 'dm', name: 'Riya Sen', people: [riya], preview: 'You: Count me in', timeLabel: '8:47', unread: 0, liveRoom: null, sortKey: TODAY_2102 - 15 * MIN },
  { chatId: KABIR_DM, kind: 'dm', name: 'Kabir Mehta', people: [kabir], preview: 'Watch this lap', timeLabel: '8:30', unread: 2, liveRoom: null, sortKey: TODAY_2102 - 32 * MIN },
  { chatId: COLLEGE_GANG, kind: 'group', name: 'College Gang', people: [], preview: "Meera: who's free Saturday?", timeLabel: 'Mon', unread: 0, liveRoom: null, sortKey: TODAY_2102 - 2 * DAY },
  { chatId: MEERA_DM, kind: 'dm', name: 'Meera Kapoor', people: [meera], preview: 'Loved the finale', timeLabel: 'Sun', unread: 0, liveRoom: null, sortKey: TODAY_2102 - 3 * DAY },
  { chatId: ROAD_TRIP, kind: 'group', name: 'Road Trip', people: [], preview: 'You created the group', timeLabel: 'Sat', unread: 0, liveRoom: null, sortKey: TODAY_2102 - 4 * DAY }
]

const text = (id: string, from: PersonVM, body: string, sentAt: number): MessageVM => ({
  kind: 'text',
  id,
  fromMe: from.id === me.id,
  senderId: from.id,
  senderName: from.name.split(' ')[0],
  text: body,
  sentAt
})

export const DEMO_MESSAGES: Record<string, MessageVM[]> = {
  [RIYA_DM]: [
    { kind: 'day', id: 'd1', label: 'Yesterday' },
    { kind: 'watched', id: 'w1', title: 'The Last Monsoon', durationLabel: '1 h 42 m', detail: 'in your room', sentAt: TODAY_2102 - DAY },
    { kind: 'day', id: 'd2', label: 'Today' },
    text('r1', riya, 'Movie night at 9? Episode 4 next', TODAY_2102 - 20 * MIN),
    text('r2', riya, "I'll bring the snacks this time", TODAY_2102 - 19 * MIN),
    text('r3', me, 'Count me in', TODAY_2102 - 15 * MIN)
  ],
  [MOVIE_NIGHT]: [
    { kind: 'event', id: 'e1', text: 'Riya started a room · 9:02', sentAt: TODAY_2102 },
    text('m1', riya, 'Starting episode 4, hop in whenever', TODAY_2102 + MIN),
    { kind: 'event', id: 'e2', text: 'Kabir joined from his phone', sentAt: TODAY_2102 + 2 * MIN },
    text('m2', meera, 'Save me a seat', TODAY_2102 + 3 * MIN),
    { kind: 'event', id: 'e3', text: 'Riya pinged Zoe and Ananya', sentAt: TODAY_2102 + 4 * MIN }
  ],
  [KABIR_DM]: [{ kind: 'day', id: 'd1', label: 'Today' }, text('k1', kabir, 'You up for a race later?', TODAY_2102 - 33 * MIN), text('k2', kabir, 'Watch this lap', TODAY_2102 - 32 * MIN)],
  [COLLEGE_GANG]: [{ kind: 'day', id: 'd1', label: 'Monday' }, text('c1', meera, "who's free Saturday?", TODAY_2102 - 2 * DAY)],
  [MEERA_DM]: [{ kind: 'day', id: 'd1', label: 'Sunday' }, text('x1', meera, 'Loved the finale', TODAY_2102 - 3 * DAY)],
  [ROAD_TRIP]: []
}

export const DEMO_HEADERS: Record<string, ChatHeaderVM> = {
  [RIYA_DM]: { chatId: RIYA_DM, kind: 'dm', name: 'Riya Sen', people: [riya], subtitle: 'Watching Midnight Signal · Netflix', room: null },
  [KABIR_DM]: { chatId: KABIR_DM, kind: 'dm', name: 'Kabir Mehta', people: [kabir], subtitle: 'Live · Skyline Racers', room: null },
  [MEERA_DM]: { chatId: MEERA_DM, kind: 'dm', name: 'Meera Kapoor', people: [meera], subtitle: 'Neon Streets · Spotify', room: null },
  [MOVIE_NIGHT]: {
    chatId: MOVIE_NIGHT,
    kind: 'group',
    name: 'Movie Night',
    people: [riya, kabir],
    subtitle: '6 members · 3 in the room',
    room: { code: '7KQ2MX', inCount: 3, startedAtLabel: '18 min', summary: 'Watching Midnight Signal · S2:E4 · Netflix · talking in voice', names: [riya, kabir, meera] },
    canRename: true
  },
  [COLLEGE_GANG]: { chatId: COLLEGE_GANG, kind: 'group', name: 'College Gang', people: [], subtitle: '4 members', room: null, canRename: true },
  [ROAD_TRIP]: { chatId: ROAD_TRIP, kind: 'group', name: 'Road Trip', people: [], subtitle: 'Just you', room: null, canRename: true }
}

export const DEMO_MEMBERS: Record<string, GroupMemberVM[]> = {
  [MOVIE_NIGHT]: [
    { ...riya, detail: 'Started the room', inRoom: true, isMe: false, ping: null },
    { ...kabir, detail: 'On his phone', inRoom: true, isMe: false, ping: null },
    { ...meera, detail: 'Talking', inRoom: true, isMe: false, ping: null },
    { ...me, detail: 'Join any time', inRoom: false, isMe: true, ping: null },
    { ...zoe, detail: 'Online', inRoom: false, isMe: false, ping: 'can' },
    { ...ananya, detail: 'Offline · 2 h', inRoom: false, isMe: false, ping: { pingedLabel: 'Pinged · 1 min' } }
  ],
  [ROAD_TRIP]: [{ ...me, detail: 'Admin', inRoom: false, isMe: true, ping: null }],
  [COLLEGE_GANG]: [
    { ...me, detail: 'Admin', inRoom: false, isMe: true, ping: null },
    { ...meera, detail: 'Online', inRoom: false, isMe: false, ping: null },
    { ...zoe, detail: 'Online', inRoom: false, isMe: false, ping: null },
    { ...ananya, detail: 'Offline · 2 h', inRoom: false, isMe: false, ping: null }
  ]
}

// ---------- Room ----------

export const DEMO_ROOM: RoomVM = {
  code: '7KQ2MX',
  chatId: MOVIE_NIGHT,
  context: 'Movie Night · group room · started by Riya',
  title: 'Midnight Signal · S2:E4',
  service: 'Netflix',
  playerLabel: 'Playing in your Netflix tab',
  playing: true,
  positionMs: 24 * MIN + 13_000,
  durationMs: 52 * MIN,
  syncLabel: 'All in sync · ±0.2 s',
  allInSync: true,
  members: [
    { ...riya, device: 'Laptop · Edge', isHost: true, isMe: false, syncLabel: 'In sync', health: 'inSync' },
    { ...me, device: 'This PC · Edge', isHost: false, isMe: true, syncLabel: '+0.1 s', health: 'inSync' },
    { ...kabir, device: 'Phone · Netflix app', isHost: false, isMe: false, syncLabel: 'Catching up', health: 'catchingUp' }
  ],
  notHere: [
    { ...zoe, detail: 'Online', inRoom: false, isMe: false, ping: 'can' },
    { ...ananya, detail: 'Offline', inRoom: false, isMe: false, ping: { pingedLabel: 'Pinged' } }
  ],
  voice: {
    members: [
      { ...riya, speaking: true, muted: false, isMe: false },
      { ...me, speaking: false, muted: false, isMe: true },
      { ...kabir, speaking: false, muted: true, isMe: false }
    ],
    quality: 'HD',
    pushToTalkKey: 'V',
    joined: true,
    muted: false
  },
  settings: { waitForBuffering: false, subtitles: 'English' }
}

export const DEMO_ROOM_CHAT: MessageVM[] = [
  text('rc1', riya, 'That cliffhanger last week though', TODAY_2102 + 5 * MIN),
  text('rc2', kabir, 'Joining from my phone today', TODAY_2102 + 6 * MIN),
  text('rc3', me, 'Popcorn ready. Hit play', TODAY_2102 + 7 * MIN),
  { kind: 'event', id: 'rc4', text: 'Riya pressed play · 24:13', sentAt: TODAY_2102 + 8 * MIN }
]

/** A fresh room started from a 1:1 chat: nothing chosen yet, the friend not in yet. */
export function demoNewRoom(header: ChatHeaderVM): RoomVM {
  const friend = header.people[0]
  return {
    code: 'Q4W8ZD',
    chatId: header.chatId,
    context: `${header.name} · your room`,
    title: '',
    service: null,
    playerLabel: 'Open a title in your browser',
    playerAction: 'Open Netflix',
    playing: false,
    positionMs: 0,
    durationMs: null,
    syncLabel: 'Just you so far',
    allInSync: true,
    members: [{ ...me, device: 'This PC · Edge', isHost: true, isMe: true, syncLabel: 'No title yet', health: 'noPlayer' }],
    notHere: friend ? [{ ...friend, detail: friend.online ? 'Online' : 'Offline', inRoom: false, isMe: false, ping: 'can' }] : [],
    voice: { members: [{ ...me, speaking: false, muted: false, isMe: true }], quality: 'HD', pushToTalkKey: 'V', joined: true, muted: false },
    settings: { waitForBuffering: true, subtitles: null }
  }
}

// ---------- Streams ----------

export const DEMO_STREAM: StreamVM = {
  liveHost: kabir,
  liveDetail: 'Skyline Racers · direct connection',
  stats: '4K · 60 fps · 41 ms',
  viewers: 2,
  source: 'game',
  quality: '2160p60',
  capacityTitle: '4K · 60 needs about 25 Mbps upload per viewer',
  capacityDetail: 'Your connection: 100 Mbps up · good for 2 viewers at 4K, 6 at 1080p. Quality drops on its own if the line gets busy.',
  streaming: false,
  watchers: [me, meera]
}

export const DEMO_STREAM_CHAT: MessageVM[] = [
  text('s1', meera, 'Take the inside line on the bridge', TODAY_2102 + 10 * MIN),
  text('s2', kabir, 'Watch this', TODAY_2102 + 11 * MIN),
  text('s3', me, 'That drift was clean', TODAY_2102 + 12 * MIN)
]

export const DEMO_CAPACITY: Record<StreamVM['quality'], string> = {
  '1080p60': '1080p · 60 needs about 8 Mbps upload per viewer',
  '1440p60': '1440p · 60 needs about 14 Mbps upload per viewer',
  '2160p60': '4K · 60 needs about 25 Mbps upload per viewer'
}

// ---------- Add to browser, Settings, Friends ----------

export const DEMO_BROWSER: BrowserSetupVM = {
  found: [
    { id: 'edge', name: 'Microsoft Edge' },
    { id: 'chrome', name: 'Google Chrome' }
  ],
  connected: { browser: 'Microsoft Edge', detail: 'BingeJam can see your Netflix tab.' },
  popup: { roomCode: '7KQ2MX', people: 'Riya, Kabir', title: 'Midnight Signal · S2:E4', position: '24:13', inSync: true }
}

export const DEMO_SETTINGS: SettingsVM = {
  account: { uid: DEMO_ME.uid, name: DEMO_ME.name, username: DEMO_ME.username, email: 'alex@example.com', photoUrl: null },
  extension: { connected: true, detail: 'Edge · Netflix tab open' },
  voice: {
    inputs: [
      { id: 'mic-headset', label: 'Headset microphone (USB)' },
      { id: 'mic-array', label: 'Microphone array (built in)' }
    ],
    outputs: [
      { id: 'out-headset', label: 'Headset (USB)' },
      { id: 'out-speakers', label: 'Speakers (built in)' }
    ],
    inputId: 'mic-headset',
    outputId: null,
    pushToTalkKey: 'V'
  },
  startWithWindows: true,
  version: '0.1.0'
}

export const DEMO_FRIENDS: FriendsVM = {
  friends: [
    ...(DEMO_HOME.friends ?? []),
    { ...ananya, status: 'Last seen 2 h ago', action: null },
    { id: 'demo-dev-7', name: 'Dev Malhotra', username: 'devm', online: false, status: 'Last seen yesterday', action: null }
  ],
  requests: [
    { id: 'demo-tara', name: 'Tara Nair', username: 'tara.n', sentLabel: '2 h ago' },
    { id: 'demo-ishaan-4', name: 'Ishaan Rao', username: 'ishaan', sentLabel: 'Yesterday' }
  ],
  sent: [{ id: 'demo-sara-8', name: 'Sara Khan', username: 'sara', sentLabel: 'Sent 3 d ago' }]
}
