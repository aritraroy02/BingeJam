// Dates of birth, as on the phone (app/.../ui/profile/BirthDate.kt, 2026-11-10): stored as
// "YYYY-MM-DD" in users_private/{uid}/dob (owner + app owner only). BingeJam is 18+ (Terms).
// Pure (no Firebase) so it runs under `node --test`. Dates are calendar days, not instants, so
// they're handled as {y, m, d} numbers; "today" is the person's local calendar day.

export const EARLIEST_YEAR = 1920
export const MIN_AGE = 18

export interface CalendarDate {
  y: number
  m: number // 1–12
  d: number // 1–31
}

const WIRE = /^(19|20)[0-9][0-9]-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$/

function daysInMonth(y: number, m: number): number {
  return new Date(Date.UTC(y, m, 0)).getUTCDate()
}

/** "2001-04-09" → date; null for anything the rules wouldn't accept or that isn't a real day. */
export function parseBirthDate(value: string | null | undefined): CalendarDate | null {
  if (!value || !WIRE.test(value)) return null
  const [y, m, d] = value.split('-').map(Number) as [number, number, number]
  return d <= daysInMonth(y, m) ? { y, m, d } : null
}

export function wireBirthDate(date: CalendarDate): string {
  return `${date.y}-${String(date.m).padStart(2, '0')}-${String(date.d).padStart(2, '0')}`
}

/** The person's local calendar day (a date of birth isn't a sync decision; the wall clock is right here). */
export function todayLocal(now: Date = new Date()): CalendarDate {
  return { y: now.getFullYear(), m: now.getMonth() + 1, d: now.getDate() }
}

function compare(a: CalendarDate, b: CalendarDate): number {
  return a.y - b.y || a.m - b.m || a.d - b.d
}

/** Whole years between [date] and [today] (like java.time.Period.years). */
export function ageOn(date: CalendarDate, today: CalendarDate): number {
  let years = today.y - date.y
  if (today.m < date.m || (today.m === date.m && today.d < date.d)) years--
  return years
}

/** What's wrong with [date], worded for the person entering it, or null if it's fine. Same text as the phone. */
export function birthDateProblem(date: CalendarDate | null, today: CalendarDate): string | null {
  if (!date || date.y < EARLIEST_YEAR || compare(date, today) > 0) return "That doesn't look like a real date of birth"
  if (ageOn(date, today) < MIN_AGE) return `You need to be ${MIN_AGE} or older to use BingeJam`
  return null
}
