import { useEffect, useState, type FormEvent, type JSX } from 'react'
import { BRAND } from '@shared/brand'
import {
  EARLIEST_YEAR,
  birthDateProblem,
  checkUsername,
  createProfile,
  parseBirthDate,
  setBirthDate,
  todayLocal,
  wireBirthDate,
  UsernameTakenError,
  type UsernameCheck
} from '@/data'
import { Backdrop } from '@/ui/Backdrop'
import { Icon, LogoMark } from '@/ui/Icon'
import { Button } from '@/ui/primitives'

// First run on a new account, the same as the phone's UsernameScreen: pick a unique username and the
// name friends see, plus a date of birth (18+, kept private). An account made on the phone already
// has a username; if it has no date of birth yet (made before 0.7.0) only that is asked.

export interface ProfileSetupProps {
  /** No username yet: ask for username + name too. */
  needProfile: boolean
  /** No date of birth yet. */
  needBirthDate: boolean
  suggestedName: string
  onSignOut: () => void
}

export function ProfileSetup({ needProfile, needBirthDate, suggestedName, onSignOut }: ProfileSetupProps): JSX.Element {
  const [username, setUsername] = useState('')
  const [name, setName] = useState(suggestedName)
  const [dob, setDob] = useState('')
  const [check, setCheck] = useState<UsernameCheck | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const today = todayLocal()

  // Availability as they type (debounced; an exact read of usernames/{key}).
  useEffect(() => {
    if (!needProfile || !username.trim()) {
      setCheck(null)
      return
    }
    let cancelled = false
    const timer = setTimeout(() => {
      checkUsername(username).then(
        (c) => !cancelled && setCheck(c),
        () => !cancelled && setCheck(null)
      )
    }, 350)
    return () => {
      cancelled = true
      clearTimeout(timer)
    }
  }, [username, needProfile])

  const dobProblem = needBirthDate && dob ? birthDateProblem(parseBirthDate(dob), today) : null
  const ready =
    (!needProfile || (check?.state === 'available' && name.trim().length > 0)) && (!needBirthDate || (dob !== '' && dobProblem === null))

  async function submit(e: FormEvent): Promise<void> {
    e.preventDefault()
    if (!ready) return
    setBusy(true)
    setError(null)
    try {
      if (needProfile) await createProfile(username, name)
      if (needBirthDate) await setBirthDate(dob)
      // The live profile / birth date reads switch the app to the main screens by themselves.
    } catch (err) {
      setError(err instanceof UsernameTakenError ? `${err.message}. Try another one.` : "Couldn't save that. Check your connection and try again.")
      if (err instanceof UsernameTakenError) setCheck({ state: 'taken' })
      setBusy(false)
    }
  }

  const usernameHint =
    check === null
      ? 'Letters, numbers, dots and underscores. Friends find you by it.'
      : check.state === 'available'
        ? `@${check.username} is free`
        : check.state === 'taken'
          ? 'That username is taken'
          : check.reason

  return (
    <div style={{ position: 'relative', height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 20 }}>
      <Backdrop preset="home" />
      <div className="drag-strip" />
      <main
        className="glass glass--hero glass--raised rise"
        style={{ position: 'relative', zIndex: 1, width: 'min(520px, 100%)', padding: 40, display: 'flex', flexDirection: 'column', gap: 18 }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div className="logo-tile" style={{ width: 48, height: 48, borderRadius: 15 }}>
            <LogoMark size={30} />
          </div>
          <span className="wordmark" style={{ fontSize: 22 }}>
            {BRAND.name}
          </span>
        </div>
        <h1 className="h1" style={{ fontSize: 30, letterSpacing: -0.8 }}>
          {needProfile ? 'Set up your profile' : 'One more thing'}
        </h1>
        <p className="muted" style={{ margin: 0, fontSize: 15, lineHeight: 1.5 }}>
          {needProfile
            ? 'Pick the username friends add you by and the name they see. Already use BingeJam on your phone? Sign in with the same email instead.'
            : `${BRAND.name} is for people 18 and older. Your date of birth stays private.`}
        </p>

        <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 12, marginTop: 6 }}>
          {needProfile ? (
            <>
              <label htmlFor="username" className="small" style={{ fontWeight: 600 }}>
                Username
              </label>
              <div className="field-wrap">
                <span aria-hidden className="field-prefix">
                  @
                </span>
                <input
                  id="username"
                  className="field"
                  autoComplete="username"
                  spellCheck={false}
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  autoFocus
                  aria-describedby="username-hint"
                />
              </div>
              <span
                id="username-hint"
                className="small"
                style={{ marginTop: -4, color: check && check.state !== 'available' ? '#FECDD3' : undefined, opacity: check ? 1 : 0.7 }}
              >
                {usernameHint}
              </span>

              <label htmlFor="name" className="small" style={{ fontWeight: 600 }}>
                Name
              </label>
              <div className="field-wrap">
                <Icon name="user" size={18} />
                <input id="name" className="field" autoComplete="name" maxLength={40} value={name} onChange={(e) => setName(e.target.value)} />
              </div>
            </>
          ) : null}

          {needBirthDate ? (
            <>
              <label htmlFor="dob" className="small" style={{ fontWeight: 600 }}>
                Date of birth
              </label>
              <div className="field-wrap">
                <Icon name="calendar" size={18} />
                <input
                  id="dob"
                  className="field"
                  type="date"
                  style={{ colorScheme: 'dark' }}
                  min={`${EARLIEST_YEAR}-01-01`}
                  max={wireBirthDate(today)}
                  value={dob}
                  onChange={(e) => setDob(e.target.value)}
                  autoFocus={!needProfile}
                  aria-describedby="dob-hint"
                />
              </div>
              <span id="dob-hint" className="small" style={{ marginTop: -4, color: dobProblem ? '#FECDD3' : undefined, opacity: dobProblem ? 1 : 0.7 }}>
                {dobProblem ?? 'Only you can see it.'}
              </span>
            </>
          ) : null}

          <Button variant="primary" size="lg" type="submit" disabled={busy || !ready}>
            {busy ? 'Saving…' : 'Continue'}
          </Button>
          <Button variant="ghost" onClick={onSignOut}>
            Sign out
          </Button>
        </form>

        {error ? (
          <p role="alert" style={{ margin: 0, color: '#FECDD3', fontSize: 13 }}>
            {error}
          </p>
        ) : null}
      </main>
    </div>
  )
}
