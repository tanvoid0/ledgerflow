import { useMemo } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { useToken } from '../../roles'
import { get } from '../../api'
import type { Order, TicketEvent } from '../../types'
import { formatMinor, formatTime } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { Stat } from '../../components/ui/Stat'
import { Card } from '../../components/ui/Card'
import { StateBadge } from '../../components/ui/Badge'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

const IN_FLIGHT: Order['status'][] = ['HELD', 'PENDING_PAYMENT']
const LOST: Order['status'][] = ['FAILED', 'EXPIRED']

export function Overview() {
  const token = useToken()
  const orders = useQuery({ queryKey: ['orders-all'], queryFn: () => get<Order[]>('/api/v1/orders/all?limit=200', token) })
  const events = useQuery({ queryKey: ['events-admin'], queryFn: () => get<TicketEvent[]>('/api/v1/events', token) })

  const stats = useMemo(() => {
    const os = orders.data ?? []
    const confirmed = os.filter((o) => o.status === 'CONFIRMED')
    const revenue = confirmed.reduce((sum, o) => sum + o.amountMinor, 0)
    const ticketsSold = confirmed.reduce((sum, o) => sum + o.seats.length, 0)
    const inFlight = os.filter((o) => IN_FLIGHT.includes(o.status)).length
    const lost = os.filter((o) => LOST.includes(o.status)).length
    const lostShare = os.length > 0 ? (lost / os.length) * 100 : 0
    return { revenue, ticketsSold, inFlight, lostShare, currency: confirmed[0]?.currency ?? 'GBP' }
  }, [orders.data])

  const sellThrough = useMemo(
    () =>
      (events.data ?? [])
        .map((e) => ({ ...e, sold: e.seatsTotal - e.seatsFree, pct: e.seatsTotal > 0 ? (e.seatsTotal - e.seatsFree) / e.seatsTotal : 0 }))
        .sort((a, b) => b.pct - a.pct),
    [events.data],
  )

  const recent = useMemo(() => [...(orders.data ?? [])].sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)).slice(0, 10), [orders.data])

  return (
    <div className="space-y-6">
      <PageHeader title="Overview" />

      {orders.isError && <ErrorBox error={orders.error} />}
      {events.isError && <ErrorBox error={events.error} />}
      {(orders.isLoading || events.isLoading) && <Skeleton rows={4} />}

      {orders.data && (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
          <Stat label="Confirmed revenue" value={formatMinor(stats.revenue, stats.currency)} delta="last 200 orders" />
          <Stat label="Tickets sold" value={stats.ticketsSold} delta="last 200 orders" />
          <Stat label="Orders in flight" value={stats.inFlight} delta="HELD + PENDING_PAYMENT" />
          <Stat label="Failed / expired" value={`${stats.lostShare.toFixed(1)}%`} delta="share of last 200 orders" />
        </div>
      )}

      {sellThrough.length > 0 && (
        <Card className="space-y-3">
          <div className="text-sm font-medium text-fg">Sell-through by event</div>
          <div className="space-y-2.5">
            {sellThrough.map((e) => (
              <div key={e.id} className="flex items-center gap-3 text-xs">
                <span className="w-32 shrink-0 truncate text-fg">{e.title}</span>
                <div className="relative h-6 flex-1 rounded-full bg-surface-2">
                  <div
                    className="absolute inset-y-0 left-0 flex items-center justify-end rounded-full bg-brand-600 pr-1.5"
                    style={{ width: `${Math.max(4, e.pct * 100)}%` }}
                  >
                    <span className="font-mono text-[10px] tabular-nums text-white">{e.sold}/{e.seatsTotal}</span>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </Card>
      )}
      {events.data && events.data.length === 0 && <Empty>No events yet.</Empty>}

      <div className="space-y-2">
        <div className="text-sm font-medium text-fg">Recent orders</div>
        {orders.data && recent.length === 0 && <Empty>No orders yet.</Empty>}
        {recent.length > 0 && (
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
              {recent.map((o) => (
                <TR key={o.id}>
                  <TD className="tabular">{formatTime(o.createdAt)}</TD>
                  <TD>{o.username}</TD>
                  <TD>{o.eventTitle}</TD>
                  <TD className="tabular">{o.seats.length}</TD>
                  <TD className="tabular">{formatMinor(o.amountMinor, o.currency)}</TD>
                  <TD>
                    {o.paymentId ? (
                      <Link to={`/admin/payments/${o.paymentId}`}>
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
    </div>
  )
}
