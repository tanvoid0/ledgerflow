import { useState } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { useToken } from '../../roles'
import { get } from '../../api'
import type { Order, TicketEvent } from '../../types'
import { formatEventDate, formatMinor } from '../../format'
import { StateBadge } from '../../components/ui/Badge'
import { Poster } from '../../components/ui/Poster'
import { PageHeader } from '../../components/ui/PageHeader'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

function TicketCard({ order, event, dim }: { order: Order; event: TicketEvent | undefined; dim?: boolean }) {
  return (
    <Link to={`/orders/${order.id}`} className={`group block space-y-2 ${dim ? 'opacity-60' : ''}`}>
      <Poster
        slug={event?.slug ?? order.id}
        title={order.eventTitle}
        category={event?.category}
        dateLabel={event && formatEventDate(event.startsAt)}
        className="aspect-[4/3] transition-transform duration-200 ease-out group-hover:-translate-y-1"
      />
      <div className="space-y-0.5 px-0.5">
        <div className="truncate font-display text-sm font-semibold text-fg">{order.eventTitle}</div>
        {event && <div className="text-xs text-muted">{formatEventDate(event.startsAt)}</div>}
        {event && (
          <div className="text-xs text-muted">
            {event.venue} · {event.city}
          </div>
        )}
        <div className="flex items-baseline justify-between gap-2 text-xs text-muted">
          <span>{order.seats.join(', ')}</span>
          <span className="text-sm font-medium text-fg">{formatMinor(order.amountMinor, order.currency)}</span>
        </div>
      </div>
    </Link>
  )
}

export function MyOrders() {
  const token = useToken()
  const orders = useQuery({ queryKey: ['my-orders'], queryFn: () => get<Order[]>('/api/v1/orders', token) })
  // public read, no token - same list Home browses, just for a slug/date/venue lookup by id
  const events = useQuery({ queryKey: ['events'], queryFn: () => get<TicketEvent[]>('/api/v1/events', undefined) })
  const eventsById = new Map((events.data ?? []).map((e) => [e.id, e]))

  // a snapshot, not a live clock - the upcoming/past split doesn't need to move mid-session
  const [now] = useState(() => Date.now())
  const confirmed = (orders.data ?? []).filter((o) => o.status === 'CONFIRMED').map((o) => ({ o, e: eventsById.get(o.eventId) }))
  // no event match yet (still loading, or an id gone from the public list) reads as upcoming rather than vanishing
  const upcoming = confirmed
    .filter(({ e }) => !e || Date.parse(e.startsAt) >= now)
    .sort((a, b) => Date.parse(a.e?.startsAt ?? '') - Date.parse(b.e?.startsAt ?? ''))
  const past = confirmed
    .filter(({ e }) => e && Date.parse(e.startsAt) < now)
    .sort((a, b) => Date.parse(b.e!.startsAt) - Date.parse(a.e!.startsAt))
  const rest = (orders.data ?? []).filter((o) => o.status !== 'CONFIRMED').sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))

  return (
    <div className="space-y-6">
      <PageHeader title="My tickets" />
      {orders.isLoading && <Skeleton rows={4} />}
      {orders.isError && <ErrorBox error={orders.error} />}
      {orders.data && orders.data.length === 0 && (
        <Empty>
          No tickets yet —{' '}
          <Link to="/" className="text-brand-600 hover:underline">
            browse events
          </Link>
          .
        </Empty>
      )}

      {(upcoming.length > 0 || past.length > 0) && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {upcoming.map(({ o, e }) => (
            <TicketCard key={o.id} order={o} event={e} />
          ))}
          {past.map(({ o, e }) => (
            <TicketCard key={o.id} order={o} event={e} dim />
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
              <span className="flex items-center gap-2">
                {o.status === 'HELD' && <span className="text-xs text-brand-600">finish paying</span>}
                <StateBadge state={o.status} />
              </span>
            </Link>
          ))}
        </div>
      )}
    </div>
  )
}
