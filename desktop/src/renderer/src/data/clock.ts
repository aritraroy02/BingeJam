import { useEffect, useState } from 'react'
import { onValue, ref } from 'firebase/database'
import { db } from '@/lib/firebase'

// Firebase server time, like Android's FirebaseServerClock. Firebase publishes
// .info/serverTimeOffset = server time − this PC's wall clock. We read the wall clock once per
// offset update to set an anchor, then advance with performance.now() (monotonic), so a user
// changing the PC's clock can't shift anything:
//   anchorServer = Date.now() at the update + offset
//   serverNow()  = anchorServer + (performance.now() − anchorMono)
// This is the only place in the data layer that reads the wall clock.

let anchorServer = Date.now()
let anchorMono = performance.now()
let listening = false
let synced = false

function listen(): void {
  if (listening) return
  listening = true
  onValue(ref(db, '.info/serverTimeOffset'), (s) => {
    const offset = s.val()
    if (typeof offset !== 'number') return
    const mono = performance.now()
    anchorServer = Date.now() + offset
    anchorMono = mono
    synced = true
  })
}

export function serverNow(): number {
  listen()
  return anchorServer + (performance.now() - anchorMono)
}

/** False until Firebase has sent an offset. */
export function isServerClockSynced(): boolean {
  listen()
  return synced
}

/** Same shape as src/shared/sync's ServerClock, so the sync engine can use it too. */
export const serverClock = { serverNowMs: serverNow }

/** Server time, refreshed every [tickMs] so "Last seen 5 min ago" stays current (Android: 30 s). */
export function useServerNow(tickMs = 30_000): number {
  const [now, setNow] = useState(serverNow)
  useEffect(() => {
    const timer = setInterval(() => setNow(serverNow()), tickMs)
    return () => clearInterval(timer)
  }, [tickMs])
  return now
}
