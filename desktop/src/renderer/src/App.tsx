import { useEffect, useState, type JSX } from 'react'
import type { User } from 'firebase/auth'
import { BRAND } from '@shared/brand'
import { signOut, useAuth } from '@/lib/auth'
import { MOVIE_NIGHT, RIYA_DM, ROAD_TRIP } from '@/demo/sample'
import { useDemo } from '@/demo/useDemo'
import type { ShellData } from '@/shell'
import { Backdrop, type BackdropPreset } from '@/ui/Backdrop'
import { LogoMark } from '@/ui/Icon'
import { RailNav, SideNav, type Tab } from '@/ui/Nav'
import { Home, type TrendingKind } from '@/screens/Home'
import { SignIn } from '@/screens/SignIn'
import { Chats } from '@/screens/Chats'
import { Room } from '@/screens/Room'
import { Streams } from '@/screens/Streams'
import { AddToBrowser } from '@/screens/AddToBrowser'
import { Settings } from '@/screens/Settings'
import { Friends } from '@/screens/Friends'

const params = new URLSearchParams(window.location.search)
const DEMO = params.get('demo') === '1'

/**
 * Where the app opens (`?screen=`, set by BJ_SCREEN for snapshots): home, chats (1:1 with Riya in
 * demo mode), group (Movie Night), solo (a group with just you), room, streams, browser (Add to browser), settings, friends.
 */
type View =
  | { tab: 'home' }
  | { tab: 'chats'; room: boolean }
  | { tab: 'friends' }
  | { tab: 'streams' }
  | { tab: 'settings'; browser: boolean }

function initialView(): { view: View; chatId: string | null; joinRoom: boolean } {
  const screen = params.get('screen')
  switch (screen) {
    case 'chats':
      return { view: { tab: 'chats', room: false }, chatId: DEMO ? RIYA_DM : null, joinRoom: false }
    case 'group':
      return { view: { tab: 'chats', room: false }, chatId: DEMO ? MOVIE_NIGHT : null, joinRoom: false }
    case 'solo':
      return { view: { tab: 'chats', room: false }, chatId: DEMO ? ROAD_TRIP : null, joinRoom: false }
    case 'room':
      return { view: { tab: 'chats', room: true }, chatId: DEMO ? MOVIE_NIGHT : null, joinRoom: true }
    case 'streams':
    case 'friends':
      return { view: { tab: screen }, chatId: null, joinRoom: false }
    case 'settings':
    case 'browser':
      return { view: { tab: 'settings', browser: screen === 'browser' }, chatId: null, joinRoom: false }
    default:
      return { view: { tab: 'home' }, chatId: null, joinRoom: false }
  }
}

const START = initialView()

/** First-time "Start a room with …" hint: shown until dismissed once (per PC). */
const TIP_KEY = 'bj.tip.startRoom.dismissed'
function tipDismissed(): boolean {
  if (DEMO) return false
  try {
    return localStorage.getItem(TIP_KEY) === '1'
  } catch {
    return false
  }
}

export function App(): JSX.Element {
  const auth = useAuth()
  if (DEMO) return <DemoShell />
  if (auth.state === 'loading') return <Splash />
  if (auth.state === 'signedOut') return <SignIn />
  return <SignedInShell user={auth.user} />
}

function DemoShell(): JSX.Element {
  return <Shell data={useDemo()} />
}

