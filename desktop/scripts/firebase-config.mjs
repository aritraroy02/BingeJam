// Writes src/renderer/src/firebase-config.json (git-ignored) from the Firebase Web app
// "BingeJam Desktop" in project syncit-watchparty. Needs the Firebase CLI, logged in as the owner.
import { execSync } from 'node:child_process'
import { writeFileSync } from 'node:fs'

const APP_ID = '1:289263837364:web:26eb0e945f65dc8f8d77e1'
const out = execSync(`firebase apps:sdkconfig WEB ${APP_ID} --project syncit-watchparty`, { encoding: 'utf8' })
const cfg = JSON.parse(out.slice(out.indexOf('{'), out.lastIndexOf('}') + 1))
const keep = ['apiKey', 'authDomain', 'databaseURL', 'projectId', 'storageBucket', 'messagingSenderId', 'appId']
writeFileSync('src/renderer/src/firebase-config.json', JSON.stringify(Object.fromEntries(keep.map((k) => [k, cfg[k]])), null, 2))
console.log('Wrote src/renderer/src/firebase-config.json')
