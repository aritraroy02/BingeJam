import { useEffect, useId, useLayoutEffect, useRef, useState, type JSX, type KeyboardEvent, type ReactNode } from 'react'
import type { MessageVM } from './models'
import { Icon } from './Icon'
import { avatarTint } from './primitives'
import { formatTimeOfDay } from './format'

// Chat pieces shared by the Chats screen, the room's chat panel and the stream chat.
// Bubble shapes, colours and spacing are copied from design/2-chat-1to1 and 3-group-chat.

/**
 * The message column. Scrolls on its own and sticks to the newest message: it follows new messages
 * while you're at the bottom, stays put while you read older ones, and always jumps down when you
 * send something yourself.
 */
export function MessageList({
  messages,
  resetKey,
  showSenders,
  label,
  empty,
  compact = false
}: {
  messages: MessageVM[] | null
  /** Which chat this is: a new key scrolls to the bottom and skips the entry animation. */
  resetKey: string
  /** Group chats and rooms put the sender's name on other people's bubbles. */
  showSenders: boolean
  label: string
  /** Shown when the chat has no messages yet. */
  empty?: ReactNode
  /** Side panels (room, stream): no padding, narrower gaps, wider bubbles. */
  compact?: boolean
}): JSX.Element {
  const ref = useRef<HTMLDivElement>(null)
  const stick = useRef(true)
  const lastKey = useRef(resetKey)
  const lastId = useRef<string | null>(null)

  // Messages that were already there when the chat opened don't animate in; later ones do.
  const initial = useRef<{ key: string; ids: Set<string> } | null>(null)
  if (messages && (initial.current === null || initial.current.key !== resetKey)) {
    initial.current = { key: resetKey, ids: new Set(messages.map((m) => m.id)) }
  }

  useLayoutEffect(() => {
    const el = ref.current
    if (!el || !messages) return
    if (lastKey.current !== resetKey) {
      lastKey.current = resetKey
      stick.current = true
    }
    const last = messages[messages.length - 1]
    const mineJustSent = last !== undefined && last.id !== lastId.current && last.kind === 'text' && last.fromMe
    lastId.current = last?.id ?? null
    if (stick.current || mineJustSent) el.scrollTop = el.scrollHeight
  }, [messages, resetKey])

  // Window resizes and a growing composer change the height: stay on the newest message.
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const observer = new ResizeObserver(() => {
      if (stick.current) el.scrollTop = el.scrollHeight
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const classes = ['messages', compact ? 'messages--compact' : '', messages?.length === 0 ? 'messages--empty' : '']
  return (
    <div
      ref={ref}
      className={classes.filter(Boolean).join(' ')}
      role="log"
      aria-live="polite"
      aria-busy={messages === null}
      aria-label={label}
      tabIndex={0}
      onScroll={() => {
        const el = ref.current
        if (el) stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 48
      }}
    >
      {messages === null ? <span className="day-pill">Loading messages…</span> : null}
      {messages?.length === 0 ? empty : null}
      {(messages ?? []).map((m, i) => {
        const all = messages ?? []
        const isNew = !initial.current?.ids.has(m.id)
        const anim = isNew ? ' msg-new' : ''
        switch (m.kind) {
          case 'day':
            return (
              <span key={m.id} className={`day-pill${anim}`}>
                {m.label}
              </span>
            )
          case 'event':
            return (
              <span key={m.id} className={`day-pill event-pill${anim}`} title={formatTimeOfDay(m.sentAt)}>
                {m.text}
              </span>
            )
          case 'watched':
            return (
              <div key={m.id} className={`watched-card${anim}`} title={formatTimeOfDay(m.sentAt)}>
                <span className="watched-card__icon">
                  <Icon name="play" size={16} />
                </span>
                <span style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
                  <span style={{ fontWeight: 700 }}>Watched {m.title} together</span>
                  <span className="watched-card__detail">
                    {m.durationLabel} · {m.detail}
                  </span>
                </span>
              </div>
            )
          case 'text': {
            const prev = all[i - 1]
            const sameSender = prev?.kind === 'text' && prev.senderId === m.senderId
            const name = showSenders && !m.fromMe && !sameSender ? (m.senderName ?? null) : null
            return (
              <div
                key={m.id}
                className={`${m.fromMe ? 'bubble bubble--out' : 'bubble'}${m.deleted ? ' bubble--deleted' : ''}${anim}`}
                title={formatTimeOfDay(m.sentAt)}
              >
                {name ? (
                  <div className="bubble__sender" style={{ color: avatarTint(m.senderId) }}>
                    {name}
                  </div>
                ) : null}
                {m.forwarded && !m.deleted ? <div className="bubble__forwarded">Forwarded</div> : null}
                {m.deleted ? 'This message was deleted' : m.text}
              </div>
            )
          }
        }
      })}
    </div>
  )
}

/**
 * Message box: Enter sends, Shift+Enter starts a new line, grows up to about five lines.
 * `onTyping(true)` fires on the first keystroke and `onTyping(false)` after 3 s without typing,
 * on send and on leaving the box (the data layer writes typing/{dmId}/{uid} from it).
 */
export function Composer({
  label,
  placeholder,
  onSend,
  onTyping,
  onAttach,
  variant = 'main',
  disabled = false
}: {
  label: string
  placeholder: string
  onSend: (text: string) => void
  onTyping?: (typing: boolean) => void
  onAttach?: () => void
  /** main: the open chat (design 2/3); panel: room and stream chat (design 4/5). */
  variant?: 'main' | 'panel'
  disabled?: boolean
}): JSX.Element {
  const id = useId()
  const [text, setText] = useState('')
  const input = useRef<HTMLTextAreaElement>(null)
  const typing = useRef(false)
  const idle = useRef<number | undefined>(undefined)

  function setTyping(next: boolean): void {
    window.clearTimeout(idle.current)
    if (next) idle.current = window.setTimeout(() => setTyping(false), 3000)
    if (typing.current !== next) {
      typing.current = next
      onTyping?.(next)
    }
  }

  useEffect(() => () => window.clearTimeout(idle.current), [])

  // Grow with the text (one line = 42px like the design), then scroll inside.
  useLayoutEffect(() => {
    const el = input.current
    if (!el) return
    el.style.height = '42px'
    el.style.height = `${Math.min(el.scrollHeight, 140)}px`
  }, [text])

  function send(): void {
    const value = text.trim()
    if (!value || disabled) return
    onSend(value)
    setText('')
    setTyping(false)
    input.current?.focus()
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>): void {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault()
      send()
    }
  }

  return (
    <div className={`composer composer--${variant}`}>
      {onAttach ? (
        <button type="button" className="composer__icon pressable" aria-label="Attach" onClick={onAttach} disabled={disabled}>
          <Icon name="plus" size={20} />
        </button>
      ) : null}
      <label htmlFor={id} className="sr-only">
        {label}
      </label>
      <textarea
        id={id}
        ref={input}
        rows={1}
        className="composer__input"
        style={{ paddingLeft: onAttach ? 8 : 12, paddingRight: onAttach ? 8 : 12 }}
        placeholder={placeholder}
        value={text}
        disabled={disabled}
        maxLength={4000}
        onChange={(e) => {
          setText(e.target.value)
          setTyping(e.target.value.trim().length > 0)
        }}
        onKeyDown={onKeyDown}
        onBlur={() => setTyping(false)}
      />
      <button type="button" className="composer__send pressable" aria-label="Send" onClick={send} disabled={disabled}>
        <Icon name="send" size={18} />
      </button>
    </div>
  )
}
