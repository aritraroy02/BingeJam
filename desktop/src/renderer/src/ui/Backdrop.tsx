import type { JSX } from 'react'

// Behind every screen: the plain warm black (2026-11-12 unslop). The drifting colour glows are gone;
// the colour now comes from the content (posters, artwork, faces). The preset names stay so screens
// can keep asking for one.
export type BackdropPreset = 'home' | 'chat' | 'group' | 'room' | 'live' | 'browser' | 'calm'

export function Backdrop(_props: { preset?: BackdropPreset }): JSX.Element {
  return <div className="backdrop" aria-hidden="true" />
}
