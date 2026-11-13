import { useEffect, useId, useState, type CSSProperties, type FormEvent, type JSX } from 'react'
import { Icon } from '@/ui/Icon'
import { Avatar, Badge, Button, Switch, SyncDots } from '@/ui/primitives'
import { Composer, MessageList } from '@/ui/chat'
import { ConfirmDialog, Dialog } from '@/ui/Dialog'
import { MenuButton, type MenuItem } from '@/ui/Menu'
import { ReportDialog, type ReportReasonValue } from '@/ui/ReportDialog'
import { firstName, joinNames } from '@/ui/format'
import type { ChatHeaderVM, ChatSummaryVM, GroupMemberVM, MessageVM, PersonVM } from '@/ui/models'

// Chats: 1:1 chats and groups in one list, each with its own room (PLAN.md "Chats and rooms are one
// thing"). design/2-chat-1to1 (list + open chat) and design/3-group-chat (open chat + Members panel).

export interface ChatsProps {
  /** Newest first is done here (by sortKey). null = loading. */
  chats: ChatSummaryVM[] | null
  selectedChatId: string | null
  /** The open chat; null while it loads (or when nothing is selected). */
  header: ChatHeaderVM | null
  messages: MessageVM[] | null
  /** Group members for the right panel (groups only). */
  members: GroupMemberVM[] | null
  /** Friends to pick from for "New group" and "Add people". */
  friends: PersonVM[] | null
  /** First-time hint under "Start room" in a 1:1 chat. */
  showStartRoomTip: boolean
  onSelectChat: (chatId: string) => void
  onSend: (text: string) => void
  /** true on the first keystroke, false after 3 s idle / on send / on blur. */
  onTyping: (typing: boolean) => void
  /** 1:1 chats show the attach (+) button when this is set. */
  onAttach?: () => void
  onStartRoom: () => void
  /** Join the chat's live room (or open it again if you're already in). */
  onJoinRoom: () => void
  onPing: (uid: string) => void
  onRename: (name: string) => void
  onNewGroup: (name: string, memberIds: string[]) => void
  onAddPeople: (memberIds: string[]) => void
  onDeleteChat: () => void
  onDismissStartRoomTip: () => void
  /** 1:1 chats: block the friend (2026-11-10, same as the phone). */
  onBlock?: () => void
  /** Groups: exit and stop anyone adding you back. */
  onBlockGroup?: () => void
  /** Report the person or group; [block] also blocks (or, for a group, exits and blocks). */
  onReport?: (reason: ReportReasonValue, details: string | null, block: boolean) => void
  /** Someone's profile (the DM header, a group member). */
  onOpenProfile?: (uid: string) => void
  /** Group admins (2026-11-10): make or dismiss an admin, remove someone, the two settings. */
  onSetAdmin?: (uid: string, admin: boolean) => void
  onRemoveMember?: (uid: string) => void
  onGroupSettings?: (onlyAdminsSend: boolean, onlyAdminsEdit: boolean) => void
  /** Friends with an unanswered invite to the open group ("Invited" in Add people). */
  invited?: readonly string[]
  /** Message actions (2026-11-12): forward a text to friends, delete in a 1:1 chat. */
  onForward?: (text: string, friendIds: string[]) => void
  onDeleteForMe?: (messageId: string) => void
  onDeleteForEveryone?: (messageId: string) => void
}

type DialogKind = 'newGroup' | 'addPeople' | 'delete' | 'block' | 'report' | null

type TextMessage = Extract<MessageVM, { kind: 'text' }>

