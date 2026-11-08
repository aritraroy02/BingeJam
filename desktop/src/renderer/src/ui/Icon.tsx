import type { JSX } from 'react'

// Icons copied from desktop/design/*.html (24×24, 2px round strokes unless noted).
const STROKE: Record<string, JSX.Element> = {
  home: <path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z" />,
  chat: <path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />,
  friends: (
    <>
      <circle cx="9" cy="8" r="4" />
      <path d="M2 21c0-3.9 3.1-7 7-7s7 3.1 7 7" />
      <path d="M19 8v6M16 11h6" />
    </>
  ),
  streams: (
    <>
      <rect x="2" y="7" width="20" height="12" rx="4" />
      <path d="M7 11v4M5 13h4" />
      <circle cx="16" cy="12" r="1" />
      <circle cx="18" cy="14" r="1" />
    </>
  ),
  settings: <path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6" />,
  mic: (
    <>
      <rect x="9" y="2" width="6" height="12" rx="3" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v4" />
    </>
  ),
  search: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="M21 21l-4.3-4.3" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  startRoom: (
    <>
      <rect x="2" y="4" width="20" height="13" rx="3" />
      <path d="M10 8.5v5l4-2.5z" fill="currentColor" />
      <path d="M8 21h8" />
    </>
  ),
  send: <path d="M22 2L11 13M22 2l-7 20-4-9-9-4z" />,
  edit: (
    <>
      <path d="M12 20h9" />
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z" />
    </>
  ),
  back10: (
    <>
      <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
      <path d="M3 3v5h5" />
    </>
  ),
  fwd10: (
    <>
      <path d="M21 12a9 9 0 1 1-3-6.7L21 8" />
      <path d="M21 3v5h-5" />
    </>
  ),
  screen: (
    <>
      <rect x="2" y="4" width="20" height="13" rx="2" />
      <path d="M8 21h8" />
    </>
  ),
  volume: (
    <>
      <path d="M11 5L6 9H2v6h4l5 4z" />
      <path d="M15.5 8.5a5 5 0 0 1 0 7M19 5a10 10 0 0 1 0 14" />
    </>
  ),
  fullscreen: <path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3" />,
  close: <path d="M18 6L6 18M6 6l12 12" />,
  mail: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="M3 7l9 6 9-6" />
    </>
  ),
  link: (
    <>
      <path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7" />
      <path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7" />
    </>
  )
}

const FILL: Record<string, JSX.Element> = {
  play: <path d="M7 4v16l13-8z" />,
  pause: (
    <>
      <rect x="6" y="5" width="4" height="14" rx="1" />
      <rect x="14" y="5" width="4" height="14" rx="1" />
    </>
  ),
  more: (
    <>
      <circle cx="12" cy="5" r="1.8" />
      <circle cx="12" cy="12" r="1.8" />
      <circle cx="12" cy="19" r="1.8" />
    </>
  )
}

export type IconName = keyof typeof STROKE | keyof typeof FILL

export function Icon({ name, size = 20, strokeWidth = 2 }: { name: IconName; size?: number; strokeWidth?: number }): JSX.Element {
  const fill = FILL[name]
  return fill ? (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      {fill}
    </svg>
  ) : (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {STROKE[name]}
    </svg>
  )
}

/** The BingeJam mark: play triangle inside two sync arrows (same as the Android launcher icon). */
export function LogoMark({ size = 26 }: { size?: number }): JSX.Element {
  return (
    <svg width={size} height={size} viewBox="0 0 48 48" aria-hidden="true">
      <path
        fill="#fff"
        d="M24,8V2L16,10l8,8V12c6.62,0 12,5.38 12,12 0,2.02 -0.5,3.94 -1.4,5.6l2.92,2.92C39.08,30.06 40,27.14 40,24c0,-8.84 -7.16,-16 -16,-16zM24,36c-6.62,0 -12,-5.38 -12,-12 0,-2.02 0.5,-3.94 1.4,-5.6L10.48,15.48C8.92,17.94 8,20.86 8,24c0,8.84 7.16,16 16,16v6l8,-8 -8,-8v6z"
      />
      <path fill="#fff" d="M20,17L30,24 20,31z" />
    </svg>
  )
}
