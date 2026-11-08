import { useState, type CSSProperties, type JSX } from 'react'
import { BRAND } from '@shared/brand'
import { useAuth } from '@/lib/auth'
import { DEMO_HOME, DEMO_ME } from '@/demo/sample'
import { Backdrop, type BackdropPreset } from '@/ui/Backdrop'
import { LogoMark } from '@/ui/Icon'
import { RailNav, SideNav, type Tab } from '@/ui/Nav'
import type { HomeVM } from '@/ui/models'
import { Home, type TrendingKind } from '@/screens/Home'
import { SignIn } from '@/screens/SignIn'

const DEMO = new URLSearchParams(window.location.search).get('demo') === '1'

const BACKDROP: Record<Tab, BackdropPreset> = {
  home: 'home',
  chats: 'chat',
  friends: 'chat',
  streams: 'live',
  settings: 'calm'
}

export function App(): JSX.Element {
  const auth = useAuth()
  if (DEMO) return <Shell me={DEMO_ME} home={DEMO_HOME} />
  if (auth.state === 'loading') return <Splash />
  if (auth.state === 'signedOut') return <SignIn />
  const user = auth.user
  // Real data arrives in the next step (src/renderer/src/data): until then the screens show their
  // loading / empty states for the signed-in account.
  return (
    <Shell
      me={{ uid: user.uid, name: user.displayName ?? user.email ?? 'You', username: '', photoUrl: user.photoURL }}
      home={{ continueWatching: null, liveRoom: null, trending: null, friends: null, onlineCount: 0 }}
    />
  )
}

function Shell({ me, home }: { me: { uid: string; name: string; username: string; photoUrl: string | null }; home: HomeVM }): JSX.Element {
  const [tab, setTab] = useState<Tab>('home')
  const [trendingKind, setTrendingKind] = useState<TrendingKind>('movies')
  const nav = {
    current: tab,
    onNavigate: setTab,
    badges: DEMO ? { chats: 3, friends: 1 } : {},
    me,
    extension: DEMO ? { connected: true, detail: 'Edge · Netflix tab open' } : { connected: false, detail: 'Add it from Settings' }
  }
  const noop = (): void => undefined
  return (
    <div className="app">
      <Backdrop preset={BACKDROP[tab]} />
      <div className="drag-strip" />
      {tab === 'home' ? <SideNav {...nav} /> : <RailNav {...nav} />}
      {tab === 'home' ? (
        <Home
          data={home}
          trendingKind={trendingKind}
          onTrendingKind={setTrendingKind}
          onNewRoom={noop}
          onJoinLiveRoom={() => setTab('chats')}
          onResume={noop}
          onInvite={noop}
          onOpenTitle={noop}
          onFriendAction={noop}
        />
      ) : (
        <ComingNext tab={tab} />
      )}
    </div>
  )
}

function ComingNext({ tab }: { tab: Tab }): JSX.Element {
  const names: Record<Tab, string> = { home: 'Home', chats: 'Chats', friends: 'Friends', streams: 'Streams', settings: 'Settings' }
  return (
    <div className="screen">
      <section
        className="glass glass--raised rise"
        style={{ '--i': 1, flex: 1, padding: 34, display: 'flex', flexDirection: 'column', gap: 10, justifyContent: 'center', alignItems: 'center' } as CSSProperties}
      >
        <h2 className="h2">{names[tab]}</h2>
        <p className="muted" style={{ margin: 0 }}>
          Being built next.
        </p>
      </section>
    </div>
  )
}

function Splash(): JSX.Element {
  return (
    <div className="loading-screen">
      <Backdrop preset="home" />
      <div className="drag-strip" />
      <div className="logo-tile rise" style={{ width: 72, height: 72, borderRadius: 22, position: 'relative', zIndex: 1 }} aria-label={BRAND.name}>
        <LogoMark size={44} />
      </div>
    </div>
  )
}
