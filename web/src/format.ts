import type { Money } from './types'

export function formatMoney(m: Money): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency: m.currency }).format(m.minorUnits / 100)
}

export function formatMinor(minorUnits: number, currency: string): string {
  return new Intl.NumberFormat('en-GB', { style: 'currency', currency }).format(minorUnits / 100)
}

export function formatTime(iso: string): string {
  return new Date(iso).toLocaleTimeString('en-GB', { hour12: false })
}

/** "Sat 18 Oct · 19:30" - the storefront's one date format, event cards through ticket stubs. */
export function formatEventDate(iso: string): string {
  const d = new Date(iso)
  const day = d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
  const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false })
  return `${day} · ${time}`
}

/** mm:ss for a hold's countdown; never negative, so an already-expired hold just reads 0:00. */
export function formatCountdown(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

// PENDING_PAYMENT / AuthorizationPending -> "Pending payment" / "Authorization pending"
export function humanize(state: string): string {
  const words = state.includes('_') || state === state.toUpperCase() ? state.toLowerCase().split('_') : state.split(/(?=[A-Z])/).map((w) => w.toLowerCase())
  const text = words.join(' ')
  return text.charAt(0).toUpperCase() + text.slice(1)
}
