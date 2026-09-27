import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useToken } from '../../roles'
import { get } from '../../api'
import type { Order, OrderStatus } from '../../types'
import { humanize, formatMinor, formatTime } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { StateBadge } from '../../components/ui/Badge'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

const FILTERS: (OrderStatus | 'All')[] = ['All', 'HELD', 'PENDING_PAYMENT', 'CONFIRMED', 'FAILED', 'EXPIRED']

export function Orders() {
  const token = useToken()
  const [status, setStatus] = useState<(typeof FILTERS)[number]>('All')

  const orders = useQuery({
    queryKey: ['orders-all', status],
    queryFn: () => get<Order[]>(`/api/v1/orders/all?limit=200${status === 'All' ? '' : `&status=${status}`}`, token),
    refetchInterval: 5000,
  })

  return (
    <div className="space-y-4">
      <PageHeader title="Orders" />

      <div className="flex flex-wrap gap-2">
        {FILTERS.map((f) => (
          <button
            key={f}
            onClick={() => setStatus(f)}
            className={`rounded-full border px-3 py-1 text-xs transition-colors duration-150 ${
              status === f ? 'border-brand-600 bg-brand-600 text-white' : 'border-line text-muted hover:text-fg'
            }`}
          >
            {f === 'All' ? f : humanize(f)}
          </button>
        ))}
      </div>

      {orders.isLoading && <Skeleton rows={6} />}
      {orders.isError && <ErrorBox error={orders.error} />}
      {orders.data && orders.data.length === 0 && <Empty>No orders match.</Empty>}
      {orders.data && orders.data.length > 0 && (
        <Table>
          <THead>
            <tr>
              <TH>Time</TH>
              <TH>Buyer</TH>
              <TH>Event</TH>
              <TH>Seats</TH>
              <TH>Amount</TH>
              <TH>Status</TH>
            </tr>
          </THead>
          <tbody>
            {orders.data.map((o) => (
              <TR key={o.id}>
                <TD className="tabular">{formatTime(o.createdAt)}</TD>
                <TD>{o.username}</TD>
                <TD>{o.eventTitle}</TD>
                <TD className="tabular">{o.seats.join(', ')}</TD>
                <TD className="tabular">{formatMinor(o.amountMinor, o.currency)}</TD>
                <TD>
                  {o.paymentId ? (
                    <Link to={`/admin/payments/${o.paymentId}`} className="hover:underline">
                      <StateBadge state={o.status} />
                    </Link>
                  ) : (
                    <StateBadge state={o.status} />
                  )}
                </TD>
              </TR>
            ))}
          </tbody>
        </Table>
      )}
    </div>
  )
}
