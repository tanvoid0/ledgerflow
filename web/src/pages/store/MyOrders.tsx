import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useToken } from '../../roles'
import { get } from '../../api'
import type { Order } from '../../types'
import { formatMinor } from '../../format'
import { StateBadge } from '../../components/ui/Badge'
import { Card } from '../../components/ui/Card'
import { PageHeader } from '../../components/ui/PageHeader'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

export function MyOrders() {
  const token = useToken()
  const orders = useQuery({ queryKey: ['my-orders'], queryFn: () => get<Order[]>('/api/v1/orders', token) })

  const upcoming = (orders.data ?? []).filter((o) => o.status === 'CONFIRMED').sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
  const rest = (orders.data ?? []).filter((o) => o.status !== 'CONFIRMED').sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))

  return (
    <div className="space-y-6">
      <PageHeader title="My tickets" />
      {orders.isLoading && <Skeleton rows={4} />}
      {orders.isError && <ErrorBox error={orders.error} />}
      {orders.data && orders.data.length === 0 && <Empty>No orders yet - go find something on.</Empty>}

      {upcoming.length > 0 && (
        <div className="grid gap-4 sm:grid-cols-2">
          {upcoming.map((o) => (
            <Link key={o.id} to={`/orders/${o.id}`}>
              <Card className="space-y-1 transition-colors duration-150 hover:border-brand-400">
                <div className="font-display text-base font-semibold text-fg">{o.eventTitle}</div>
                <div className="text-xs text-muted">{o.seats.join(', ')}</div>
                <div className="text-sm font-medium text-fg">{formatMinor(o.amountMinor, o.currency)}</div>
              </Card>
            </Link>
          ))}
        </div>
      )}

      {rest.length > 0 && (
        <div className="divide-y divide-line rounded-card border border-line">
          {rest.map((o) => (
            // a HELD order is paid for on its event page, which picks the hold back up
            <Link key={o.id} to={o.status === 'HELD' ? `/events/${o.eventId}` : `/orders/${o.id}`} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm hover:bg-surface-2">
              <span className="text-fg">{o.eventTitle}</span>
              <span className="text-xs text-muted">{o.seats.join(', ')}</span>
              <StateBadge state={o.status} />
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
