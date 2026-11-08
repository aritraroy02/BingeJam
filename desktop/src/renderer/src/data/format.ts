// Times and durations for display, like Android's ChatTimes. Pure, unit-tested.
//
// Timing: every timestamp here is server time (ServerValue.TIMESTAMP) and `now` is our server-time
// estimate (clock.ts). Turning them into wall-clock words with this PC's time zone is display only;
// no sync decision is ever made from these.

export interface TimeOptions {
  /** IANA zone, e.g. "Asia/Kolkata"; undefined = this PC's zone. */
  timeZone?: string
  /** "9:02 pm" (true) or "21:02" (false); undefined = this PC's setting. */
  hour12?: boolean
}

const DAY_MS = 86_400_000
const formatters = new Map<string, Intl.DateTimeFormat>()

function fmt(locale: string, options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = locale + JSON.stringify(options)
  let f = formatters.get(key)
  if (!f) {
    f = new Intl.DateTimeFormat(locale, options)
    formatters.set(key, f)
  }
  return f
}

function part(parts: Intl.DateTimeFormatPart[], type: Intl.DateTimeFormatPartTypes): string {
  return parts.find((p) => p.type === type)?.value ?? ''
}

/** The PC's 12/24-hour preference. */
export function systemHour12(): boolean {
  try {
    return new Intl.DateTimeFormat(undefined, { hour: 'numeric' }).resolvedOptions().hour12 ?? true
  } catch {
    return true
  }
}

/** A message just sent can briefly have no server stamp yet: show it as sent "now". */
export function effectiveSentAt(sentAt: number, now: number): number {
  return sentAt > 0 ? sentAt : now
}

/** Calendar day number of [ms] in [timeZone] (days since 1970-01-01 of that local date). */
export function dayNumber(ms: number, timeZone?: string): number {
  const parts = fmt('en-US', { timeZone, year: 'numeric', month: 'numeric', day: 'numeric' }).formatToParts(ms)
  return Math.round(Date.UTC(Number(part(parts, 'year')), Number(part(parts, 'month')) - 1, Number(part(parts, 'day'))) / DAY_MS)
}

function yearOf(ms: number, timeZone?: string): string {
  return part(fmt('en-US', { timeZone, year: 'numeric' }).formatToParts(ms), 'year')
}

/** "9:02 pm" or "21:02". */
export function timeLabel(sentAt: number, now: number, o: TimeOptions = {}): string {
  const at = effectiveSentAt(sentAt, now)
  if (o.hour12 ?? systemHour12()) {
    const parts = fmt('en-US', { timeZone: o.timeZone, hour: 'numeric', minute: '2-digit', hour12: true }).formatToParts(at)
    // Built from parts: ICU puts a narrow no-break space before "PM"; WhatsApp writes "pm".
    return `${part(parts, 'hour')}:${part(parts, 'minute')} ${part(parts, 'dayPeriod').toLowerCase()}`
  }
  const parts = fmt('en-GB', { timeZone: o.timeZone, hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).formatToParts(at)
  return `${part(parts, 'hour')}:${part(parts, 'minute')}`
}

function daysAgo(sentAt: number, now: number, timeZone?: string): number {
  return dayNumber(now, timeZone) - dayNumber(effectiveSentAt(sentAt, now), timeZone)
}

/**
 * Day separator: "Today", "Yesterday", the weekday within the last week, otherwise
 * "12 October" (this year) or "12 October 2025" (ChatTimes.dayLabel).
 */
export function dayLabel(sentAt: number, now: number, o: TimeOptions = {}): string {
  const at = effectiveSentAt(sentAt, now)
  const ago = daysAgo(at, now, o.timeZone)
  // <= 0: a server stamp slightly ahead of our clock across midnight still reads "Today".
  if (ago <= 0) return 'Today'
  if (ago === 1) return 'Yesterday'
  if (ago <= 6) return fmt('en-GB', { timeZone: o.timeZone, weekday: 'long' }).format(at)
  const sameYear = yearOf(at, o.timeZone) === yearOf(now, o.timeZone)
  return fmt('en-GB', { timeZone: o.timeZone, day: 'numeric', month: 'long', ...(sameYear ? {} : { year: 'numeric' }) }).format(at)
}

/** Chats list: "9:02 pm" today, "Yesterday", "Mon", "12 Oct", "12 Oct 2025". */
export function listTimeLabel(sentAt: number, now: number, o: TimeOptions = {}): string {
  const at = effectiveSentAt(sentAt, now)
  const ago = daysAgo(at, now, o.timeZone)
  if (ago <= 0) return timeLabel(at, now, o)
  if (ago === 1) return 'Yesterday'
  if (ago <= 6) return fmt('en-GB', { timeZone: o.timeZone, weekday: 'short' }).format(at)
  const sameYear = yearOf(at, o.timeZone) === yearOf(now, o.timeZone)
  return fmt('en-GB', { timeZone: o.timeZone, day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }) }).format(at)
}

/** Short age: "now", "5 min", "2 h", "3 d", "2 w" (for "Offline · 2 h", "Pinged · 1 min"). */
export function agoShort(ms: number): string {
  const minutes = Math.floor(Math.max(0, ms) / 60_000)
  if (minutes < 1) return 'now'
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} h`
  const days = Math.floor(hours / 24)
  return days < 7 ? `${days} d` : `${Math.floor(days / 7)} w`
}

/** "Last seen just now / 5 min ago / 2 h ago / 3 d ago / over a week ago"; unknown → "Offline". */
export function lastSeenText(lastSeen: number | null, now: number): string {
  if (lastSeen === null || lastSeen <= 0) return 'Offline'
  // Clock estimates can put lastSeen slightly in the future; treat that as "just now".
  const minutes = Math.floor(Math.max(0, now - lastSeen) / 60_000)
  if (minutes < 1) return 'Last seen just now'
  if (minutes < 60) return `Last seen ${minutes} min ago`
  if (minutes < 24 * 60) return `Last seen ${Math.floor(minutes / 60)} h ago`
  if (minutes < 7 * 24 * 60) return `Last seen ${Math.floor(minutes / (24 * 60))} d ago`
  return 'Last seen over a week ago'
}

/** Watched-together length: "1 h 42 m", "2 h", "45 m" (whole minutes, at least 1). */
export function durationLabel(ms: number): string {
  const minutes = Math.max(1, Math.floor(ms / 60_000))
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  if (h === 0) return `${m} m`
  return m === 0 ? `${h} h` : `${h} h ${m} m`
}

/** How long a room has been running: "just now", "18 min", "2 h", "1 h 5 min". */
export function elapsedLabel(startedAt: number, now: number): string {
  const minutes = Math.floor(Math.max(0, now - startedAt) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min`
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return m === 0 ? `${h} h` : `${h} h ${m} min`
}