// Real data arrives in the next step (src/renderer/src/data). Until then a signed-in account sees
// each screen's loading / empty state.
function SignedInShell({ user }: { user: User }): JSX.Element {
  const [version, setVersion] = useState('')
  useEffect(() => {
    void window.bingejam.version().then(setVersion)
  }, [])
  const me = { uid: user.uid, name: user.displayName ?? user.email ?? 'You', username: '', photoUrl: user.photoURL }
  const noop = (): void => undefined
  const data: ShellData = {
    me,
    home: { continueWatching: null, liveRoom: null, trending: null, friends: null, onlineCount: 0 },
    chats: null,
    header: () => null,
    messages: () => null,
    members: () => null,
    friendPeople: null,
    room: null,
    roomChat: null,
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
    browser: { found: [], connected: null, popup: null },
    settings: {
      account: { uid: user.uid, name: me.name, username: '', email: user.email, photoUrl: user.photoURL },
      extension: { connected: false, detail: 'Add it from Settings' },
      voice: { inputs: [], outputs: [], inputId: null, outputId: null, pushToTalkKey: null },
      startWithWindows: null,
      version
    },
    friends: { friends: null, requests: null, sent: null },
    badges: {},
    act: {
      send: noop,
      typing: noop,
      ping: noop,
      rename: noop,
      newGroup: () => null,
      addPeople: noop,
      deleteChat: noop,
      startRoom: noop,
      joinRoom: noop,
      leaveRoom: noop,
      room: {
        send: noop,
        togglePlay: noop,
        seekBy: noop,
        seekTo: noop,
        subtitles: noop,
        waitForBuffering: noop,
        syncAllToMe: noop,
        invite: noop,
        showPlayer: noop,
        toggleMute: noop,
        joinVoice: noop
      },
      stream: { source: noop, quality: noop, start: noop, stop: noop, send: noop },
      addToBrowser: noop,
      settings: { signOut: () => void signOut(), inputDevice: noop, outputDevice: noop, pushToTalkKey: noop, startWithWindows: noop },
      friends: {
        add: async () => ({ ok: false, message: 'Friends arrive with the next update.' }),
        accept: noop,
        decline: noop,
        cancel: noop
      }
    }
  }
  return <Shell data={data} />
}

