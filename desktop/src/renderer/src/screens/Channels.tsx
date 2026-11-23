import { useEffect, useId, useState, type CSSProperties, type FormEvent, type JSX } from 'react'
import { Icon } from '@/ui/Icon'
import { Avatar, Button, Segmented } from '@/ui/primitives'
import { ConfirmDialog, Dialog } from '@/ui/Dialog'
import { MenuButton, type MenuItem } from '@/ui/Menu'
import { SignalBars, serverDetail, serverLine } from '@/ui/VoiceDock'
import type { ChannelVM, GroupChannelsVM, ShareSourceVM, VoiceConnectionVM, VoiceOccupantVM } from '@/ui/models'
import { LiveBadge, ShareDialog, StreamVideo, StreamView, type ShareQuality } from './GoLive'

// A group's channels, Discord style (PC app, 2026-11-22): the column that takes the chat list's
// place while a group is open (text channels, voice channels with who's in them and the call timer),
// and the voice stage the main area shows for a voice channel.

export type ChannelKind = 'text' | 'voice'

const STATUS_MAX = 60
const NAME_MAX = 32

export function GroupChannels({
  groupName,
  channels,
  selected,
  voice,
  menu,
  renaming,
  onRenameGroup,
  onBack,
  onSelect,
  onJoinVoice,
  onCreate,
  onRename,
  onDelete,
  onSetStatus,
  onOpenProfile
}: {
  groupName: string
  channels: GroupChannelsVM
  selected: string
  voice: VoiceConnectionVM | null
  /** The group's own actions (rename, add people…), in the ⌄ next to its name. */
  menu: MenuItem[]
  renaming: boolean
  onRenameGroup: (name: string | null) => void
  onBack: () => void
  onSelect: (channelId: string) => void
  onJoinVoice: (channelId: string) => void
  onCreate: (name: string, kind: ChannelKind) => void
  onRename: (channelId: string, name: string, kind: ChannelKind) => void
  onDelete: (channelId: string) => void
  onSetStatus: (channelId: string, text: string) => void
  onOpenProfile?: (uid: string) => void
}): JSX.Element {
  const [creating, setCreating] = useState<ChannelKind | null>(null)
  const [editing, setEditing] = useState<ChannelVM | null>(null)
  const [deleting, setDeleting] = useState<ChannelVM | null>(null)

  const manage = (c: ChannelVM): MenuItem[] =>
    channels.canManage && !c.builtIn
      ? [
          { label: 'Rename channel', icon: 'edit', onSelect: () => setEditing(c) },
          { label: 'Delete channel', icon: 'trash', danger: true, onSelect: () => setDeleting(c) }
        ]
      : []

  return (
    <section aria-label={`${groupName} channels`} className="glass channel-list rise" style={{ '--i': 1 } as CSSProperties}>
      <header className="channel-list__head">
        <button type="button" className="icon-btn-sm pressable" aria-label="All chats" title="All chats" onClick={onBack}>
          <Icon name="chevronLeft" size={16} />
        </button>
        {renaming ? (
          <GroupNameField name={groupName} onDone={onRenameGroup} />
        ) : (
          <h2 className="channel-list__name truncate" title={groupName}>
            {groupName}
          </h2>
        )}
        {menu.length > 0 ? <MenuButton label={`${groupName} options`} items={menu} small /> : null}
      </header>

      <div className="scroll channel-list__body">
        <ChannelSection title="Text channels" kind="text" canManage={channels.canManage} onAdd={() => setCreating('text')}>
          {channels.text.map((c) => (
            <li key={c.id} className="channel">
              <div className="channel-row-wrap">
                <button
                  type="button"
                  className={c.unread ? 'channel-row channel-row--unread' : 'channel-row'}
                  aria-current={selected === c.id ? 'true' : undefined}
                  onClick={() => onSelect(c.id)}
                >
                  <Icon name="hash" size={18} />
                  <span className="channel-row__name truncate">{c.name}</span>
                  {c.unread ? <span className="sr-only">, unread</span> : null}
                </button>
                {manage(c).length > 0 ? (
                  <span className="channel-row__menu">
                    <MenuButton label={`Options for #${c.name}`} items={manage(c)} small />
                  </span>
                ) : null}
              </div>
            </li>
          ))}
        </ChannelSection>

        <ChannelSection title="Voice channels" kind="voice" canManage={channels.canManage} onAdd={() => setCreating('voice')}>
          {channels.voice.map((c) => (
            <VoiceChannelRow
              key={c.id}
              channel={c}
              selected={selected === c.id}
              voice={c.connected ? voice : null}
              menu={manage(c)}
              onOpen={() => {
                onSelect(c.id)
                if (!c.connected) onJoinVoice(c.id)
              }}
              onSetStatus={(text) => onSetStatus(c.id, text)}
              onOpenProfile={onOpenProfile}
            />
          ))}
        </ChannelSection>
      </div>

      <ChannelDialog
        open={creating !== null}
        mode="create"
        kind={creating ?? 'text'}
        groupName={groupName}
        onClose={() => setCreating(null)}
        onDone={(name, kind) => onCreate(name, kind)}
      />
      <ChannelDialog
        open={editing !== null}
        mode="rename"
        kind={editing?.kind ?? 'text'}
        initialName={editing?.name ?? ''}
        groupName={groupName}
        onClose={() => setEditing(null)}
        onDone={(name, kind) => editing && onRename(editing.id, name, kind)}
      />
      <ConfirmDialog
        open={deleting !== null}
        title={deleting ? `Delete ${deleting.kind === 'text' ? '#' : ''}${deleting.name}?` : 'Delete channel?'}
        description={
          deleting?.kind === 'text'
            ? 'Its messages are deleted for everyone in the group. This can’t be undone.'
            : 'Anyone in it is disconnected. This can’t be undone.'
        }
        confirmLabel="Delete channel"
        danger
        onConfirm={() => deleting && onDelete(deleting.id)}
        onClose={() => setDeleting(null)}
      />
    </section>
  )
}

