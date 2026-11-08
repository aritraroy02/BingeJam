import { resolve } from 'node:path'
import { defineConfig, externalizeDepsPlugin } from 'electron-vite'
import react from '@vitejs/plugin-react'

// Three builds: main (Node, Electron APIs), preload (bridge), renderer (React UI in Chromium).
export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
    resolve: { alias: { '@shared': resolve('src/shared') } },
    build: {
      rolldownOptions: {
        // native-host.js: the browser extension's native messaging host (src/main/extension/host.ts),
        // run by the app's Electron binary as plain Node. Must not import 'electron'.
        input: { index: resolve('src/main/index.ts'), 'native-host': resolve('src/main/extension/host.ts') }
      }
    }
  },
  preload: {
    plugins: [externalizeDepsPlugin()]
  },
  renderer: {
    resolve: {
      alias: {
        '@': resolve('src/renderer/src'),
        '@shared': resolve('src/shared'),
        '@fonts': resolve('../web/fonts')
      }
    },
    plugins: [react()]
  }
})
