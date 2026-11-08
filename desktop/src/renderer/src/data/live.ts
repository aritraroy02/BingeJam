import { useEffect, useMemo, useRef, useState } from 'react'
import { onAuthStateChanged } from 'firebase/auth'
import { onValue, query, ref, type DataSnapshot, type QueryConstraint } from 'firebase/database'
import { auth, db } from '@/lib/firebase'
import type { Children } from './parse.ts'
import { parsePresence, parseProfile, type Presence, type Profile } from './parse.ts'

// Live Realtime Database reads for React: the desktop's version of Android's snapshots() flows.
// A listener the rules refuse (e.g. presence of someone who isn't our friend) reads as "absent",
// like Android's cancelled listener emitting null, with `denied` set for callers that care.

export interface Snap {
  exists: boolean
  val: unknown
  /** Children in query order (val() loses the order of a query). */
  children: Children
  denied: boolean
}

export interface LiveSpec {
  /** Unique per path + query: include anything the constraints depend on (e.g. a startAt value). */
  key: string
  path: string
  constraints?: QueryConstraint[]
}

const DENIED: Snap = { exists: false, val: null, children: [], denied: true }

function toSnap(s: DataSnapshot): Snap {
  const children: [string, unknown][] = []
  s.forEach((c) => {
    children.push([c.key, c.val()])
  })
  return { exists: s.exists(), val: s.val(), children, denied: false }
}

/** Signed-in, non-anonymous uid; undefined while auth is still loading, null when signed out. */
export function useUid(): string | null | undefined {
  const [uid, setUid] = useState<string | null | undefined>(() => currentUid() ?? undefined)
  useEffect(() => onAuthStateChanged(auth, (u) => setUid(u && !u.isAnonymous ? u.uid : null)), [])
  return uid
}

export function currentUid(): string | null {
  const u = auth.currentUser
  return u && !u.isAnonymous ? u.uid : null
}

export function requireUid(): string {
  const uid = currentUid()
  if (!uid) throw new Error('Sign in first')
  return uid
}

/**
 * Keeps one listener per spec and diffs the set as it changes, so adding a friend doesn't
 * re-attach everyone else's listeners. Values are undefined until their first snapshot arrives.
 */
export function useLiveMany(specs: readonly LiveSpec[]): ReadonlyMap<string, Snap> {
  const [values, setValues] = useState<ReadonlyMap<string, Snap>>(() => new Map())
  const subs = useRef(new Map<string, () => void>())
  const latest = useRef(specs)
  latest.current = specs
  const signature = specs.map((s) => s.key).join('\n')

  useEffect(() => {
    const wanted = new Map(latest.current.map((s) => [s.key, s]))
    const removed: string[] = []
    for (const [key, unsubscribe] of subs.current) {
      if (wanted.has(key)) continue
      unsubscribe()
      subs.current.delete(key)
      removed.push(key)
    }
    if (removed.length > 0) {
      setValues((m) => {
        const next = new Map(m)
        for (const key of removed) next.delete(key)
        return next
      })
    }
    for (const [key, spec] of wanted) {
      if (subs.current.has(key)) continue
      const target = spec.constraints?.length ? query(ref(db, spec.path), ...spec.constraints) : ref(db, spec.path)
      const put = (snap: Snap): void => setValues((m) => new Map(m).set(key, snap))
      subs.current.set(key, onValue(target, (s) => put(toSnap(s)), () => put(DENIED)))
    }
  }, [signature])

  useEffect(
    () => () => {
      for (const unsubscribe of subs.current.values()) unsubscribe()
      subs.current.clear()
    },
    []
  )
  return values
}

/** One path (null = nothing to read yet). */
export function useLive(path: string | null, constraints?: QueryConstraint[], key?: string): Snap | undefined {
  const specs = useMemo(() => (path ? [{ key: key ?? path, path, constraints }] : []), [path, key])
  return useLiveMany(specs).get(key ?? path ?? '')
}

/** users/{uid} for each uid: profile, null if missing, absent from the map while loading. */
export function useProfiles(uids: readonly string[]): ReadonlyMap<string, Profile | null> {
  const specs = useMemo(() => [...new Set(uids)].sort().map((uid) => ({ key: uid, path: `users/${uid}` })), [uids.join(',')])
  const snaps = useLiveMany(specs)
  return useMemo(() => {
    const out = new Map<string, Profile | null>()
    for (const [uid, s] of snaps) out.set(uid, parseProfile(uid, s.val))
    return out
  }, [snaps])
}

/** presence/{uid} for each uid (friends only; others read as null), with all devices aggregated. */
export function usePresences(uids: readonly string[]): ReadonlyMap<string, Presence | null> {
  const specs = useMemo(() => [...new Set(uids)].sort().map((uid) => ({ key: uid, path: `presence/${uid}` })), [uids.join(',')])
  const snaps = useLiveMany(specs)
  return useMemo(() => {
    const out = new Map<string, Presence | null>()
    for (const [uid, s] of snaps) out.set(uid, parsePresence(s.val))
    return out
  }, [snaps])
}

/** Firebase errors that mean "the rules said no". */
export function isPermissionDenied(e: unknown): boolean {
  const err = e as { code?: unknown; message?: unknown } | null
  return /permission[_ ]denied/i.test(`${String(err?.code ?? '')} ${String(err?.message ?? '')}`)
}
