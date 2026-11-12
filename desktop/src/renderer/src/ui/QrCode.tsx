import { useMemo, type JSX } from 'react'
import qrcode from 'qrcode-generator'

/** A QR code as crisp SVG, always dark on white (what phone cameras read best). */
export function QrCode({ text, size = 200, label }: { text: string; size?: number; label: string }): JSX.Element {
  const path = useMemo(() => {
    const qr = qrcode(0, 'M')
    qr.addData(text)
    qr.make()
    const n = qr.getModuleCount()
    let d = ''
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (qr.isDark(r, c)) d += `M${c} ${r}h1v1h-1z`
    return { d, n }
  }, [text])
  return (
    <svg role="img" aria-label={label} width={size} height={size} viewBox={`-2 -2 ${path.n + 4} ${path.n + 4}`} style={{ background: '#fff', borderRadius: 12, display: 'block' }}>
      <path d={path.d} fill="#000" shapeRendering="crispEdges" />
    </svg>
  )
}
