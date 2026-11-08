// DM typing indicator timing (Android TypingIndicator, TypingThrottle, TypingSignal). Pure, unit-tested.
// typing/{dmId}/{uid} = server timestamp. Writers refresh at most every 3 s (monotonic clock); readers
// count an entry as typing while it's younger than 6 s in server time.

export const TYPING_REFRESH_MS = 3_000
export const TYPING_STALE_MS = 6_000
/** An entry this far in the future (bad server-time estimate) still counts, but no further. */
export const TYPING_MAX_FUTURE_SKEW_MS = 3_000
export const TYPING_TICK_MS = 1_000

/** Is an entry stamped at [stampMs] (server time) still "typing" at [serverNowMs]? */
export function isTypingFresh(stampMs: number, serverNowMs: number): boolean {
  const age = serverNowMs - stampMs
  return age >= -TYPING_MAX_FUTURE_SKEW_MS && age < TYPING_STALE_MS
}

/** Delay before re-checking an unchanged entry: until it goes stale (+1 ms), at most a tick. */
export function nextTypingCheckMs(stampMs: number, serverNowMs: number): number {
  return Math.min(TYPING_TICK_MS, Math.max(1, stampMs + TYPING_STALE_MS - serverNowMs + 1))
}

/** Per-chat write throttle. [now] must be monotonic (performance.now()), never the wall clock. */
export class TypingThrottle {
  private readonly now: () => number
  private readonly lastWriteAt = new Map<string, number>()

  constructor(now: () => number) {
    this.now = now
  }

  /** True if a write for [chatId] is due now; records it as written. */
  shouldWrite(chatId: string): boolean {
    const t = this.now()
    const last = this.lastWriteAt.get(chatId)
    if (last !== undefined && t - last < TYPING_REFRESH_MS) return false
    this.lastWriteAt.set(chatId, t)
    return true
  }

  /** Forgets [chatId]; true if we had written an entry since the last clear (something to remove). */
  clear(chatId: string): boolean {
    return this.lastWriteAt.delete(chatId)
  }
}

/**
 * When to tell the friend we're typing. Each change to non-blank text says true (the throttle keeps
 * writes rare); a blank draft, sending and leaving the chat say false, but only after a true.
 */
export class TypingSignal {
  private draft = ''
  lastSent = false

  /** Returns the value to send, or null for "send nothing". */
  onDraftChanged(text: string): boolean | null {
    if (text === this.draft) return null
    this.draft = text
    return text.trim() ? this.mark(true) : this.stop()
  }

  onSent(): boolean | null {
    this.draft = ''
    return this.stop()
  }

  onStopped(): boolean | null {
    return this.stop()
  }

  private stop(): boolean | null {
    return this.lastSent ? this.mark(false) : null
  }

  private mark(value: boolean): boolean {
    this.lastSent = value
    return value
  }
}