function ChannelSection({
  title,
  kind,
  canManage,
  onAdd,
  children
}: {
  title: string
  kind: ChannelKind
  canManage: boolean
  onAdd: () => void
  children: JSX.Element[]
}): JSX.Element {
  const [open, setOpen] = useState(true)
  const headId = useId()
  return (
    <div className="channel-section">
      <div className="channel-section__head">
        <button type="button" className="channel-section__toggle" aria-expanded={open} id={headId} onClick={() => setOpen((v) => !v)}>
          <span>{title}</span>
          <span className={open ? 'channel-section__chevron' : 'channel-section__chevron channel-section__chevron--closed'}>
            <Icon name="chevronDown" size={14} />
          </span>
        </button>
        {canManage ? (
          <button
            type="button"
            className="icon-btn-sm icon-btn-sm--quiet pressable"
            aria-label={kind === 'text' ? 'Create text channel' : 'Create voice channel'}
            title={kind === 'text' ? 'Create text channel' : 'Create voice channel'}
            onClick={onAdd}
          >
            <Icon name="plus" size={16} />
          </button>
        ) : null}
      </div>
      {open ? (
        <ul className="channel-section__items" aria-labelledby={headId}>
          {children}
        </ul>
      ) : null}
    </div>
  )
}

function VoiceChannelRow({
  channel: c,
  selected,
  voice,
  menu,
  onOpen,
  onSetStatus,
  onOpenProfile
}: {
  channel: ChannelVM
  selected: boolean
  /** Our connection when we're in this channel. */
  voice: VoiceConnectionVM | null
  menu: MenuItem[]
  onOpen: () => void
  onSetStatus: (text: string) => void
  onOpenProfile?: (uid: string) => void
}): JSX.Element {
  const people = c.people ?? []
  return (
    <li className="channel">
      <div className="channel-row-wrap">
        <button
          type="button"
          className={c.connected ? 'channel-row channel-row--connected' : 'channel-row'}
          aria-current={selected ? 'true' : undefined}
          aria-label={`${c.name}, voice channel${people.length > 0 ? `, ${people.length} in` : ''}${c.connected ? ', connected' : ''}`}
          title={c.connected ? undefined : `Join ${c.name}`}
          onClick={onOpen}
        >
          <Icon name="volume" size={18} />
          <span className="channel-row__name truncate">{c.name}</span>
          {c.elapsed ? <span className="channel-row__timer">{c.elapsed}</span> : null}
        </button>
        {menu.length > 0 ? (
          <span className="channel-row__menu">
            <MenuButton label={`Options for ${c.name}`} items={menu} small />
          </span>
        ) : null}
      </div>
      {c.connected ? (
        <StatusLine status={c.status ?? null} onSave={onSetStatus} />
      ) : c.status ? (
        <span className="channel-status truncate">{c.status}</span>
      ) : null}
      {people.length > 0 ? (
        <ul className="voice-occupants" aria-label={`In ${c.name}`}>
          {people.map((p) => (
            <Occupant key={p.id} person={p} onOpenProfile={onOpenProfile} />
          ))}
        </ul>
      ) : null}
      {voice && voice.status !== 'connected' ? <span className="channel-status">{voice.status === 'reconnecting' ? 'Reconnecting…' : 'Connecting…'}</span> : null}
    </li>
  )
}

