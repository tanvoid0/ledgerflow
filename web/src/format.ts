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