export function Chats(p: ChatsProps): JSX.Element {
  const [dialog, setDialog] = useState<DialogKind>(null)
  const [membersOpen, setMembersOpen] = useState(true)
  const [renaming, setRenaming] = useState(false)
  const [forwarding, setForwarding] = useState<string | null>(null)
  const [deleting, setDeleting] = useState<TextMessage | null>(null)
  const isGroup = p.header?.kind === 'group'
  const showMembers = isGroup && membersOpen
  // A group can start with nobody but its creator: "Just you · add people".
  const solo = isGroup && p.members !== null && p.members.every((m) => m.isMe)

  // A different chat cancels a rename in progress.
  useEffect(() => setRenaming(false), [p.selectedChatId])

  return (
    <div className={showMembers ? 'screen chats chats--members' : 'screen chats'}>
      <ChatList {...p} onNewGroup={() => setDialog('newGroup')} />

      {p.header ? (
        <ChatView
          {...p}
          header={p.header}
          membersOpen={membersOpen}
          onToggleMembers={() => setMembersOpen((v) => !v)}
          renaming={renaming}
          setRenaming={setRenaming}
          onAskDelete={() => setDialog('delete')}
          onForwardMessage={p.onForward ? (m) => setForwarding(m.text) : undefined}
          onDeleteMessage={p.onDeleteForMe ? (m) => setDeleting(m) : undefined}
          onAskBlock={p.onBlock || p.onBlockGroup ? () => setDialog('block') : undefined}
          onAskReport={p.onReport ? () => setDialog('report') : undefined}
          solo={solo}
          onOpenAddPeople={() => setDialog('addPeople')}
        />
      ) : (
        <NoChat loading={p.selectedChatId !== null} hasChats={(p.chats?.length ?? 0) > 0} onNewGroup={() => setDialog('newGroup')} />
      )}

      {showMembers && p.header ? (
        <MembersPanel
          header={p.header}
          members={p.members}
          onPing={p.onPing}
          onOpenProfile={p.onOpenProfile}
          onSetAdmin={p.onSetAdmin}
          onRemoveMember={p.onRemoveMember}
          onGroupSettings={p.onGroupSettings}
          onAddPeople={() => setDialog('addPeople')}
          onRename={() => setRenaming(true)}
        />
      ) : null}

      <PeopleDialog
        open={dialog === 'newGroup'}
        mode="newGroup"
        friends={p.friends}
        exclude={[]}
        onClose={() => setDialog(null)}
        onDone={(name, ids) => p.onNewGroup(name, ids)}
      />
      <PeopleDialog
        open={dialog === 'addPeople'}
        mode="addPeople"
        friends={p.friends}
        exclude={(p.members ?? []).map((m) => m.id)}
        invited={p.invited}
        onClose={() => setDialog(null)}
        onDone={(_, ids) => p.onAddPeople(ids)}
      />
      <ConfirmDialog
        open={dialog === 'delete'}
        title={isGroup ? `Delete ${p.header?.name ?? 'this group'}?` : `Delete your chat with ${firstName(p.header?.name ?? '')}?`}
        description={
          isGroup
            ? 'The group and its messages disappear from your chats.'
            : `The messages disappear from your chats. ${firstName(p.header?.name ?? 'They')} keeps their copy.`
        }
        confirmLabel="Delete chat"
        danger
        onConfirm={p.onDeleteChat}
        onClose={() => setDialog(null)}
      />
      <ConfirmDialog
        open={dialog === 'block'}
        title={isGroup ? `Block ${p.header?.name ?? 'this group'}?` : `Block ${p.header?.name ?? 'them'}?`}
        description={
          isGroup
            ? "You'll exit the group, and nobody can add you back or invite you to it. You can unblock it from Friends."
            : "You won't be friends any more. They can't message you, send you requests or see your activity. You can unblock them from Friends."
        }
        confirmLabel={isGroup ? 'Exit and block' : 'Block'}
        danger
        onConfirm={() => (isGroup ? p.onBlockGroup?.() : p.onBlock?.())}
        onClose={() => setDialog(null)}
      />
      <ForwardDialog
        open={forwarding !== null}
        friends={p.friends}
        onClose={() => setForwarding(null)}
        onSend={(ids) => forwarding !== null && p.onForward?.(forwarding, ids)}
      />
      <Dialog open={deleting !== null} title="Delete message?" onClose={() => setDeleting(null)}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {deleting?.fromMe && !deleting.deleted && p.onDeleteForEveryone ? (
            <Button
              variant="danger"
              onClick={() => {
                if (deleting) p.onDeleteForEveryone?.(deleting.id)
                setDeleting(null)
              }}
            >
              Delete for everyone
            </Button>
          ) : null}
          <Button
            onClick={() => {
              if (deleting) p.onDeleteForMe?.(deleting.id)
              setDeleting(null)
            }}
          >
            Delete for me
          </Button>
          <Button variant="ghost" onClick={() => setDeleting(null)}>
            Cancel
          </Button>
        </div>
      </Dialog>
      <ReportDialog
        open={dialog === 'report'}
        title={`Report ${p.header?.name ?? ''}`.trim()}
        evidenceNote={
          isGroup
            ? 'The last 5 messages from others in this group are sent with your report so it can be reviewed. Nobody in the group is told.'
            : `${firstName(p.header?.name ?? 'Their')}'s last 5 messages in this chat are sent with your report so it can be reviewed. ${firstName(p.header?.name ?? 'They')} isn't told.`
        }
        blockLabel={isGroup ? 'Also exit and block this group' : `Also block ${firstName(p.header?.name ?? 'them')}`}
        onClose={() => setDialog(null)}
        onSubmit={(reason, details, block) => p.onReport?.(reason, details, block)}
      />
    </div>
  )
}

