import type { ShareSourceVM } from '@/ui/models'

// Demo Go Live (BJ_DEMO=1): a made-up game drawn on a canvas stands in for a friend's shared screen,
// and the picker offers a few made-up windows. Nothing is captured from this PC.

function thumb(label: string, top: string, bottom: string): string {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="352" height="198"><defs><linearGradient id="g" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${top}"/><stop offset="1" stop-color="${bottom}"/></linearGradient></defs><rect width="352" height="198" fill="url(#g)"/><text x="16" y="182" font-family="Segoe UI, sans-serif" font-size="14" fill="#efe9de" opacity=".8">${label}</text></svg>`
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

export const DEMO_SOURCES: ShareSourceVM[] = [
  { id: 'window:1', name: 'Skyline Racers', kind: 'window', thumbnail: thumb('Skyline Racers', '#26303d', '#0f1217'), icon: null, blocked: false },
  { id: 'window:2', name: 'Visual Studio Code', kind: 'window', thumbnail: thumb('Visual Studio Code', '#1f2430', '#181b22'), icon: null, blocked: false },
  { id: 'window:3', name: 'Netflix - Microsoft Edge', kind: 'window', thumbnail: thumb('Netflix', '#141414', '#0a0a0a'), icon: null, blocked: true },
  { id: 'window:4', name: 'Spotify Premium', kind: 'window', thumbnail: thumb('Spotify', '#1d2a22', '#121614'), icon: null, blocked: false },
  { id: 'screen:0', name: 'Entire screen', kind: 'screen', thumbnail: thumb('Screen 1', '#2a2622', '#161412'), icon: null, blocked: false }
]

/** A little racing scene at 30 fps, like a game someone is sharing. */
export function demoScreen(title: string): MediaStream {
  const canvas = document.createElement('canvas')
  canvas.width = 1280
  canvas.height = 720
  const g = canvas.getContext('2d')
  let frame = 0
  const draw = (): void => {
    if (!g) return
    frame++
    const sky = g.createLinearGradient(0, 0, 0, 420)
    sky.addColorStop(0, '#1b2433')
    sky.addColorStop(1, '#3a3a46')
    g.fillStyle = sky
    g.fillRect(0, 0, 1280, 420)
    g.fillStyle = '#1a1917'
    g.fillRect(0, 420, 1280, 300)
    // Road and its moving lane marks.
    g.fillStyle = '#2c2a26'
    g.beginPath()
    g.moveTo(560, 420)
    g.lineTo(720, 420)
    g.lineTo(1180, 720)
    g.lineTo(100, 720)
    g.fill()
    g.fillStyle = '#efe9de'
    for (let i = 0; i < 6; i++) {
      const t = ((frame * 0.02 + i / 6) % 1) ** 2
      const y = 420 + t * 300
      const w = 4 + t * 16
      g.fillRect(640 - w / 2, y, w, 6 + t * 30)
    }
    // Skyline.
    g.fillStyle = '#12161d'
    for (let i = 0; i < 12; i++) g.fillRect(i * 110 + ((i * 37) % 40), 260 + ((i * 53) % 120), 80, 200)
    // The car.
    g.fillStyle = '#c8102e'
    g.fillRect(590, 600, 100, 60)
    g.fillStyle = '#efe9de'
    g.font = '500 28px Segoe UI, sans-serif'
    g.fillText(title, 40, 60)
    g.font = '24px Segoe UI, sans-serif'
    g.fillText(`Lap 2/3   ${(184 + (frame % 9)).toString()} km/h`, 40, 100)
  }
  draw()
  const timer = setInterval(draw, 33)
  const stream = canvas.captureStream(30)
  stream.getVideoTracks()[0]?.addEventListener('ended', () => clearInterval(timer))
  return stream
}