function Occupant({ person: p, onOpenProfile }: { person: VoiceOccupantVM; onOpenProfile?: (uid: string) => void }): JSX.Element {
  return (
    <li className={p.speaking ? 'voice-occupant voice-occupant--speaking' : 'voice-occupant'}>
      <button
        type="button"
        className="voice-occupant__open"
        disabled={!onOpenProfile || p.isMe}
        aria-label={p.isMe ? 'You' : `${p.name}'s profile`}
        onClick={() => onOpenProfile?.(p.id)}
      >
        <span className="voice-occupant__face">
          <Avatar name={p.name} id={p.id} photoUrl={p.photoUrl} size={24} />
        </span>
        <span className="voice-occupant__name truncate">{p.name}</span>
      </button>
      {p.live ? <LiveBadge /> : null}
      {p.deafened ? (
        <span className="voice-occupant__icon" title="Deafened">
          <Icon name="headphonesOff" size={14} />
        </span>
      ) : p.muted ? (
        <span className="voice-occupant__icon" title="Muted">
          <Icon name="micOff" size={14} />
        </span>
      ) : null}
    </li>
  )
}

/** "Set a channel status" under the voice channel you're in; Enter saves, Escape cancels, empty clears. */
function StatusLine({ status, onSave }: { status: string | null; onSave: (text: string) => void }): JSX.Element {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(status ?? '')
  useEffect(() => {
    if (!editing) setDraft(status ?? '')
  }, [status, editing])
  if (editing) {
    return (
      <form
        className="channel-status-form"
        onSubmit={(e) => {
          e.preventDefault()
          setEditing(false)
          if (draft.trim() !== (status ?? '')) onSave(draft)
        }}
      >
        <input
          className="field channel-status-field"
          aria-label="Channel status"
          placeholder="What are you up to?"
          maxLength={STATUS_MAX}
          value={draft}
          autoFocus
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              e.preventDefault()
              setEditing(false)
            }
          }}
          onBlur={() => setEditing(false)}
        />
      </form>
    )
  }
  return (
    <button type="button" className="channel-status channel-status--edit" onClick={() => setEditing(true)}>
      <span className="truncate">{status ?? 'Set a channel status'}</span>
      <Icon name="edit" size={12} />
    </button>
  )
}

