import { useEffect, useState } from 'react'
import {
  isSignInWithEmailLink,
  onAuthStateChanged,
  sendSignInLinkToEmail,
  signInWithEmailLink,
  signOut as firebaseSignOut,
  type User
} from 'firebase/auth'
import { auth } from './firebase'

// Passwordless email link, the same provider the phone app uses (FirebaseAuthService.kt), so
// an existing account signs in to the same uid. Google sign-in on desktop needs a small page on
// the website (system browser → back to the app) and comes next.

const PENDING_EMAIL_KEY = 'bj.pendingEmail'

// Where the email's link lands in the browser. The page on our website (web.app is an authorized
// domain) hands the link back to the app via bingejam://; until it exists, people paste the link.
const DESKTOP_CONTINUE_URL = 'https://syncit-watchparty.web.app/desktop-signin'

export type AuthStatus = { state: 'loading' } | { state: 'signedOut' } | { state: 'signedIn'; user: User }

export function useAuth(): AuthStatus {
  const [status, setStatus] = useState<AuthStatus>({ state: 'loading' })
  useEffect(
    () =>
      onAuthStateChanged(auth, (user) => {
        // Anonymous users (old v0.2 room clients) can't use any social feature: treat as signed out.
        setStatus(user && !user.isAnonymous ? { state: 'signedIn', user } : { state: 'signedOut' })
      }),
    []
  )
  return status
}

export async function sendEmailLink(email: string): Promise<void> {
  await sendSignInLinkToEmail(auth, email, { url: DESKTOP_CONTINUE_URL, handleCodeInApp: true })
  try {
    localStorage.setItem(PENDING_EMAIL_KEY, email)
  } catch {
    // Storage blocked: the person types the email again when completing.
  }
}

export function pendingEmail(): string | null {
  try {
    return localStorage.getItem(PENDING_EMAIL_KEY)
  } catch {
    return null
  }
}

/** Accepts the raw email link, the page URL it redirects to, or a bingejam://signin?link=… URL. */
export function extractSignInLink(input: string): string | null {
  const text = input.trim()
  if (!text) return null
  try {
    const url = new URL(text)
    if (url.protocol === 'bingejam:') {
      const inner = url.searchParams.get('link')
      return inner && isSignInWithEmailLink(auth, inner) ? inner : null
    }
  } catch {
    return null
  }
  return isSignInWithEmailLink(auth, text) ? text : null
}

export async function completeEmailLink(link: string, email: string): Promise<void> {
  await signInWithEmailLink(auth, email.trim(), link)
  try {
    localStorage.removeItem(PENDING_EMAIL_KEY)
  } catch {
    // ignore
  }
}

export function signOut(): Promise<void> {
  return firebaseSignOut(auth)
}

/** Firebase error codes → short, human messages. */
export function authErrorMessage(error: unknown): string {
  const code = (error as { code?: string })?.code ?? ''
  switch (code) {
    case 'auth/invalid-email':
      return "That email address doesn't look right."
    case 'auth/invalid-action-code':
    case 'auth/expired-action-code':
      return 'This link has expired or was already used. Send a new one.'
    case 'auth/network-request-failed':
      return "Can't reach the internet. Check your connection and try again."
    case 'auth/too-many-requests':
      return 'Too many tries. Wait a minute and try again.'
    case 'auth/user-disabled':
      return 'This account has been disabled.'
    default:
      return 'Something went wrong. Try again.'
  }
}
