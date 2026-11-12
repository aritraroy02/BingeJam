import { useEffect, useState, type FormEvent, type JSX } from 'react'
import { BRAND, LINKS } from '@shared/brand'
import { authErrorMessage, completeEmailLink, extractSignInLink, pendingEmail, sendEmailLink } from '@/lib/auth'
import { Backdrop } from '@/ui/Backdrop'
import { Icon, LogoMark } from '@/ui/Icon'
import { Button } from '@/ui/primitives'

type Step = 'email' | 'sent'

export function SignIn(): JSX.Element {
  const [step, setStep] = useState<Step>(pendingEmail() ? 'sent' : 'email')
  const [email, setEmail] = useState(pendingEmail() ?? '')
  const [link, setLink] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // A bingejam://signin?link=… hand-off from the website completes sign-in by itself.
  useEffect(
    () =>
      window.bingejam.onDeepLink((url) => {
        const found = extractSignInLink(url)
        if (found && email) void complete(found)
      }),
    [email]
  )

  async function send(e: FormEvent): Promise<void> {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await sendEmailLink(email.trim())
      setStep('sent')
    } catch (err) {
      setError(authErrorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  async function complete(found: string): Promise<void> {
    setBusy(true)
    setError(null)
    try {
      await completeEmailLink(found, email)
    } catch (err) {
      setError(authErrorMessage(err))
      setBusy(false)
    }
  }

  function submitLink(e: FormEvent): void {
    e.preventDefault()
    const found = extractSignInLink(link)
    if (!found) {
      setError("That isn't a BingeJam sign-in link. Copy the whole link from the email.")
      return
    }
    void complete(found)
  }

  return (
    <div style={{ position: 'relative', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}>
      <Backdrop preset="home" />
      <div className="drag-strip" />
      <main
        className="glass glass--hero glass--raised rise"
        style={{ position: 'relative', zIndex: 1, width: 'min(520px, 100%)', padding: 40, display: 'flex', flexDirection: 'column', gap: 16 }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div className="logo-tile" style={{ width: 48, height: 48, borderRadius: 12 }}>
            <LogoMark size={30} />
          </div>
          <span className="wordmark" style={{ fontSize: 22 }}>
            {BRAND.name}
          </span>
        </div>
        <h1 className="h1" style={{ fontSize: 34, letterSpacing: -0.4 }}>
          {BRAND.tagline}
        </h1>
        <p className="muted" style={{ margin: 0, fontSize: 15, lineHeight: 1.5 }}>
          Watch and listen together with friends, chat, talk and stream your games. Sign in with the same account as on your
          phone.
        </p>

        {step === 'email' ? (
          <form onSubmit={send} style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 8 }}>
            <label htmlFor="email" className="sr-only">
              Email
            </label>
            <div className="field-wrap">
              <Icon name="mail" size={18} />
              <input
                id="email"
                className="field"
                type="email"
                autoComplete="email"
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                required
                autoFocus
              />
            </div>
            <Button variant="primary" size="lg" type="submit" disabled={busy || !email.trim()}>
              {busy ? 'Sending…' : 'Email me a sign-in link'}
            </Button>
          </form>
        ) : (
          <form onSubmit={submitLink} style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 8 }}>
            <div className="tile" style={{ padding: 16, display: 'flex', flexDirection: 'column', gap: 4 }}>
              <span style={{ fontWeight: 500 }}>Check your inbox</span>
              <span className="muted small" style={{ lineHeight: 1.5 }}>
                We sent a sign-in link to <b className="selectable">{email}</b>. Copy the link from the email (right-click → Copy
                link) and paste it here.
              </span>
            </div>
            <label htmlFor="link" className="sr-only">
              Sign-in link
            </label>
            <div className="field-wrap">
              <Icon name="link" size={18} />
              <input
                id="link"
                className="field"
                type="text"
                placeholder="Paste the link from the email"
                value={link}
                onChange={(e) => setLink(e.target.value)}
                autoFocus
              />
            </div>
            <Button variant="primary" size="lg" type="submit" disabled={busy || !link.trim()}>
              {busy ? 'Signing in…' : 'Sign in'}
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setStep('email')
                setLink('')
                setError(null)
              }}
            >
              Use a different email
            </Button>
          </form>
        )}

        {error ? (
          <p role="alert" style={{ margin: 0, color: 'var(--accent-text)', fontSize: 13 }}>
            {error}
          </p>
        ) : null}

        <p className="subtle" style={{ margin: 0, lineHeight: 1.5 }}>
          By continuing you agree to the{' '}
          <a href={LINKS.terms} target="_blank" rel="noreferrer">
            Terms
          </a>{' '}
          and{' '}
          <a href={LINKS.privacy} target="_blank" rel="noreferrer">
            Privacy Policy
          </a>
          . You must be 18 or older to use {BRAND.name}.
        </p>
      </main>
    </div>
  )
}
