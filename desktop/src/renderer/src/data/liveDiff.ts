// Which database listeners to stop and start when a screen's list of reads changes (live.ts
// useLiveMany). Pure, so it's tested without Firebase.
//
// A listener is its key AND its path. Screens name their reads by role ("msgs", "room") and keep the
// name when the chat changes, so keying by name alone kept the first chat's listener alive and every
// chat showed the first one's messages (fixed 2026-11-23).

export interface ListenTarget {
  key: string
  path: string
}

/** The identity of one listener. */
export function listenerId(t: ListenTarget): string {
  return `${t.key}\u0000${t.path}`
}

/**
 * [running]: listener id → the key its value is stored under. Returns the ids to stop, the keys whose
 * stored value is now stale (cleared so screens show loading, not the old chat), and what to start.
 */
export function planListeners<T extends ListenTarget>(
  running: ReadonlyMap<string, string>,
  wanted: readonly T[]
): { stop: string[]; staleKeys: string[]; start: T[] } {
  const wantedIds = new Map(wanted.map((t) => [listenerId(t), t]))
  const stop: string[] = []
  const staleKeys: string[] = []
  for (const [id, key] of running) {
    if (wantedIds.has(id)) continue
    stop.push(id)
    staleKeys.push(key)
  }
  const start = [...wantedIds].filter(([id]) => !running.has(id)).map(([, t]) => t)
  return { stop, staleKeys, start }
}