function GroupNameField({ name, onDone }: { name: string; onDone: (name: string | null) => void }): JSX.Element {
  const [draft, setDraft] = useState(name)
  function submit(e?: FormEvent): void {
    e?.preventDefault()
    onDone(draft.trim() || null)
  }
  return (
    <form onSubmit={submit} style={{ display: 'flex', flex: 1, minWidth: 0 }}>
      <input
        className="field rename-field"
        aria-label="Group name"
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

/** Create a channel (pick text or voice, like Discord) or rename one. */
function ChannelDialog({
  open,
  mode,
  kind: initialKind,
  initialName = '',
  groupName,
  onClose,
  onDone
}: {
  open: boolean
  mode: 'create' | 'rename'
  kind: ChannelKind
  initialName?: string
  groupName: string
  onClose: () => void
  onDone: (name: string, kind: ChannelKind) => void
}): JSX.Element {
  const [name, setName] = useState(initialName)
  const [kind, setKind] = useState<ChannelKind>(initialKind)
  const nameId = useId()
  useEffect(() => {
    if (open) {
      setName(initialName)
      setKind(initialKind)
    }
  }, [open, initialName, initialKind])
  const create = mode === 'create'
  return (
    <Dialog
      open={open}
      title={create ? 'Create channel' : 'Rename channel'}
      description={create ? `In ${groupName}. Everyone in the group can see it.` : undefined}
      onClose={onClose}
    >
      <form
        style={{ display: 'flex', flexDirection: 'column', gap: 16 }}
        onSubmit={(e) => {
          e.preventDefault()
          if (!name.trim()) return
          onDone(name.trim(), kind)
          onClose()
        }}
      >
        {create ? (
          <Segmented
            label="Channel type"
            options={[
              { value: 'text', label: 'Text' },
              { value: 'voice', label: 'Voice' }
            ]}
            value={kind}
            onChange={setKind}
          />
        ) : null}
        <label htmlFor={nameId} className="section-label">
          Channel name
        </label>
        <div className="field-wrap" style={{ marginTop: -8 }}>
          <Icon name={kind === 'text' ? 'hash' : 'volume'} size={16} />
          <input
            id={nameId}
            className="field"
            placeholder={kind === 'text' ? 'new-channel' : 'Gaming'}
            maxLength={NAME_MAX}
            value={name}
            autoFocus
            onChange={(e) => setName(e.target.value)}
          />
        </div>
        {kind === 'text' ? <span className="subtle">Text channel names are lowercase, with dashes for spaces.</span> : null}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <Button onClick={onClose}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={!name.trim()}>
            {create ? 'Create channel' : 'Save'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

// ---------- Voice stage (the main area for a voice channel) ----------

export interface ShareActions {
  listSources: () => Promise<ShareSourceVM[]>
  start: (sourceId: string, quality: ShareQuality, sound: boolean) => void
  stop: () => void
  watch: (uid: string) => void
  stopWatching: () => void
}

export function VoiceStage({
  channel: c,
  groupName,
  voice,
  media,
  share,
  onJoin,
  onLeave,
  onToggleMute,
  onToggleDeafen,
  onOpenProfile
}: {
  channel: ChannelVM
  groupName: string
  /** Our connection when we're in this channel. */
  voice: VoiceConnectionVM | null
  /** The share we watch and our own (Go Live). */
  media?: { watching: MediaStream | null; preview: MediaStream | null }
  /** Go Live; absent = no screen share here. */
  share?: ShareActions
  onJoin: () => void
  onLeave: () => void
  onToggleMute: () => void
  onToggleDeafen: () => void
  onOpenProfile?: (uid: string) => void
}): JSX.Element {
  const people = c.people ?? []
  const titleId = useId()
  // Demo snapshots (BJ_SCREEN=golive) open the picker straight away.
  const [picking, setPicking] = useState(() => /[?&]demo=1/.test(location.search) && /[?&]screen=golive/.test(location.search))
  const connected = voice?.status === 'connected'
  const watching = voice?.watching ?? null
  const watchedName = watching ? people.find((p) => p.id === watching.uid)?.name ?? watching.name : null

  const tiles = (
    <ul className={watching ? 'voice-tiles voice-tiles--strip' : 'voice-tiles'} aria-label={`In ${c.name}`}>
      {people.map((p) => {
        const preview = p.isMe && media?.preview ? media.preview : null
        const canWatch = !!share && !!voice && p.live && !p.isMe && watching?.uid !== p.id
        return (
          <li key={p.id} className={['voice-tile', p.speaking ? 'voice-tile--speaking' : '', preview ? 'voice-tile--video' : ''].filter(Boolean).join(' ')}>
            {preview ? (
              <StreamVideo stream={preview} muted className="voice-tile__video" />
            ) : (
              <button
                type="button"
                className="voice-tile__open"
                disabled={!onOpenProfile || p.isMe}
                aria-label={p.isMe ? 'You' : `${p.name}'s profile`}
                onClick={() => onOpenProfile?.(p.id)}
              >
                <span className="voice-tile__face">
                  <Avatar name={p.name} id={p.id} photoUrl={p.photoUrl} size={watching ? 48 : 80} />
                </span>
              </button>
            )}
            <span className="voice-tile__name">
              {p.live ? <LiveBadge /> : null}
              <span className="truncate">{p.name}</span>
              {p.deafened ? <Icon name="headphonesOff" size={14} /> : p.muted ? <Icon name="micOff" size={14} /> : null}
            </span>
            {canWatch ? (
              <Button size="xs" className="voice-tile__watch" onClick={() => share?.watch(p.id)}>
                Watch
              </Button>
            ) : p.live && !p.isMe && !voice ? (
              <span className="subtle">Join to watch</span>
            ) : null}
          </li>
        )
      })}
    </ul>
  )

  return (
    <main aria-labelledby={titleId} className="chat-main voice-stage rise" style={{ '--i': 2 } as CSSProperties}>
      <header className="chat-header">
        <span className="channel-glyph" aria-hidden="true">
          <Icon name="volume" size={20} />
        </span>
        <div className="chat-header__title">
          <h2 id={titleId} className="chat-header__name truncate">
            {c.name}
          </h2>
          <span className="chat-header__subtitle truncate">{c.status ?? `Voice channel in ${groupName}`}</span>
        </div>
        {c.elapsed ? <span className="channel-row__timer channel-row__timer--header">{c.elapsed}</span> : null}
      </header>

      <div className={watching ? 'voice-stage__body voice-stage__body--watching' : 'voice-stage__body'}>
        {watching && watchedName ? (
          <>
            <StreamView name={watchedName} stream={media?.watching ?? null} deafened={voice?.deafened === true} onStop={() => share?.stopWatching()} />
            {tiles}
          </>
        ) : people.length === 0 ? (
          <div className="chat-empty">
            <span className="chat-empty__icon">
              <Icon name="volume" size={24} />
            </span>
            <span style={{ color: 'var(--text)', fontWeight: 500, fontSize: 16 }}>No one’s in {c.name}</span>
            <span>Join and the others in {groupName} see you here. Talk while you watch, play or just hang out.</span>
          </div>
        ) : (
          tiles
        )}
      </div>

      <footer className="voice-stage__bar">
        {voice ? (
          <>
            <span className="voice-stage__server" title={serverDetail(voice)}>
              <SignalBars quality={voice.status === 'connected' ? voice.quality : null} />
              <span className="truncate">{serverLine(voice)}</span>
            </span>
            <span style={{ flex: 1 }} />
            {share ? (
              voice.sharing ? (
                <button type="button" className="voice-btn voice-btn--live pressable" onClick={share.stop}>
                  <Icon name="screen" />
                  Stop sharing
                  <span className="voice-btn__meta">{voice.sharing.viewers === 1 ? '1 watching' : `${voice.sharing.viewers} watching`}</span>
                </button>
              ) : (
                <button type="button" className="voice-btn pressable" disabled={!connected} onClick={() => setPicking(true)}>
                  <Icon name="screen" />
                  Share screen
                </button>
              )
            ) : null}
            <button
              type="button"
              className="voice-btn voice-btn--icon pressable"
              aria-pressed={voice.muted}
              aria-label={voice.muted ? 'Unmute' : 'Mute'}
              title={voice.muted ? 'Unmute' : 'Mute'}
              onClick={onToggleMute}
            >
              <Icon name={voice.muted ? 'micOff' : 'mic'} />
            </button>
            <button
              type="button"
              className="voice-btn voice-btn--icon pressable"
              aria-pressed={voice.deafened}
              aria-label={voice.deafened ? 'Undeafen' : 'Deafen'}
              title={voice.deafened ? 'Undeafen' : 'Deafen'}
              onClick={onToggleDeafen}
            >
              <Icon name={voice.deafened ? 'headphonesOff' : 'headphones'} />
            </button>
            <button type="button" className="voice-btn voice-btn--leave pressable" onClick={onLeave}>
              <Icon name="hangUp" />
              Disconnect
            </button>
          </>
        ) : (
          <>
            <span className="subtle">{people.length > 0 ? `${people.length} in ${c.name}` : 'Nobody here yet'}</span>
            <span style={{ flex: 1 }} />
            <Button variant="primary" onClick={onJoin}>
              <Icon name="volume" />
              Join voice
            </Button>
          </>
        )}
      </footer>
      {share ? (
        <ShareDialog open={picking} onClose={() => setPicking(false)} listSources={share.listSources} onStart={(id, quality, sound) => share.start(id, quality, sound)} />
      ) : null}
    </main>
  )
}
