import { initializeApp } from 'firebase/app'
import { indexedDBLocalPersistence, initializeAuth } from 'firebase/auth'
import { getDatabase } from 'firebase/database'
// Firebase Web app "BingeJam Desktop" in the existing project syncit-watchparty (registered
// 2026-11-08). Git-ignored like google-services.json; recreate with `npm run firebase-config`.
import config from '../firebase-config.json'

// Same project, same users and the same Realtime Database as the Android app: a phone and a PC
// with the same account are the same uid.
export const firebaseApp = initializeApp(config)

// Signed in stays signed in across restarts (IndexedDB in the app's own profile folder).
export const auth = initializeAuth(firebaseApp, { persistence: indexedDBLocalPersistence })

export const db = getDatabase(firebaseApp, config.databaseURL)
