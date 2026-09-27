import { useMemo } from 'react'
import { useParams, useSearchParams, Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useToken } from '../../roles'
import { get } from '../../api'
import { useLedgerStream, recordKey } from '../../stream'
import type { PaymentState, StreamRecord } from '../../types'
import { TERMINAL_STATES } from '../../types'
import { formatMinor, formatTime } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { StateBadge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, ErrorBox } from '../../components/ui/States'

const STEPS = ['RESERVE', 'AUTHORIZE', 'ISSUE'] as const

const STEP_TONE: Record<string, string> = {
  failed: 'border-bad/40 bg-bad/10 text-bad',
  done: 'border-ok/40 bg-ok/10 text-ok',
  active: 'border-warn/40 bg-warn/10 text-fg',
  pending: 'border-line text-muted',
}

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
      <Link to="/admin/payments" className="flex items-center gap-1 text-sm text-muted hover:text-fg">
        <ArrowLeft size={14} /> Payments
      </Link>
      <PageHeader title={<span className="font-mono text-lg">{id}</span>} />

      {payment.isLoading && <Skeleton />}
      {payment.isError && <ErrorBox error={payment.error} />}
      {payment.data && (
        <div className="flex items-center gap-2">
          <StateBadge state={payment.data.state} />
          {'amount' in payment.data && <span className="tabular text-fg">{formatMinor(payment.data.amount.minorUnits, payment.data.amount.currency)}</span>}
          {payment.data.state === 'Failed' && <span className="text-sm text-bad">reason: {payment.data.reason}</span>}
        </div>
      )}

      <div className="flex gap-3">
        {STEPS.map((step) => {
          const captured = payment.data?.state === 'Captured'
          // steps before the one it is on - or the one it failed at - are done
          const reached = currentStep ?? failedAt
          const done = captured || (reached !== null && STEPS.indexOf(step) < STEPS.indexOf(reached))
          const failed = failedAt === step
          const active = currentStep === step
          const tone = failed ? 'failed' : done ? 'done' : active ? 'active' : 'pending'
          return (
            <div key={step} className={`flex-1 rounded-control border p-3 text-center text-xs font-medium ${STEP_TONE[tone]}`}>
              {step}
            </div>
          )
        })}
      </div>

      <div className="space-y-2">
        <div className="text-sm font-medium text-fg">Saga messages ({messages.length})</div>
        <Card className="p-0">
          <Table className="text-xs">
            <THead>
              <tr>
                <TH className="px-3">t+ms</TH>
                <TH>Topic</TH>
                <TH>Event</TH>
                <TH>Time</TH>
              </tr>
            </THead>
            <tbody>
              {messages.map((m) => (
                <TR key={recordKey(m)}>
                  <TD className="tabular px-3">+{Date.parse(m.value.occurredAt) - startedAt}</TD>
                  <TD className="font-mono">{m.topic.replace('ledgerflow.', '')}</TD>
                  <TD>{m.value.eventType}</TD>
                  <TD className="tabular">{formatTime(m.value.occurredAt)}</TD>
                </TR>
              ))}
            </tbody>
          </Table>
        </Card>
      </div>
    </div>
  )
}
