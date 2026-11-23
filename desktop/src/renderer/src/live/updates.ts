import { useEffect, useState } from 'react'
import type { UpdateState } from '@shared/update'

// In-app updates (src/main/updater.ts): the main process checks, downloads and installs; this only
// mirrors its state for the update bar and Settings.

export function useUpdateState(): UpdateState {
  const [state, setState] = useState<UpdateState>({ status: 'idle' })
  useEffect(() => {
    const bridge = window.bingejam.update
    if (!bridge) return
    let live = true
    void bridge.get().then((s) => live && setState(s))
    const off = bridge.onState(setState)
    return () => {
      live = false
      off()
    }
  }, [])
  return state
}

const LAST_VERSION = 'bj.lastVersion'

/** "Updated to 0.1.3" once, on the first start after an update. Null otherwise. */
export function justUpdatedTo(version: string): string | null {
  if (!version) return null
  try {
    const last = localStorage.getItem(LAST_VERSION)
    localStorage.setItem(LAST_VERSION, version)
    return last && last !== version ? version : null
  } catch {
    return null
  }
}