// ---------- List ----------

function ChatList(p: ChatsProps & { onNewGroup: () => void }): JSX.Element {
  const [query, setQuery] = useState('')
  const searchId = useId()
  const q = query.trim().toLowerCase()
  const chats = p.chats
    ? [...p.chats]
        .sort((a, b) => b.sortKey - a.sortKey)
        .filter((c) => !q || c.name.toLowerCase().includes(q) || c.preview.toLowerCase().includes(q))
    : null

  return (
    <section aria-labelledby="h-chats" className="glass chat-list rise" style={{ '--i': 1 } as CSSProperties}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <h2 id="h-chats" className="h2" style={{ flex: 1 }}>
          Chats
        </h2>
        <Button size="sm" onClick={p.onNewGroup}>
          New group
        </Button>
      </div>
      <label htmlFor={searchId} className="sr-only">
        Search chats
      </label>
      <input
        id={searchId}
        type="search"
        className="field chat-list__search"
        placeholder="Search chats"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      <div className="scroll" style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        {chats === null ? (
          <p className="muted small" style={{ margin: '4px 12px' }}>
            Loading chats…
          </p>
        ) : chats.length === 0 ? (
          <p className="muted small" style={{ margin: '4px 12px', lineHeight: 1.5 }}>
            {q ? `No chats match “${query.trim()}”.` : 'No chats yet. Message a friend from Friends, or start a group.'}
          </p>
        ) : (
          <ul className="chat-list__items" aria-label="Chats">
            {chats.map((c) => (
              <li key={c.chatId}>
                <button
                  type="button"
                  className="chat-item pressable"
                  aria-current={c.chatId === p.selectedChatId ? 'true' : undefined}
                  onClick={() => p.onSelectChat(c.chatId)}
                >
                  <ChatAvatar kind={c.kind} name={c.name} id={c.chatId} people={c.people} />
                  <span className="chat-item__text">
                    <span className="chat-item__name truncate">{c.name}</span>
                    {c.liveRoom ? (
                      <span className="chat-item__live">
                        <SyncDots />
                        Live · {c.liveRoom.inCount} in
                      </span>
                    ) : (
                      <span className="chat-item__preview">{c.preview}</span>
                    )}
                  </span>
                  {c.unread > 0 ? (
                    <>
                      <span className="sr-only">{c.unread} unread</span>
                      <Badge count={c.unread} />
                    </>
                  ) : (
                    <span className="chat-item__time">{c.timeLabel}</span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}

/** 1:1: the friend. Group: two members stacked, or the group's initials when there's no one to show. */
function ChatAvatar({
  kind,
  name,
  id,
  people,
  large = false,
  online = false
}: {
  kind: 'dm' | 'group'
  name: string
  id: string
  people: PersonVM[]
  large?: boolean
  online?: boolean
}): JSX.Element {
  if (kind === 'group') {
    if (people.length >= 2) {
      const [a, b] = people
      return (
        <span className={large ? 'avatar-pair avatar-pair--lg' : 'avatar-pair'} aria-hidden="true">
          <Avatar name={a.name} id={a.id} photoUrl={a.photoUrl} size={large ? 32 : 30} />
          <Avatar name={b.name} id={b.id} photoUrl={b.photoUrl} size={large ? 32 : 30} />
        </span>
      )
    }
    return <Avatar name={name} id={id} size={large ? 46 : 44} group />
  }
  const friend = people[0]
  return <Avatar name={friend?.name ?? name} id={friend?.id ?? id} photoUrl={friend?.photoUrl} size={large ? 46 : 44} online={online} />
}

// ---------- The open chat ----------

function ChatView(
  p: ChatsProps & {
    header: ChatHeaderVM
    membersOpen: boolean
    onToggleMembers: () => void
    renaming: boolean
    setRenaming: (v: boolean) => void
    onAskDelete: () => void
    onAskBlock?: () => void
    onAskReport?: () => void
    onForwardMessage?: (m: TextMessage) => void
    onDeleteMessage?: (m: TextMessage) => void
    solo: boolean
    onOpenAddPeople: () => void
  }
): JSX.Element {
  const h = p.header
  const isGroup = h.kind === 'group'
  const friend = h.people[0]
  const tipId = useId()
  const titleId = useId()
  const showTip = p.showStartRoomTip && !isGroup && h.room === null

  // The hint closes with Escape too (WCAG 1.4.13).
  useEffect(() => {
    if (!showTip) return
    const onKey = (e: KeyboardEvent): void => {
      if (e.key === 'Escape') p.onDismissStartRoomTip()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [showTip, p.onDismissStartRoomTip])

  const menu: MenuItem[] = [
    ...(isGroup ? [{ label: p.membersOpen ? 'Hide members' : 'Show members', icon: 'users' as const, onSelect: p.onToggleMembers }] : []),
    { label: 'Delete chat', icon: 'trash', danger: true, onSelect: p.onAskDelete },
    ...(p.onAskBlock ? [{ label: isGroup ? 'Block group' : `Block ${firstName(h.name)}`, icon: 'block' as const, danger: true, onSelect: p.onAskBlock }] : []),
    ...(p.onAskReport ? [{ label: isGroup ? 'Report group' : `Report ${firstName(h.name)}`, icon: 'flag' as const, danger: true, onSelect: p.onAskReport }] : [])
  ]

  const roomButtonLabel = h.room ? (h.room.meInRoom ? 'Open room' : 'Join room') : null

  return (
    <main aria-labelledby={titleId} className="chat-main rise" style={{ '--i': 2 } as CSSProperties}>
      <header className="chat-header">
        {!isGroup && friend && p.onOpenProfile ? (
          <button type="button" className="person-open" aria-label={`${h.name}'s profile`} onClick={() => p.onOpenProfile?.(friend.id)}>
            <ChatAvatar kind={h.kind} name={h.name} id={h.chatId} people={h.people} large online={friend.online === true} />
          </button>
        ) : (
          <ChatAvatar kind={h.kind} name={h.name} id={h.chatId} people={h.people} large online={!isGroup && friend?.online === true} />
        )}
        <div className="chat-header__title">
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
            {p.renaming ? (
              <RenameField name={h.name} labelId={titleId} onDone={(name) => {
                p.setRenaming(false)
                if (name && name !== h.name) p.onRename(name)
              }} />
            ) : (
              <h2 id={titleId} className="chat-header__name truncate">
                {h.name}
              </h2>
            )}
            {isGroup && h.canRename && !p.renaming ? (
              <button type="button" className="icon-btn-sm pressable" aria-label="Rename group" title="Rename group" onClick={() => p.setRenaming(true)}>
                <Icon name="edit" size={14} />
              </button>
            ) : null}
          </div>
          {p.solo ? (
            <button type="button" className="chat-header__subtitle" onClick={p.onOpenAddPeople}>
              Just you · add people
            </button>
          ) : isGroup ? (
            <button
              type="button"
              className="chat-header__subtitle"
              aria-expanded={p.membersOpen}
              aria-controls="members-panel"
              title={p.membersOpen ? 'Hide members' : 'Show members'}
              onClick={p.onToggleMembers}
            >
              {h.subtitle}
            </button>
          ) : (
            <span className="chat-header__subtitle truncate">{h.subtitle}</span>
          )}
        </div>

        {h.room ? (
          <span className="room-live-chip">
            <SyncDots />
            Room live · {h.room.startedAtLabel}
          </span>
        ) : null}
        {h.room ? (
          <Button variant="primary" style={{ padding: '0 20px' }} onClick={p.onJoinRoom}>
            {roomButtonLabel}
          </Button>
        ) : p.solo ? (
          <Button variant="primary" onClick={p.onOpenAddPeople}>
            <Icon name="friends" size={18} />
            Add people
          </Button>
        ) : (
          <Button
            variant="primary"
            aria-describedby={showTip ? tipId : undefined}
            onClick={() => {
              if (showTip) p.onDismissStartRoomTip()
              p.onStartRoom()
            }}
          >
            <Icon name="startRoom" size={18} />
            Start room
          </Button>
        )}
        <MenuButton label="More options" items={menu} />

        {showTip ? (
          <div role="tooltip" id={tipId} className="glass-tip chat-tip" onClick={p.onDismissStartRoomTip}>
            <b>Start a room with {firstName(friend?.name ?? h.name)}.</b> Watch a movie together, stream a game or your screen, and talk,
            all from this chat.
          </div>
        ) : null}
      </header>

      <div className="chat-pane">
        {h.room ? <RoomBanner room={h.room} onJoin={p.onJoinRoom} /> : null}
        <MessageList
          messages={p.messages}
          actions={(m) => [
            ...(!m.deleted ? [{ label: 'Copy', icon: 'copy' as const, onSelect: () => void navigator.clipboard.writeText(m.text).catch(() => undefined) }] : []),
            ...(!m.deleted && p.onForwardMessage ? [{ label: 'Forward', icon: 'forward' as const, onSelect: () => p.onForwardMessage?.(m) }] : []),
            ...(!isGroup && p.onDeleteMessage ? [{ label: 'Delete', icon: 'trash' as const, danger: true, onSelect: () => p.onDeleteMessage?.(m) }] : [])
          ]}
          resetKey={h.chatId}
          showSenders={isGroup}
          label={`Messages with ${h.name}`}
          empty={
            <div className="chat-empty">
              <span className="chat-empty__icon">
                <Icon name={isGroup ? 'users' : 'chat'} size={24} />
              </span>
              {p.solo ? (
                <>
                  <span style={{ color: 'var(--text)', fontWeight: 500, fontSize: 16 }}>Just you in {h.name} so far</span>
                  <span>Add friends to chat here and start the group's room together.</span>
                  <Button size="sm" onClick={p.onOpenAddPeople} style={{ marginTop: 8 }}>
                    Add people
                  </Button>
                </>
              ) : (
                <>
                  <span style={{ color: 'var(--text)', fontWeight: 500, fontSize: 16 }}>Say hi to {isGroup ? h.name : firstName(h.name)}</span>
                  <span>Or start a room to watch, listen or play together. The chat stays open beside it.</span>
                </>
              )}
            </div>
          }
        />
        <Composer
          label={`Message ${isGroup ? h.name : firstName(h.name)}`}
          placeholder={isGroup && h.canSend === false ? 'Only admins can send messages' : `Message ${isGroup ? h.name : firstName(h.name)}`}
          disabled={isGroup && h.canSend === false}
          onSend={p.onSend}
          onTyping={p.onTyping}
          onAttach={isGroup ? undefined : p.onAttach}
        />
      </div>
    </main>
  )
}

function RenameField({ name, labelId, onDone }: { name: string; labelId: string; onDone: (name: string | null) => void }): JSX.Element {
  const [draft, setDraft] = useState(name)
  const inputId = useId()
  function submit(e?: FormEvent): void {
    e?.preventDefault()
    onDone(draft.trim() || null)
  }
  return (
    <form onSubmit={submit} style={{ display: 'flex', minWidth: 0 }} id={labelId}>
      <label htmlFor={inputId} className="sr-only">
        Group name
      </label>
      <input
        id={inputId}
        className="field rename-field"
        value={draft}
        maxLength={50}
        autoFocus
        onFocus={(e) => e.currentTarget.select()}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') {
            e.preventDefault()
            onDone(null)
          }
        }}
        onBlur={() => submit()}
      />
    </form>
  )
}

/** "Riya, Kabir and Meera are in the room" card at the top of a chat whose room is running. */
function RoomBanner({ room, onJoin }: { room: NonNullable<ChatHeaderVM['room']>; onJoin: () => void }): JSX.Element {
  const names = room.names.map((n) => firstName(n.name))
  return (
    <div className="room-banner" role="region" aria-label="Live room">
      <span className="avatar-stack" aria-hidden="true">
        {room.names.slice(0, 3).map((n) => (
          <Avatar key={n.id} name={n.name} id={n.id} photoUrl={n.photoUrl} size={40} />
        ))}
      </span>
      <span style={{ flex: '1 1 260px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span style={{ fontWeight: 500 }}>
          {names.length === 0 ? 'The room is open' : `${joinNames(names)} ${names.length === 1 ? 'is' : 'are'} in the room`}
        </span>
        <span style={{ fontSize: 13, color: 'rgba(var(--ink), 0.76)' }}>{room.summary}</span>
      </span>
      <Button
        style={{ height: 40, padding: '0 16px', borderRadius: 12, borderColor: 'rgba(var(--ink), 0.24)', background: 'rgba(var(--ink), 0.12)', fontWeight: 500 }}
        onClick={onJoin}
      >
        {room.meInRoom ? 'Open' : 'Join'}
      </Button>
    </div>
  )
}

function NoChat({ loading, hasChats, onNewGroup }: { loading: boolean; hasChats: boolean; onNewGroup: () => void }): JSX.Element {
  return (
    <main className="chat-main rise" style={{ '--i': 2, alignItems: 'center', justifyContent: 'center', padding: 24 } as CSSProperties} aria-busy={loading}>
      {loading ? (
        <p className="muted">Loading the chat…</p>
      ) : (
        <div className="chat-empty">
          <span className="chat-empty__icon">
            <Icon name="chat" size={24} />
          </span>
          <span style={{ color: 'var(--text)', fontWeight: 500, fontSize: 19 }}>{hasChats ? 'Pick a chat' : 'No chats yet'}</span>
          <span>
            Every chat has its own room: watch a movie together, stream a game or your screen, and talk.
            {hasChats ? '' : ' Message a friend from Friends, or start a group.'}
          </span>
          {hasChats ? null : (
            <Button size="sm" onClick={onNewGroup} style={{ marginTop: 8 }}>
              New group
            </Button>
          )}
        </div>
      )}
    </main>
  )
}

// ---------- Members (groups) ----------

function MembersPanel({
  header,
  members,
  onPing,
  onOpenProfile,
  onSetAdmin,
  onRemoveMember,
  onGroupSettings,
  onAddPeople,
  onRename
}: {
  header: ChatHeaderVM
  members: GroupMemberVM[] | null
  onPing: (uid: string) => void
  onOpenProfile?: (uid: string) => void
  onSetAdmin?: (uid: string, admin: boolean) => void
  onRemoveMember?: (uid: string) => void
  onGroupSettings?: (onlyAdminsSend: boolean, onlyAdminsEdit: boolean) => void
  onAddPeople: () => void
  onRename: () => void
}): JSX.Element {
  const amAdmin = header.group?.amAdmin === true
  // Admins manage everyone but themselves and the creator (same as the rules and the phone).
  const menuFor = (m: GroupMemberVM): MenuItem[] => {
    const items: MenuItem[] = []
    if (onOpenProfile && !m.isMe) items.push({ label: 'View profile', icon: 'friends', onSelect: () => onOpenProfile(m.id) })
    if (amAdmin && !m.isMe && !m.creator) {
      if (onSetAdmin) items.push({ label: m.admin ? 'Dismiss as admin' : 'Make group admin', icon: 'star', onSelect: () => onSetAdmin(m.id, !m.admin) })
      if (onRemoveMember) items.push({ label: 'Remove from group', icon: 'trash', danger: true, onSelect: () => onRemoveMember(m.id) })
    }
    return items
  }
  const inRoom = (members ?? []).filter((m) => m.inRoom)
  const out = (members ?? []).filter((m) => !m.inRoom)
  const live = header.room !== null
  return (
    <aside id="members-panel" aria-labelledby="h-members" className="glass side-panel rise" style={{ '--i': 3 } as CSSProperties}>
      <h2 id="h-members" className="h2" style={{ fontSize: 19, letterSpacing: 0 }}>
        Members{members ? ` · ${members.length}` : ''}
      </h2>
      <div className="scroll" style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 12, margin: '0 -4px', padding: '0 4px' }}>
        {members === null ? <p className="muted small">Loading members…</p> : null}
        {live && inRoom.length > 0 ? (
          <>
            <span className="section-label section-label--live">In the room</span>
            {inRoom.map((m) => (
              <MemberRow key={m.id} member={m} onPing={onPing} onOpen={onOpenProfile} menu={menuFor(m)} />
            ))}
            <div className="divider" />
          </>
        ) : null}
        {live && out.length > 0 ? <span className="section-label">Not in the room</span> : null}
        {out.map((m) => (
          <MemberRow key={m.id} member={m} onPing={onPing} onOpen={onOpenProfile} menu={menuFor(m)} />
        ))}
      </div>
      {amAdmin && onGroupSettings && header.group ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div className="divider" />
          <span className="section-label">Group settings</span>
          <label className="setting-switch">
            <span>Only admins can send messages</span>
            <Switch label="Only admins can send messages" checked={header.group.onlyAdminsSend} onChange={(v) => onGroupSettings(v, header.group?.onlyAdminsEdit ?? false)} />
          </label>
          <label className="setting-switch">
            <span>Only admins can edit group info</span>
            <Switch label="Only admins can edit group info" checked={header.group.onlyAdminsEdit} onChange={(v) => onGroupSettings(header.group?.onlyAdminsSend ?? false, v)} />
          </label>
          <span className="subtle">Admins add and remove people and make others admin. Nobody can remove the group&apos;s creator.</span>
        </div>
      ) : null}
      <div style={{ display: 'flex', gap: 8 }}>
        {amAdmin ? (
          <Button style={{ flex: 1, height: 40 }} onClick={onAddPeople}>
            Add people
          </Button>
        ) : null}
        {header.canRename ? (
          <Button style={{ flex: 1, height: 40 }} onClick={onRename}>
            Rename group
          </Button>
        ) : null}
      </div>
    </aside>
  )
}

/** A member with Ping / "Pinged · 1 min" (group panel and the room's "Not here yet"). */
export function MemberRow({
  member: m,
  onPing,
  onOpen,
  menu = [],
  size = 38
}: {
  member: GroupMemberVM
  onPing: (uid: string) => void
  /** Their profile (not for "You"). */
  onOpen?: (uid: string) => void
  /** Group info actions for this member (admins). */
  menu?: MenuItem[]
  size?: number
}): JSX.Element {
  return (
    <div className="member-row">
      <button type="button" className="person-open" disabled={!onOpen || m.isMe} aria-label={onOpen && !m.isMe ? `${m.name}'s profile` : undefined} onClick={() => onOpen?.(m.id)}>
        <Avatar name={m.name} id={m.id} photoUrl={m.photoUrl} size={size} muted={m.online === false} />
      </button>
      <span className="member-row__text">
        <span className="member-row__name truncate">
          {m.isMe ? 'You' : m.name}
          {m.admin ? <span className="admin-tag">Admin</span> : null}
        </span>
        <span className="member-row__detail truncate">{m.detail}</span>
      </span>
      {m.ping === 'can' ? (
        <Button variant="primary" size="xs" aria-label={`Ping ${m.name}`} onClick={() => onPing(m.id)}>
          Ping
        </Button>
      ) : m.ping ? (
        <Button size="xs" className="btn--pinged" disabled>
          {m.ping.pingedLabel}
        </Button>
      ) : null}
      {menu.length > 0 ? <MenuButton label={`Options for ${m.name}`} items={menu} small /> : null}
    </div>
  )
}

/** Forward a message to friends (1:1 chats), like the phone. */
function ForwardDialog({ open, friends, onClose, onSend }: { open: boolean; friends: PersonVM[] | null; onClose: () => void; onSend: (ids: string[]) => void }): JSX.Element {
  const [picked, setPicked] = useState<string[]>([])
  useEffect(() => {
    if (open) setPicked([])
  }, [open])
  return (
    <Dialog open={open} title="Forward to" onClose={onClose}>
      {friends === null ? <p className="muted small">Loading friends…</p> : null}
      <ul className="pick-list">
        {(friends ?? []).map((f) => (
          <li key={f.id}>
            <label className="pick-row">
              <Avatar name={f.name} id={f.id} photoUrl={f.photoUrl} size={34} />
              <span style={{ flex: 1, minWidth: 0 }} className="truncate">
                {f.name}
              </span>
              <input
                type="checkbox"
                checked={picked.includes(f.id)}
                onChange={(e) => setPicked((cur) => (e.target.checked ? [...cur, f.id] : cur.filter((id) => id !== f.id)))}
              />
            </label>
          </li>
        ))}
      </ul>
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
        <Button onClick={onClose}>Cancel</Button>
        <Button
          variant="primary"
          disabled={picked.length === 0}
          onClick={() => {
            onSend(picked)
            onClose()
          }}
        >
          {picked.length > 1 ? `Send to ${picked.length}` : 'Send'}
        </Button>
      </div>
    </Dialog>
  )
}

// ---------- New group / Add people ----------

function PeopleDialog({
  open,
  mode,
  friends,
  exclude,
  invited = [],
  onClose,
  onDone
}: {
  open: boolean
  mode: 'newGroup' | 'addPeople'
  friends: PersonVM[] | null
  exclude: string[]
  /** Already invited and not answered yet: shown as "Invited", not pickable. */
  invited?: readonly string[]
  onClose: () => void
  onDone: (name: string, memberIds: string[]) => void
}): JSX.Element {
  const [name, setName] = useState('')
  const [picked, setPicked] = useState<string[]>([])
  const nameId = useId()
  const choices = (friends ?? []).filter((f) => !exclude.includes(f.id))
  const newGroup = mode === 'newGroup'
  // A new group needs only a name (friends are optional: it can start with just you).
  const ready = newGroup ? name.trim().length > 0 : picked.length > 0

  useEffect(() => {
    if (open) {
      setName('')
      setPicked([])
    }
  }, [open])

  return (
    <Dialog
      open={open}
      title={newGroup ? 'New group' : 'Add people'}
      description={
        newGroup
          ? 'A group has its own chat and its own room. Add friends now or later; you can rename it any time.'
          : 'They can see the chat and join the room.'
      }
      onClose={onClose}
    >
      <form
        style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
        onSubmit={(e) => {
          e.preventDefault()
          if (!ready) return
          onDone(name.trim(), picked)
          onClose()
        }}
      >
        {newGroup ? (
          <>
            <label htmlFor={nameId} className="section-label">
              Group name
            </label>
            <input
              id={nameId}
              className="field"
              style={{ marginTop: -8 }}
              placeholder="Movie Night"
              maxLength={50}
              value={name}
              autoFocus
              onChange={(e) => setName(e.target.value)}
            />
          </>
        ) : null}
        <fieldset style={{ border: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
          <legend className="section-label" style={{ marginBottom: 8 }}>
            {newGroup ? 'Friends (optional)' : 'Friends'}
          </legend>
          {friends === null ? <p className="muted small">Loading friends…</p> : null}
          {friends !== null && choices.length === 0 ? (
            <p className="muted small" style={{ margin: 0 }}>
              {newGroup ? 'No friends yet. You can start the group on your own and add people later.' : 'All your friends are already in this group.'}
            </p>
          ) : null}
          <ul className="pick-list">
            {choices.map((f) => (
              <li key={f.id}>
                <label className="pick-row">
                  <Avatar name={f.name} id={f.id} photoUrl={f.photoUrl} size={34} />
                  <span style={{ flex: 1, minWidth: 0 }} className="truncate">
                    {f.name}
                  </span>
                  {invited.includes(f.id) ? (
                    <span className="subtle">Invited</span>
                  ) : (
                    <input
                      type="checkbox"
                      checked={picked.includes(f.id)}
                      onChange={(e) => setPicked((cur) => (e.target.checked ? [...cur, f.id] : cur.filter((id) => id !== f.id)))}
                    />
                  )}
                </label>
              </li>
            ))}
          </ul>
        </fieldset>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button variant="primary" type="submit" disabled={!ready}>
            {newGroup ? (picked.length === 0 ? 'Create with just me' : 'Create group') : picked.length > 1 ? `Add ${picked.length} people` : 'Add'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
