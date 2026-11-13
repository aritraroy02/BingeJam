import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles/global.css'
import './styles/ui.css'
import './styles/layout.css'
import './styles/chat.css'
import './styles/screens.css'
import { App } from './App'
import { installCrashCatcher } from './data/crashReports'
import { applySavedAppearance } from './live/appearance'

// First, so a crash anywhere after this is reported (App reports, 2026-11-12).
installCrashCatcher()
// Theme and density before the first paint (no dark flash for light-theme users).
applySavedAppearance()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
)
