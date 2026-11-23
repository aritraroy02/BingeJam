import type { GroupChannelsVM, MessageVM, VoiceConnectionVM } from '@/ui/models'
import { DEMO_ME, DEMO_PEOPLE, MOVIE_NIGHT } from './sample'

// Demo channels (BJ_DEMO=1, ?screen=channel / voice): Movie Night set up like a Discord server, with
// people talking in its Gaming voice channel (voice runs in Singapore, like the real relay). Made-up names, nothing real.

const { riya, kabir, meera } = DEMO_PEOPLE

const NOW = Date.now()
const t = (minutesAgo: number): number => NOW - minutesAgo * 60_000

export const DEMO_CHANNELS: Record<string, GroupChannelsVM> = {
  [MOVIE_NIGHT]: {
    chatId: MOVIE_NIGHT,
    canManage: true,
    text: [
      { id: 'general', name: 'general', kind: 'text', builtIn: true },
      { id: 'announcements', name: 'announcements', kind: 'text', builtIn: false },
      { id: 'movie-picks', name: 'movie-picks', kind: 'text', builtIn: false, unread: true },
      { id: 'off-topic', name: 'off-topic', kind: 'text', builtIn: false },
      { id: 'bot-music', name: 'bot-music', kind: 'text', builtIn: false },
      { id: 'bugs', name: 'bugs', kind: 'text', builtIn: false, unread: true }
    ],
    voice: [
      { id: 'lounge', name: 'General', kind: 'voice', builtIn: true, people: [], elapsed: null, status: null },
      {
        id: 'gaming',
        name: 'Gaming',
        kind: 'voice',
        builtIn: false,
        connected: true,
        elapsed: '15:05',
        status: 'Valorant ranked, then Dune',
        people: [
          { ...riya, isMe: false, muted: false, deafened: false, speaking: true, live: true },
          { id: DEMO_ME.uid, name: 'You', isMe: true, muted: false, deafened: false, speaking: false },
          { ...kabir, isMe: false, muted: true, deafened: false, speaking: false }
        ]
      },
      { id: 'watch-along', name: 'Watch along', kind: 'voice', builtIn: false, people: [{ ...meera, isMe: false, muted: false, deafened: true, speaking: false }], elapsed: '42:18', status: null }
    ]
  }
}

const text = (id: string, from: typeof riya, body: string, minutesAgo: number): MessageVM => ({
  kind: 'text',
  id,
  fromMe: false,
  senderId: from.id,
  senderName: from.name.split(' ')[0],
  text: body,
  sentAt: t(minutesAgo)
})

export const DEMO_CHANNEL_MESSAGES: Record<string, MessageVM[]> = {
  [`${MOVIE_NIGHT}#off-topic`]: [
    { kind: 'day', id: 'd-today', label: 'Today' },
    text('o1', kabir, 'Anyone else hear the Gaming channel echo earlier?', 34),
    text('o2', riya, 'Fixed, it was my laptop speakers. Headphones now', 31),
    { kind: 'text', id: 'o3', fromMe: true, senderId: DEMO_ME.uid, senderName: 'You', text: 'Sounds clean on my side. Singapore server, 64 ms', sentAt: t(29) }
  ],
  [`${MOVIE_NIGHT}#movie-picks`]: [
    { kind: 'day', id: 'd-today', label: 'Today' },
    text('p1', meera, 'Saturday: Dune Part Two or Midnight Signal S3?', 12)
  ]
}

export const DEMO_VOICE: VoiceConnectionVM = {
  status: 'connected',
  error: null,
  channelName: 'Gaming',
  placeName: 'Movie Night',
  target: { kind: 'channel', chatId: MOVIE_NIGHT, channelId: 'gaming' },
  muted: false,
  deafened: false,
  speaking: false,
  pingMs: 64,
  quality: 'good',
  server: 'Singapore',
  serverCode: 'SIN',
  edge: 'Hyderabad (HYD)'
}
