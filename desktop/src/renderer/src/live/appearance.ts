import { useEffect, useState } from 'react'

// Theme (System / Dark / Light) and density (Comfortable / Compact), remembered on this PC.
// Applied as <html data-theme="dark|light" data-density="compact">; global.css holds the tokens.

export type ThemeMode = 'system' | 'dark' | 'light'
export type Density = 'comfortable' | 'compact'

const THEME_KEY = 'bj.theme'
const DENSITY_KEY = 'bj.density'

/** Snapshots (BJ_THEME=light|dark): a theme for this window only, never saved. */
const FORCED_THEME = new URLSearchParams(window.location.search).get('theme')

function read<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  if (key === THEME_KEY && (FORCED_THEME === 'light' || FORCED_THEME === 'dark')) return FORCED_THEME as T
  try {
    const v = localStorage.getItem(key)
    return v && (allowed as readonly string[]).includes(v) ? (v as T) : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Only this session.
  }
}

const systemQuery = (): MediaQueryList => window.matchMedia('(prefers-color-scheme: light)')

function resolve(mode: ThemeMode): 'dark' | 'light' {
  return mode === 'system' ? (systemQuery().matches ? 'light' : 'dark') : mode
}

function apply(mode: ThemeMode, density: Density): void {
  const theme = resolve(mode)
  document.documentElement.dataset.theme = theme
  document.documentElement.dataset.density = density
  document.documentElement.style.colorScheme = theme
  const bridge = (window as { bingejam?: { desktop?: { setTheme?: (t: 'dark' | 'light') => void; setZoom?: (f: number) => void } } }).bingejam
  bridge?.desktop?.setTheme?.(theme)
  bridge?.desktop?.setZoom?.(density === 'compact' ? 0.9 : 1)
}

/** Call once before the first render, so the first paint already has the right colours. */
export function applySavedAppearance(): void {
  apply(read<ThemeMode>(THEME_KEY, ['system', 'dark', 'light'], 'dark'), read<Density>(DENSITY_KEY, ['comfortable', 'compact'], 'comfortable'))
}

export function useAppearance(): { theme: ThemeMode; density: Density; setTheme: (t: ThemeMode) => void; setDensity: (d: Density) => void } {
  const [theme, setThemeState] = useState<ThemeMode>(() => read<ThemeMode>(THEME_KEY, ['system', 'dark', 'light'], 'dark'))
  const [density, setDensityState] = useState<Density>(() => read<Density>(DENSITY_KEY, ['comfortable', 'compact'], 'comfortable'))
  useEffect(() => {
    apply(theme, density)
    if (theme !== 'system') return
    const mq = systemQuery()
    const on = (): void => apply(theme, density)
    mq.addEventListener('change', on)
    return () => mq.removeEventListener('change', on)
  }, [theme, density])
  // Other windows (the mini window) follow changes made here.
  useEffect(() => {
    const on = (e: StorageEvent): void => {
      if (e.key === THEME_KEY) setThemeState(read<ThemeMode>(THEME_KEY, ['system', 'dark', 'light'], 'dark'))
      if (e.key === DENSITY_KEY) setDensityState(read<Density>(DENSITY_KEY, ['comfortable', 'compact'], 'comfortable'))
    }
    window.addEventListener('storage', on)
    return () => window.removeEventListener('storage', on)
  }, [])
  return {
    theme,
    density,
    setTheme: (t) => {
      write(THEME_KEY, t)
      setThemeState(t)
    },
    setDensity: (d) => {
      write(DENSITY_KEY, d)
      setDensityState(d)
    }
  }
}
