import { useEffect, useState } from 'react'
import { Moon, Sun } from 'lucide-react'

function readTheme(): 'light' | 'dark' {
  try {
    const stored = localStorage.getItem('theme')
    if (stored === 'light' || stored === 'dark') return stored
  } catch {
    // storage blocked (private mode, policy) - fall back to what index.html's inline script already set
  }
  return document.documentElement.classList.contains('dark') ? 'dark' : 'light'
}

/** index.html's inline script sets the class before paint; this only toggles and persists from here on. */
export function ThemeToggle() {
  const [theme, setTheme] = useState(readTheme)

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark')
    try {
      localStorage.setItem('theme', theme)
    } catch {
      // best effort
    }
  }, [theme])

  return (
    <button
      type="button"
      aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
      onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
      className="rounded-control p-1.5 text-muted transition-colors duration-150 hover:bg-surface-2 hover:text-fg"
    >
      {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
    </button>
  )
}
