import { useEffect, useState } from 'react'
import type { DetectedBrowser, ExtensionStatus } from '@shared/extension'

// The browser extension's link status and the installed browsers, from the main process
// (src/main/extension). Both are null until the first answer.

export function useExtensionStatus(): ExtensionStatus | null {
  const [status, setStatus] = useState<ExtensionStatus | null>(null)
  useEffect(() => {
    let live = true
    const off = window.bingejam.extension.onStatus((s) => live && setStatus(s))
    void window.bingejam.extension.status().then((s) => live && setStatus(s), () => undefined)
    return () => {
      live = false
      off()
    }
  }, [])
  return status
}

/** Re-detected whenever which browsers are linked changes (a new install shows up at once). */
export function useDetectedBrowsers(linkedKey: string): DetectedBrowser[] | null {
  const [found, setFound] = useState<DetectedBrowser[] | null>(null)
  useEffect(() => {
    let live = true
    void window.bingejam.browsers.detect().then((b) => live && setFound(b), () => live && setFound([]))
    return () => {
      live = false
    }
  }, [linkedKey])
  return found
}
