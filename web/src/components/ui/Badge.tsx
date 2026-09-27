import type { ReactNode } from 'react'
import { humanize } from '../../format'

type Tone = 'neutral' | 'brand' | 'ok' | 'warn' | 'bad' | 'flare'

const TONES: Record<Tone, string> = {
  neutral: 'bg-surface-2 text-fg',
  brand: 'bg-brand-100 text-brand-700 dark:bg-brand-700/25 dark:text-brand-400',
  ok: 'bg-ok/15 text-ok',
  warn: 'bg-warn/20 text-fg',
  bad: 'bg-bad/15 text-bad',
  flare: 'bg-flare/20 text-fg',
}

export function Badge({ tone = 'neutral', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`inline-flex w-fit items-center rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ${TONES[tone]}`}>{children}</span>
}

// payment/risk/order/seat states share one tone map, so every page's badge reads the same way
const STATE_TONES: Record<string, Tone> = {
  Requested: 'neutral',
  AuthorizationPending: 'warn',
  CapturePending: 'warn',
  Captured: 'ok',
  Failed: 'bad',
  REVIEW: 'warn',
  BLOCK: 'bad',
  FREE: 'ok',
  HELD: 'warn',
  SOLD: 'neutral',
  PENDING_PAYMENT: 'warn',
  CONFIRMED: 'ok',
  FAILED: 'bad',
  EXPIRED: 'neutral',
  ON_SALE: 'ok',
  CANCELLED: 'neutral',
}

export function StateBadge({ state }: { state: string }) {
  return <Badge tone={STATE_TONES[state] ?? 'neutral'}>{humanize(state)}</Badge>
}
