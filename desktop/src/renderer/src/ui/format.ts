// Display formatting only (no sync decisions are made from these).

/** 1453000 → "24:13", 4360000 → "1:12:40". Negative or unknown → "--:--". */
export function formatClock(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '--:--'
  const total = Math.floor(ms / 1000)
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}

/** A message's time for its tooltip: "9:02 pm" in the user's locale. */
export function formatTimeOfDay(epochMs: number): string {
  return new Date(epochMs).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
}

/** "Riya", "Riya and Kabir", "Riya, Kabir and Meera". */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

export function firstName(name: string): string {
  return name.trim().split(/\s+/)[0] ?? name
}
