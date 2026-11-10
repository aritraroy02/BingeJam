import { useEffect, useState } from 'react'
import type { SettingsVM } from '@/ui/models'

// Settings → Voice: microphones / speakers from the browser's device list, and the person's picks
// (remembered on this PC). Voice chat itself comes later; the picks are ready for it.

const KEYS = { input: 'bj.voice.input', output: 'bj.voice.output', ptt: 'bj.voice.pushToTalk' } as const

function load(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function save(key: string, value: string | null): void {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, value)
  } catch {
    // Not remembered; still used this session.
  }
}

export function useVoiceDevices(): {
  vm: SettingsVM['voice']
  setInput: (id: string | null) => void
  setOutput: (id: string | null) => void
  setPushToTalk: (key: string | null) => void
} {
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [inputId, setInputId] = useState(() => load(KEYS.input))
  const [outputId, setOutputId] = useState(() => load(KEYS.output))
  const [ptt, setPtt] = useState(() => load(KEYS.ptt))

  useEffect(() => {
    const md = navigator.mediaDevices
    if (!md?.enumerateDevices) return
    const refresh = (): void => void md.enumerateDevices().then(setDevices, () => undefined)
    refresh()
    md.addEventListener('devicechange', refresh)
    return () => md.removeEventListener('devicechange', refresh)
  }, [])

  // Without microphone permission the labels are empty; those entries can't be told apart, so skip them.
  const list = (kind: MediaDeviceKind): { id: string; label: string }[] =>
    devices.filter((d) => d.kind === kind && d.deviceId !== 'default' && d.deviceId !== 'communications' && d.label).map((d) => ({ id: d.deviceId, label: d.label }))

  return {
    vm: { inputs: list('audioinput'), outputs: list('audiooutput'), inputId, outputId, pushToTalkKey: ptt },
    setInput: (id) => {
      setInputId(id)
      save(KEYS.input, id)
    },
    setOutput: (id) => {
      setOutputId(id)
      save(KEYS.output, id)
    },
    setPushToTalk: (key) => {
      setPtt(key)
      save(KEYS.ptt, key)
    }
  }
}
