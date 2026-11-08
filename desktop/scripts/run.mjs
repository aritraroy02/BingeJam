// Runs electron-vite (dev / preview / build) without ELECTRON_RUN_AS_NODE. VS Code sets that
// variable for its own processes; terminals started from VS Code inherit it, and then Electron
// starts as plain Node ("Cannot read properties of undefined (reading 'requestSingleInstanceLock')").
import { spawn } from 'node:child_process'

const env = { ...process.env }
delete env.ELECTRON_RUN_AS_NODE
const child = spawn('npx', ['electron-vite', ...process.argv.slice(2)], { stdio: 'inherit', env, shell: true })
child.on('exit', (code) => process.exit(code ?? 0))
