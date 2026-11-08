import type { CSSProperties, JSX } from 'react'

// The drifting colour glows behind every screen (positions and colours from the design).
type Glow = { color: string; opacity: number; blur: number; size: [number, number]; pos: CSSProperties }

const PRESETS: Record<'home' | 'chat' | 'room' | 'live' | 'calm', Glow[]> = {
  home: [
    { color: '#FF2E4D', opacity: 0.55, blur: 130, size: [620, 620], pos: { left: -140, top: -180 } },
    { color: '#7C3AED', opacity: 0.38, blur: 140, size: [640, 640], pos: { right: -220, top: 80 } },
    { color: '#F97316', opacity: 0.34, blur: 140, size: [680, 620], pos: { left: '34%', bottom: -320 } },
    { color: '#BE185D', opacity: 0.45, blur: 110, size: [380, 380], pos: { right: '18%', bottom: -120 } }
  ],
  chat: [
    { color: '#FF2E4D', opacity: 0.48, blur: 140, size: [620, 560], pos: { left: '18%', top: -220 } },
    { color: '#7C3AED', opacity: 0.38, blur: 140, size: [620, 620], pos: { right: -200, top: '25%' } },
    { color: '#F97316', opacity: 0.34, blur: 130, size: [560, 520], pos: { left: -140, bottom: -200 } }
  ],
  room: [
    { color: '#10B981', opacity: 0.3, blur: 140, size: [620, 560], pos: { left: '30%', top: -260 } },
    { color: '#7C3AED', opacity: 0.4, blur: 140, size: [620, 620], pos: { right: -200, top: '30%' } },
    { color: '#FF2E4D', opacity: 0.42, blur: 130, size: [560, 520], pos: { left: -160, bottom: -220 } }
  ],
  live: [
    { color: '#7C3AED', opacity: 0.42, blur: 140, size: [640, 600], pos: { left: '20%', top: -240 } },
    { color: '#F97316', opacity: 0.36, blur: 140, size: [600, 600], pos: { left: -180, bottom: -200 } },
    { color: '#BE185D', opacity: 0.45, blur: 120, size: [480, 480], pos: { right: -120, bottom: -160 } }
  ],
  calm: [
    { color: '#FF2E4D', opacity: 0.42, blur: 140, size: [620, 620], pos: { right: -120, top: -200 } },
    { color: '#7C3AED', opacity: 0.36, blur: 140, size: [640, 640], pos: { left: '28%', bottom: -320 } }
  ]
}

export type BackdropPreset = keyof typeof PRESETS

export function Backdrop({ preset = 'home' }: { preset?: BackdropPreset }): JSX.Element {
  return (
    <div className="backdrop" aria-hidden="true">
      {PRESETS[preset].map((g, i) => (
        <div
          key={`${preset}-${i}`}
          className="glow"
          style={{
            ...g.pos,
            width: g.size[0],
            height: g.size[1],
            background: g.color,
            opacity: g.opacity,
            filter: `blur(${g.blur}px)`
          }}
        />
      ))}
    </div>
  )
}
