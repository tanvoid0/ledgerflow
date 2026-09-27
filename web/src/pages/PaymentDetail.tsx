import { useMemo } from 'react'
import { useParams, useSearchParams, Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useToken } from '../roles'
import { get } from '../api'
import { useLedgerStream, recordKey } from '../stream'
import type { PaymentState, StreamRecord } from '../types'
import { TERMINAL_STATES } from '../types'
import { formatMinor, formatTime } from '../format'
import { StateBadge } from '../components/StateBadge'
import { Loading, ErrorBox } from '../components/States'

const STEPS = ['RESERVE', 'AUTHORIZE', 'ISSUE'] as const

/** True if this record was caused by this payment: same correlationId, or its payload names the payment. */
function belongsTo(r: StreamRecord, paymentId: string, correlationId: string | null): boolean {
  if (correlationId && r.value.correlationId === correlationId) return true
  const payload = r.value.payload as Record<string, unknown> | null
  return payload?.paymentId === paymentId || payload?.reference === paymentId
}

export function PaymentDetail() {
  const { id } = useParams<{ id: string }>()
  const [params] = useSearchParams()
  const correlationId = params.get('correlationId')
  const token = useToken()

  const payment = useQuery({
    queryKey: ['payment', id],
    queryFn: () => get<PaymentState>(`/api/v1/payments/${id}`, token),
    refetchInterval: (q) => (q.state.data && TERMINAL_STATES.includes(q.state.data.state) ? false : 500),
  })

  const { records } = useLedgerStream(token)
  const messages = useMemo(
    () =>
      id
        ? records
            .filter((r) => belongsTo(r, id, correlationId))
            .sort((a, b) => Date.parse(a.value.occurredAt) - Date.parse(b.value.occurredAt))
        : [],
    [records, id, correlationId],
  )
  // server clocks only: the browser's clock is skewed from the pods', and an old payment has no "now" to measure from
  const startedAt = messages.reduce((min, m) => Math.min(min, Date.parse(m.value.occurredAt)), Infinity)

  // `step()` is a derived method on the Java side, not a record component - Jackson doesn't
  // serialise it, so the current step is read back off the `state` discriminant instead.
  const currentStep =
    payment.data?.state === 'Requested'
      ? 'RESERVE'
      : payment.data?.state === 'AuthorizationPending'
        ? 'AUTHORIZE'
        : payment.data?.state === 'CapturePending'
          ? 'ISSUE'
          : null
  const failedAt = payment.data && payment.data.state === 'Failed' ? payment.data.failedAt : null

  return (
    <div className="space-y-6">
      <Link to="/payments" className="text-sm text-indigo-600 hover:underline">
        ← Payments
      </Link>
      <h1 className="font-mono text-lg font-semibold">{id}</h1>

      {payment.isLoading && <Loading />}
      {payment.isError && <ErrorBox error={payment.error} />}
      {payment.data && (
        <div className="space-y-2">
          <StateBadge state={payment.data.state} />
          {'amount' in payment.data && <span className="ml-2 tabular">{formatMinor(payment.data.amount.minorUnits, payment.data.amount.currency)}</span>}
          {payment.data.state === 'Failed' && (
            <div className="text-sm text-red-600 dark:text-red-400">reason: {payment.data.reason}</div>
          )}
        </div>
      )}

      <div className="flex gap-4">
        {STEPS.map((step) => {
          const captured = payment.data?.state === 'Captured'
          // steps before the one it is on - or the one it failed at - are done
          const reached = currentStep ?? failedAt
          const done = captured || (reached !== null && STEPS.indexOf(step) < STEPS.indexOf(reached))
          const failed = failedAt === step
          const active = currentStep === step
          return (
            <div
              key={step}
              className={`flex-1 rounded border p-3 text-center text-xs font-medium ${
                failed
                  ? 'border-red-300 bg-red-50 text-red-700 dark:border-red-800 dark:bg-red-950'
                  : done
                    ? 'border-emerald-300 bg-emerald-50 text-emerald-700 dark:border-emerald-800 dark:bg-emerald-950'
                    : active
                      ? 'border-amber-300 bg-amber-50 text-amber-700 dark:border-amber-800 dark:bg-amber-950'
                      : 'border-slate-200 text-slate-400 dark:border-slate-800'
              }`}
            >
              {step}
            </div>
          )
        })}
      </div>

      <div>
        <div className="mb-2 text-sm font-medium">Saga messages ({messages.length})</div>
        <table className="w-full text-xs">
          <thead className="text-left text-slate-400">
            <tr>
              <th className="py-1">t+ms</th>
              <th>Topic</th>
              <th>Event</th>
              <th>Time</th>
            </tr>
          </thead>
          <tbody>
            {messages.map((m) => (
              <tr key={recordKey(m)} className="border-t border-slate-100 dark:border-slate-800">
                <td className="py-1 tabular">+{Date.parse(m.value.occurredAt) - startedAt}</td>
                <td className="font-mono">{m.topic.replace('ledgerflow.', '')}</td>
                <td>{m.value.eventType}</td>
                <td className="tabular">{formatTime(m.value.occurredAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
