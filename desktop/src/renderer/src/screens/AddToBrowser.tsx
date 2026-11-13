import type { CSSProperties, JSX } from 'react'
import { BRAND } from '@shared/brand'
import { Icon, LogoMark } from '@/ui/Icon'
import { Button, Switch } from '@/ui/primitives'
import type { BrowserId, BrowserSetupVM } from '@/ui/models'

// Add to browser (design/6-add-to-browser): the easiest free route per browser (PLAN.md "Browser
// extension, shipped inside the desktop app"), the connection state and a preview of the popup.

export interface AddToBrowserProps {
  setup: BrowserSetupVM
  /** edge: opens the Edge Add-ons listing; firefox: the signed .xpi; chrome/brave/opera: unpacks the
   *  folder, copies its path and opens that browser's extensions page. */
  onAddTo: (browser: BrowserId) => void
  /** Back to Settings. */
  onBack: () => void
}

const CHROMIUM: BrowserId[] = ['chrome', 'brave', 'opera']

export function AddToBrowser({ setup, onAddTo, onBack }: AddToBrowserProps): JSX.Element {
  const chromium = setup.found.find((b) => CHROMIUM.includes(b.id))?.id ?? 'chrome'
  return (
    <div className="screen">
      <main className="scroll" style={{ flex: '999 1 560px', minWidth: 0, display: 'flex', flexDirection: 'column', gap: 20, margin: -6, padding: 8 }}>
        <section
          aria-labelledby="h-browser"
          className="glass glass--hero glass--raised rise"
          style={{ '--i': 1, padding: 32, display: 'flex', flexDirection: 'column', gap: 12, background: 'rgba(var(--ink), 0.06)' } as CSSProperties}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <button type="button" className="icon-btn-sm pressable" aria-label="Back to Settings" title="Back to Settings" onClick={onBack}>
              <Icon name="chevronLeft" size={16} />
            </button>
            <span className="chip">Browser extension</span>
          </div>
          <h1 id="h-browser" className="h1" style={{ fontSize: 40, lineHeight: 1.05, letterSpacing: -0.4 }}>
            Add {BRAND.name} to your browser
          </h1>
          <p style={{ margin: 0, maxWidth: 680, fontSize: 15, lineHeight: 1.55, color: 'rgba(var(--ink), 0.76)' }}>
            Netflix, Prime Video and JioHotstar play in your browser. With the extension, {BRAND.name} syncs everyone to the frame, waits for anyone
            buffering and follows the host to the next episode.
          </p>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 8 }}>
            {setup.found.length === 0 ? <span className="found-chip">No supported browser found</span> : null}
            {setup.found.map((b) => (
              <span key={b.id} className="found-chip">
                Found: {b.name}
              </span>
            ))}
          </div>
        </section>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(250px, 1fr))', gap: 16 }}>
          <section aria-labelledby="h-edge" className="browser-card browser-card--best rise" style={{ '--i': 2 } as CSSProperties}>
            <div className="browser-card__head">
              <h2 id="h-edge">Microsoft Edge</h2>
              <span className="tag tag--live">Recommended</span>
            </div>
            <p>One click from the free Edge Add-ons store. Updates itself.</p>
            <div style={{ flex: 1 }} />
            <Button variant="primary" size="lg" onClick={() => onAddTo('edge')}>
              Get from Edge Add-ons
            </Button>
          </section>
          <section aria-labelledby="h-ff" className="browser-card rise" style={{ '--i': 3 } as CSSProperties}>
            <div className="browser-card__head">
              <h2 id="h-ff">Firefox</h2>
              <span className="tag">One click</span>
            </div>
            <p>Signed by Mozilla. Firefox asks once.</p>
            <div style={{ flex: 1 }} />
            <Button size="lg" style={{ fontWeight: 500 }} onClick={() => onAddTo('firefox')}>
              Add to Firefox
            </Button>
          </section>
          <section aria-labelledby="h-chrome" className="browser-card rise" style={{ '--i': 4 } as CSSProperties}>
            <div className="browser-card__head">
              <h2 id="h-chrome">Chrome, Brave, Opera</h2>
              <span className="tag">30 s</span>
            </div>
            <ol>
              <li>
                Turn on <b>Developer mode</b>
              </li>
              <li>
                Click <b>Load unpacked</b>
              </li>
              <li>Pick the {BRAND.name} folder (path copied)</li>
            </ol>
            <Button size="lg" style={{ fontWeight: 500 }} onClick={() => onAddTo(chromium)}>
              Open extensions page
            </Button>
          </section>
        </div>

        {setup.connected ? (
          <div className="connect-banner rise" role="status" style={{ '--i': 5 } as CSSProperties}>
            <span className="connect-banner__dot" aria-hidden="true" />
            <span style={{ flex: '1 1 300px', color: '#ECFDF5' }}>
              <b>Connected to {setup.connected.browser}.</b> {setup.connected.detail}
            </span>
            <span style={{ fontSize: 13, color: '#D1FAE5' }}>Reads only Netflix, Prime Video and JioHotstar pages</span>
          </div>
        ) : (
          <div className="connect-banner connect-banner--off rise" role="status" style={{ '--i': 5 } as CSSProperties}>
            <span className="connect-banner__dot" aria-hidden="true" />
            <span style={{ flex: '1 1 300px' }}>
              <b>Not connected yet.</b> After adding it, open Netflix, Prime Video or JioHotstar in that browser once.
            </span>
            <span style={{ fontSize: 13, color: 'var(--text-2)' }}>Reads only Netflix, Prime Video and JioHotstar pages</span>
          </div>
        )}
      </main>

      <aside aria-label="Extension popup preview" className="popup-aside rise" style={{ '--i': 3 } as CSSProperties}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'rgba(var(--ink), 0.74)' }}>The popup in your browser</span>
        {/* A picture of the popup: nothing in it can be focused or changed here. */}
        <div className="popup-card" inert>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <div className="logo-tile" style={{ width: 36, height: 36, borderRadius: 12 }}>
              <LogoMark size={22} />
            </div>
            <span style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
              <span style={{ fontWeight: 500 }}>{BRAND.name}</span>
              <span style={{ fontSize: 12, color: setup.connected ? 'var(--text)' : 'var(--text-2)' }}>
                {setup.connected ? 'Linked to the app' : 'Not linked yet'}
              </span>
            </span>
          </div>
          <div className="popup-card__room">
            {setup.popup ? (
              <>
                <span style={{ fontSize: 12, color: 'rgba(var(--ink), 0.72)' }}>
                  Room {setup.popup.roomCode} · {setup.popup.people}
                </span>
                <span style={{ fontWeight: 500, fontSize: 15 }}>{setup.popup.title}</span>
                <span style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: setup.popup.inSync ? 'var(--text)' : 'var(--warn)' }}>
                  <span style={{ width: 7, height: 7, borderRadius: '50%', background: setup.popup.inSync ? '#34D399' : '#FBBF24' }} />
                  {setup.popup.inSync ? 'In sync' : 'Catching up'} · {setup.popup.position}
                </span>
              </>
            ) : (
              <>
                <span style={{ fontSize: 12, color: 'rgba(var(--ink), 0.72)' }}>Not in a room</span>
                <span style={{ fontWeight: 500, fontSize: 15 }}>Join a room in the app</span>
                <span style={{ fontSize: 13, color: 'var(--text-2)' }}>The popup shows it here</span>
              </>
            )}
          </div>
          <div style={{ display: 'flex', gap: 8 }}>
            <button type="button" className="popup-btn popup-btn--primary">
              Pause for all
            </button>
            <button type="button" className="popup-btn">
              Open app
            </button>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {(
              [
                ['Wait if someone buffers', true],
                ['Same subtitles for all', true],
                ['Follow host to next episode', false]
              ] as const
            ).map(([label, on]) => (
              <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                <span style={{ flex: 1, color: 'rgba(var(--ink), 0.86)' }}>{label}</span>
                <Switch checked={on} onChange={() => undefined} label={label} />
              </div>
            ))}
          </div>
        </div>
      </aside>
    </div>
  )
}
