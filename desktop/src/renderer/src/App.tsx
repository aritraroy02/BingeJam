import { useEffect, useRef, useState, type JSX } from 'react'
import type { User } from 'firebase/auth'
import { BRAND } from '@shared/brand'
import { signOut, useAuth } from '@/lib/auth'
import { startDevicePresence, useMyBirthDate, useMyProfile, writePrivateProfile, type Profile } from '@/data'
import { useLiveShell } from '@/live/useLiveShell'
import { MOVIE_NIGHT, RIYA_DM, ROAD_TRIP } from '@/demo/sample'
import { useDemo } from '@/demo/useDemo'
import type { ShellData } from '@/shell'
import { Backdrop, type BackdropPreset } from '@/ui/Backdrop'
import { LogoMark } from '@/ui/Icon'
import { Button } from '@/ui/primitives'
import { RailNav, SideNav, type Tab } from '@/ui/Nav'
import { Home, type TrendingKind } from '@/screens/Home'
import { SignIn } from '@/screens/SignIn'
import { Chats } from '@/screens/Chats'
import { Room } from '@/screens/Room'
import { Streams } from '@/screens/Streams'
import { AddToBrowser } from '@/screens/AddToBrowser'
import { Settings } from '@/screens/Settings'
import { Friends } from '@/screens/Friends'
import { ProfileSetup } from '@/screens/ProfileSetup'

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
  if (DEMO && params.get('screen') === 'setup') return <ProfileSetup needProfile needBirthDate suggestedName="Aritra" onSignOut={() => undefined} />
  if (DEMO) return <DemoShell />
  if (auth.state === 'loading') return <Splash />
  if (auth.state === 'signedOut') return <SignIn />
  return <ProfileGate user={auth.user} />
}

function DemoShell(): JSX.Element {
  const [chatId, setChatId] = useState<string | null>(START.chatId)
  const [trendingKind, setTrendingKind] = useState<TrendingKind>('movies')
  return <Shell data={useDemo()} chatId={chatId} onChatId={setChatId} trendingKind={trendingKind} onTrendingKind={setTrendingKind} />
}

/**
 * Signed in: this PC shows online to friends (presence/{uid}/devices, PLAN.md), and the account
 * needs a username and a date of birth (18+, like the phone since 0.7.0) before the main screens.
 */
function ProfileGate({ user }: { user: User }): JSX.Element {
  const profile = useMyProfile()
  const birthDate = useMyBirthDate()
  const stopPresence = useRef<() => Promise<void>>(async () => undefined)

  useEffect(() => {
    const stop = startDevicePresence(user.uid)
    stopPresence.current = stop
    // Android writes users_private on every sign-in (email, provider); best effort.
    void writePrivateProfile()
    return () => void stop()
  }, [user.uid])

  const signOutNow = (): void =>
    void (async () => {
      await stopPresence.current()
      await signOut()
    })()

  if (profile.state === 'loading' || (profile.state === 'ready' && birthDate.state === 'loading')) return <Splash />
  if (profile.state === 'error') return <ProfileError onSignOut={signOutNow} />
  if (profile.state === 'missing' || birthDate.state === 'missing')
    return (
      <ProfileSetup
        needProfile={profile.state === 'missing'}
        needBirthDate={birthDate.state !== 'set'}
        suggestedName={user.displayName ?? ''}
        onSignOut={signOutNow}
      />
    )
  return <LiveShell profile={profile.profile} stopPresence={() => stopPresence.current()} />
}

function LiveShell({ profile, stopPresence }: { profile: Profile; stopPresence: () => Promise<void> }): JSX.Element {
  const [version, setVersion] = useState('')
  const [chatId, setChatId] = useState<string | null>(START.chatId)
  const [trendingKind, setTrendingKind] = useState<TrendingKind>('movies')
  useEffect(() => {
    void window.bingejam.version().then(setVersion)
  }, [])
  const data = useLiveShell({ profile, chatId, trendingKind, version, stopPresence })
  return <Shell data={data} chatId={chatId} onChatId={setChatId} trendingKind={trendingKind} onTrendingKind={setTrendingKind} />
}