function Shell({ data }: { data: ShellData }): JSX.Element {
  const [view, setView] = useState<View>(START.view)
  const [chatId, setChatId] = useState<string | null>(START.chatId)
  const [trendingKind, setTrendingKind] = useState<TrendingKind>('movies')
  const [tipOff, setTipOff] = useState(tipDismissed)
  const act = data.act

  // `?screen=room`: join the demo room once on start.
  useEffect(() => {
    if (START.joinRoom && START.chatId) act.joinRoom(START.chatId)
  }, [])

  const tab: Tab = view.tab
  const header = chatId ? data.header(chatId) : null

  function navigate(next: Tab): void {
    // The rail's current tab takes you back up one level (room → its chat, Add to browser → Settings).
    if (next === 'chats') setView({ tab: 'chats', room: false })
    else if (next === 'settings') setView({ tab: 'settings', browser: false })
    else setView({ tab: next })
  }
  function openChat(id: string): void {
    setChatId(id)
    setView({ tab: 'chats', room: false })
  }
  function openRoom(id: string, start: boolean): void {
    if (start) act.startRoom(id)
    else act.joinRoom(id)
    setChatId(id)
    setView({ tab: 'chats', room: true })
  }

  const inRoom = view.tab === 'chats' && view.room
  const backdrop: BackdropPreset =
    view.tab === 'home'
      ? 'home'
      : view.tab === 'chats'
        ? view.room
          ? 'room'
          : header?.kind === 'group'
            ? 'group'
            : 'chat'
        : view.tab === 'streams'
          ? 'live'
          : view.tab === 'settings' && view.browser
            ? 'browser'
            : 'calm'

  const nav = {
    current: tab,
    onNavigate: navigate,
    badges: data.badges,
    me: data.me,
    extension: data.settings.extension
  }

  let screen: JSX.Element
  switch (view.tab) {
    case 'home':
      screen = (
        <Home
          data={data.home}
          trendingKind={trendingKind}
          onTrendingKind={setTrendingKind}
          onNewRoom={() => navigate('chats')}
          onJoinLiveRoom={() => data.home.liveRoom && openRoom(data.home.liveRoom.chatId, false)}
          onResume={() => navigate('chats')}
          onInvite={() => navigate('friends')}
          onOpenTitle={() => navigate('chats')}
          onFriendAction={() => navigate('chats')}
        />
      )
      break
    case 'chats':
      screen = inRoom ? (
        <Room
          room={data.room}
          chatName={header?.name ?? 'Room'}
          messages={data.roomChat}
          onSendChat={act.room.send}
          onTogglePlay={act.room.togglePlay}
          onSeekBy={act.room.seekBy}
          onSeekTo={act.room.seekTo}
          onSubtitles={act.room.subtitles}
          onWaitForBuffering={act.room.waitForBuffering}
          onSyncAllToMe={act.room.syncAllToMe}
          onInvite={act.room.invite}
          onShowPlayer={act.room.showPlayer}
          onPing={(uid) => data.room?.chatId && act.ping(data.room.chatId, uid)}
          onToggleMute={act.room.toggleMute}
          onJoinVoice={act.room.joinVoice}
          onGoLive={() => navigate('streams')}
          onLeave={() => {
            act.leaveRoom()
            setView({ tab: 'chats', room: false })
          }}
        />
      ) : (
        <Chats
          chats={data.chats}
          selectedChatId={chatId}
          header={header}
          messages={chatId ? data.messages(chatId) : null}
          members={chatId ? data.members(chatId) : null}
          friends={data.friendPeople}
          showStartRoomTip={!tipOff}
          onSelectChat={setChatId}
          onSend={(text) => chatId && act.send(chatId, text)}
          onTyping={(typing) => chatId && act.typing(chatId, typing)}
          onAttach={() => undefined}
          onStartRoom={() => chatId && openRoom(chatId, true)}
          onJoinRoom={() => chatId && openRoom(chatId, false)}
          onPing={(uid) => chatId && act.ping(chatId, uid)}
          onRename={(name) => chatId && act.rename(chatId, name)}
          onNewGroup={(name, ids) => {
            const id = act.newGroup(name, ids)
            if (id) setChatId(id)
          }}
          onAddPeople={(ids) => chatId && act.addPeople(chatId, ids)}
          onDeleteChat={() => {
            if (!chatId) return
            act.deleteChat(chatId)
            setChatId(null)
          }}
          onDismissStartRoomTip={() => {
            setTipOff(true)
            try {
              localStorage.setItem(TIP_KEY, '1')
            } catch {
              // Private storage off: the hint just shows again next time.
            }
          }}
        />
      )
      break
    case 'friends':
      screen = (
        <Friends
          data={data.friends}
          onFriendAction={() => navigate('chats')}
          onMessage={(f) => {
            const chat = data.chats?.find((c) => c.kind === 'dm' && c.people[0]?.id === f.id)
            if (chat) openChat(chat.chatId)
            else navigate('chats')
          }}
          onAddFriend={act.friends.add}
          onAccept={act.friends.accept}
          onDecline={act.friends.decline}
          onCancelRequest={act.friends.cancel}
        />
      )
      break
    case 'streams':
      screen = (
        <Streams
          stream={data.stream}
          meId={data.me.uid}
          media={data.media}
          messages={data.streamChat}
          onSource={act.stream.source}
          onQuality={act.stream.quality}
          onStartStreaming={act.stream.start}
          onStopStreaming={act.stream.stop}
          onSend={act.stream.send}
        />
      )
      break
    case 'settings':
      screen = view.browser ? (
        <AddToBrowser setup={data.browser} onAddTo={act.addToBrowser} onBack={() => setView({ tab: 'settings', browser: false })} />
      ) : (
        <Settings
          settings={data.settings}
          onSignOut={act.settings.signOut}
          onOpenBrowserSetup={() => setView({ tab: 'settings', browser: true })}
          onInputDevice={act.settings.inputDevice}
          onOutputDevice={act.settings.outputDevice}
          onPushToTalkKey={act.settings.pushToTalkKey}
          onStartWithWindows={act.settings.startWithWindows}
        />
      )
      break
  }

  return (
    <div className="app">
      <Backdrop preset={backdrop} />
      <div className="drag-strip" />
      {tab === 'home' ? <SideNav {...nav} /> : <RailNav {...nav} />}
      {screen}
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
