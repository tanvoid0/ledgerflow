import { useMemo } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useRoles, useToken } from '../../roles'
import { get } from '../../api'
import { recordKey, useLedgerStream } from '../../stream'
import type { Order, TicketEvent } from '../../types'
import { formatEventDate, formatMinor, formatTime } from '../../format'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Skeleton, ErrorBox } from '../../components/ui/States'

const STEP_LABELS = ['Seats held', 'Payment', 'Confirmed'] as const

// eventType -> plain words; unmapped types just show their own name
const EVENT_WORDS: Record<string, string> = {
  'ledger.FundsHeld': 'Funds reserved in your wallet',
  'ledger.HoldRejected': 'Your wallet could not cover this',
  'ledger.HoldClosed': 'Hold released',
  'issuer.PaymentAuthorized': 'Card issuer approved',
  'issuer.PaymentDeclined': 'Card issuer declined',
  'payment.PaymentCaptured': 'Payment captured',
  'payment.PaymentFailed': 'Payment failed',
  'payment.PaymentRequested': 'Payment started',
  'settlement.CapturesIssued': 'Funds sent to the organiser',
  'settlement.IssueFailed': 'Settlement failed',
}

const FAIL_WORDS: Record<string, string> = {
  PAYMENT_DECLINED: 'Your bank declined the payment',
  WALLETS_UNAVAILABLE: 'Not enough available balance',
  TIMED_OUT: 'The payment timed out',
  ISSUE_FAILED: 'The payment could not be issued',
}

// the one line telling a waiting customer what's happening right now
function statusLine(o: Order): string {
  switch (o.status) {
    case 'HELD':
      return `Seats held until ${formatTime(o.expiresAt)}`
    case 'PENDING_PAYMENT':
      return 'Taking payment from your MAIN wallet…'
    case 'CONFIRMED':
      return 'Payment confirmed'
    case 'FAILED':
      return 'Payment failed'
    case 'EXPIRED':
      return 'Hold expired'
    case 'REFUNDED':
      return 'Refunded'
  }
}

function stepState(status: Order['status'], i: number): 'done' | 'active' | 'failed' | 'pending' {
  if (status === 'REFUNDED') return 'pending' // the order is over, no step is lit
  const terminalFail = status === 'FAILED' || status === 'EXPIRED'
  // PENDING_PAYMENT, FAILED and EXPIRED all sit at the "Payment" step - that's where each of them stalled or broke
  const idx = status === 'HELD' ? 0 : status === 'CONFIRMED' ? 2 : 1
  if (terminalFail) return i < idx ? 'done' : i === idx ? 'failed' : 'pending'
  if (i < idx) return 'done'
  if (i === idx) return status === 'CONFIRMED' ? 'done' : 'active'
  return 'pending'
}

// deterministic, not cryptographic - just needs to look different per seed
function hash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0
  return h >>> 0
}

function Barcode({ seed }: { seed: string }) {
  const bars = Array.from({ length: 28 }, (_, i) => (hash(seed + i) % 3) + 1)
  let x = 0
  const total = bars.reduce((a, w) => a + w + 1, 0)
  return (
    <svg viewBox={`0 0 ${total} 24`} className="h-6 w-full text-fg" preserveAspectRatio="none" aria-hidden="true">
      {bars.map((w, i) => {
        const bar = <rect key={i} x={x} width={w} height={24} fill="currentColor" />
        x += w + 1
        return bar
      })}
    </svg>
  )
}

function TicketStub({ order, event, seat }: { order: Order; event: TicketEvent | undefined; seat: string }) {
  return (
    <div className="relative overflow-visible rounded-card border border-line bg-surface p-4">
      {/* notches: two circles cut from the page background, cheaper and more portable than a mask-image gradient */}
      <span aria-hidden="true" className="absolute left-0 top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full bg-bg" />
      <span aria-hidden="true" className="absolute right-0 top-1/2 h-4 w-4 translate-x-1/2 -translate-y-1/2 rounded-full bg-bg" />
      <div className="flex items-start justify-between gap-4 border-b border-dashed border-line pb-3">
        <div>
          <div className="font-display text-lg font-bold text-fg">{order.eventTitle}</div>
          {event && (
            <div className="text-xs text-muted">
              {event.venue} · {event.city} · {formatEventDate(event.startsAt)}
            </div>
          )}
        </div>
        <div className="text-right">
          <div className="text-xs text-muted">Seat</div>
          <div className="font-mono text-sm font-semibold text-fg">{seat}</div>
        </div>
      </div>
      <div className="flex items-end justify-between gap-4 pt-3">
        <div className="font-mono text-xs text-muted">#{order.id.slice(0, 8)}</div>
        <div className="w-32">
          <Barcode seed={`${order.id}${seat}`} />
          {/* ponytail: decorative only, not a scannable code - swap for a real symbology if scanning is ever needed */}
        </div>
      </div>
    </div>
  )
}

