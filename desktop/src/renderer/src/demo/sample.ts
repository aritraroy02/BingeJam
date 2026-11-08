import type { HomeVM, PersonVM } from '@/ui/models'

// Demo mode (`BJ_DEMO=1`): the design's sample content with made-up names and titles, for store
// screenshots and design reviews. Nothing here touches Firebase or real people.

export const DEMO_ME = { uid: 'demo-alex', name: 'Alex Sharma', username: 'alex', photoUrl: null }

const riya: PersonVM = { id: 'demo-riya', name: 'Riya Sen' }
const kabir: PersonVM = { id: 'demo-kabir', name: 'Kabir Mehta' }

export const DEMO_HOME: HomeVM = {
  continueWatching: {
    title: 'The Last Monsoon',
    subtitle: 'With Riya and Kabir · stopped at 1:12:40 · Prime Video',
    progress: 0.58,
    leftLabel: '51 min left',
    people: [riya, kabir],
    gradient: 'linear-gradient(165deg, #3B0A12, #9F1239 55%, #FDBA74 140%)'
  },
  liveRoom: { chatId: 'demo-movie-night', chatName: 'Movie Night' },
  trending: [
    { id: 't1', title: 'Midnight Signal', service: 'Netflix', rating: 8.1, gradient: 'linear-gradient(170deg, #0F172A, #1E40AF 60%, #F43F5E 150%)' },
    { id: 't2', title: 'Paper Kites', service: 'JioHotstar', rating: 7.6, gradient: 'linear-gradient(170deg, #052E16, #15803D 55%, #FACC15 150%)' },
    { id: 't3', title: 'Salt & Ember', service: 'Prime Video', rating: 7.9, gradient: 'linear-gradient(170deg, #2E1065, #7E22CE 55%, #F472B6 150%)' },
    { id: 't4', title: 'Northbound', service: 'Netflix', rating: 6.8, gradient: 'linear-gradient(170deg, #1C1917, #92400E 55%, #FB923C 150%)' }
  ],
  friends: [
    { ...riya, status: 'Midnight Signal · S2:E4 · Netflix', action: 'join' },
    { ...kabir, status: 'Live · Skyline Racers', streaming: true, action: 'watch' },
    { id: 'demo-meera', name: 'Meera Kapoor', status: 'Neon Streets · Spotify', action: 'jam' },
    { id: 'demo-zoe', name: 'Zoe Williams', status: 'Online', action: null }
  ],
  onlineCount: 4
}