function ProfileError({ onSignOut }: { onSignOut: () => void }): JSX.Element {
  return (
    <div className="loading-screen" style={{ flexDirection: 'column', gap: 14 }}>
      <Backdrop preset="calm" />
      <div className="drag-strip" />
      <p style={{ position: 'relative', zIndex: 1, margin: 0, fontSize: 15 }}>Couldn&apos;t load your profile. Check your connection.</p>
      <div style={{ position: 'relative', zIndex: 1, display: 'flex', gap: 10 }}>
        <Button variant="primary" onClick={() => window.location.reload()}>
          Try again
        </Button>
        <Button variant="ghost" onClick={onSignOut}>
          Sign out
        </Button>
      </div>
    </div>
  )
}

interface ShellProps {
  data: ShellData
  chatId: string | null
  onChatId: (id: string | null) => void
  trendingKind: TrendingKind
  onTrendingKind: (kind: TrendingKind) => void
}

function Shell({ data, chatId, onChatId: setChatId, trendingKind, onTrendingKind: setTrendingKind }: ShellProps): JSX.Element {
  const [view, setView] = useState<View>(START.view)
  const [tipOff, setTipOff] = useState(tipDismissed)
  const act = data.act

  // `?screen=room`: join the demo room once on start.
  useEffect(() => {
    if (START.joinRoom && START.chatId) act.joinRoom(START.chatId)
  }, [])

  const tab: Tab = view.tab
  const header = chatId ? data.header(chatId) : null
  const inRoom = view.tab === 'chats' && view.room

  // Read receipts: which chat is on screen.
  useEffect(() => {
    act.viewing?.(view.tab === 'chats' && !view.room ? chatId : null)
  }, [view, chatId])

  // A room that failed to open, or ended, closes back to its chat (real accounts only).
  useEffect(() => {
    if (inRoom && data.roomStarting === false && !data.room) setView({ tab: 'chats', room: false })
  }, [inRoom, data.roomStarting, data.room])

  // Toasts go away by themselves.
  useEffect(() => {
    if (!data.notice) return
    const t = setTimeout(() => act.dismissNotice?.(), 4_000)
    return () => clearTimeout(t)
  }, [data.notice])

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
          onOpenTitle={(item) => (act.openTitle ? act.openTitle(item) : navigate('chats'))}
          onFriendAction={(f) => (act.friendAction ? act.friendAction(f) : navigate('chats'))}
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
          onShowPlayer={() => {
            // Not linked to a browser yet: the button says "Add to browser".
            if (!data.settings.extension.connected && data.roomStarting !== undefined) setView({ tab: 'settings', browser: true })
            else act.room.showPlayer()
          }}
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
            void Promise.resolve(act.newGroup(name, ids)).then((id) => id && setChatId(id))
          }}
          onAddPeople={(ids) => chatId && act.addPeople(chatId, ids)}
          onDeleteChat={() => {
            if (!chatId) return
            act.deleteChat(chatId)
            setChatId(null)
          }}
          onBlock={() => {
            if (!chatId) return
            act.safety.block(chatId)
            setChatId(null)
          }}
          onBlockGroup={() => {
            if (!chatId) return
            act.safety.blockGroup(chatId)
            setChatId(null)
          }}
          onReport={(reason, details, block) => {
            if (!chatId) return
            act.safety.report(chatId, reason, details, block)
            if (block) setChatId(null)
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
          onFriendAction={(f) => (act.friendAction ? act.friendAction(f) : navigate('chats'))}
          onMessage={(f) => {
            const chat = data.chats?.find((c) => c.kind === 'dm' && c.people[0]?.id === f.id)
            if (chat) openChat(chat.chatId)
            else if (act.dmWith) openChat(act.dmWith(f.id))
            else navigate('chats')
          }}
          onAddFriend={act.friends.add}
          onAccept={act.friends.accept}
          onDecline={act.friends.decline}
          onCancelRequest={act.friends.cancel}
          onUnblockUser={act.safety.unblockUser}
          onUnblockGroup={act.safety.unblockGroup}
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
      {data.notice ? (
        <div role="status" className="glass glass--strong toast" onClick={() => act.dismissNotice?.()}>
          {data.notice}
        </div>
      ) : null}
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