export function OrderPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const token = useToken()
  const roles = useRoles()
  // the gateway only streams to staff; customers follow the order through the step strip's polling
  const staff = roles.admin || roles.dev

  const order = useQuery({
    enabled: !!id,
    queryKey: ['order', id],
    queryFn: () => get<Order>(`/api/v1/orders/${id}`, token),
    refetchInterval: (q) => {
      const s = q.state.data?.status
      return s === 'CONFIRMED' || s === 'FAILED' || s === 'EXPIRED' || s === 'REFUNDED' ? false : 1000
    },
  })

  const event = useQuery({
    enabled: !!order.data,
    queryKey: ['event', order.data?.eventId],
    queryFn: () => get<TicketEvent>(`/api/v1/events/${order.data!.eventId}`, undefined),
  })

  const { records } = useLedgerStream(staff ? token : undefined)
  // no request id until checkout, and a null one would match every uncorrelated backfill record
  const requestId = order.data?.requestId
  const timeline = useMemo(
    () =>
      requestId
        ? records
            .filter((r) => r.value.correlationId === requestId)
            .sort((a, b) => Date.parse(a.value.occurredAt) - Date.parse(b.value.occurredAt))
        : [],
    [records, requestId],
  )

  if (order.isLoading) return <Skeleton rows={5} />
  if (order.isError) return <ErrorBox error={order.error} />
  if (!order.data) return null
  const o = order.data

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      <Link to={`/events/${o.eventId}`} className="text-sm text-brand-600 hover:underline">
        ← Back to event
      </Link>
      <h1 className="font-display text-xl font-semibold text-fg">{o.eventTitle}</h1>

      <div className="flex gap-3">
        {STEP_LABELS.map((label, i) => {
          const state = stepState(o.status, i)
          return (
            <div
              key={label}
              className={`flex-1 rounded-control border p-3 text-center text-xs font-medium transition-colors duration-200 ${
                state === 'failed'
                  ? 'border-bad/40 bg-bad/10 text-bad'
                  : state === 'done'
                    ? 'border-ok/40 bg-ok/10 text-ok'
                    : state === 'active'
                      ? 'border-warn/40 bg-warn/10 text-warn'
                      : 'border-line text-muted'
              }`}
            >
              {label}
            </div>
          )
        })}
      </div>

      <Card className="space-y-2">
        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="font-display text-base font-semibold text-fg">{o.eventTitle}</div>
            {event.data && (
              <div className="text-xs text-muted">
                {formatEventDate(event.data.startsAt)} · {event.data.venue}, {event.data.city}
              </div>
            )}
          </div>
          <div className="shrink-0 text-right">
            <div className="text-xs text-muted">{o.seats.join(', ')}</div>
            <div className="text-sm font-medium text-fg">{formatMinor(o.amountMinor, o.currency)}</div>
          </div>
        </div>
        <div className="flex items-center gap-2 border-t border-line pt-2 text-sm text-fg">
          {o.status === 'PENDING_PAYMENT' && <span aria-hidden="true" className="h-1.5 w-1.5 shrink-0 rounded-full bg-warn animate-pulse" />}
          {statusLine(o)}
        </div>
      </Card>

      {o.status === 'CONFIRMED' && (
        <div className="space-y-3">
          {o.seats.map((seat) => (
            <TicketStub key={seat} order={o} event={event.data} seat={seat} />
          ))}
        </div>
      )}

      {o.status === 'REFUNDED' && (
        <Card className="space-y-1 border-brand-600/40 bg-brand-100/40 dark:bg-brand-700/15">
          <p className="text-sm text-fg">This event was cancelled.</p>
          <p className="text-xs text-muted">Your money has been returned to your wallet.</p>
        </Card>
      )}

      {(o.status === 'FAILED' || o.status === 'EXPIRED') && (
        <Card className="space-y-3">
          <p className="text-sm text-fg">
            {o.status === 'EXPIRED' ? 'Your hold expired before payment finished.' : (o.failReason && FAIL_WORDS[o.failReason]) || 'The payment failed.'}
          </p>
          <p className="text-xs text-muted">Seats released.</p>
          <Button variant="secondary" size="sm" onClick={() => navigate(`/events/${o.eventId}`)}>
            Back to event
          </Button>
        </Card>
      )}

      {staff && requestId && (
        <div>
          <div className="mb-2 text-sm font-medium text-fg">What's happening</div>
          {timeline.length === 0 && <p className="text-xs text-muted">Waiting for the first update…</p>}
          <ul className="space-y-1.5 text-sm">
            {timeline.map((r) => (
              <li key={recordKey(r)} className="flex items-center justify-between gap-2 rounded-control bg-surface-2 px-3 py-1.5">
                <span className="flex items-center gap-1.5 text-fg">
                  <Check size={12} className="text-ok" /> {EVENT_WORDS[r.value.eventType] ?? r.value.eventType}
                </span>
                <span className="tabular text-xs text-muted">{formatTime(r.value.occurredAt)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
