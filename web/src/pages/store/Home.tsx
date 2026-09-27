import { useMemo, useState } from 'react'
import { Link } from 'react-router'
import { useQuery } from '@tanstack/react-query'
import { ArrowRight, CalendarDays, MapPin, Search } from 'lucide-react'
import { get } from '../../api'
import type { TicketEvent } from '../../types'
import { formatMinor, formatEventDate } from '../../format'
import { Poster } from '../../components/ui/Poster'
import { Badge } from '../../components/ui/Badge'
import { Input } from '../../components/ui/Field'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

function seatsBadge(e: TicketEvent) {
  if (e.seatsFree === 0) return <Badge tone="bad">Sold out</Badge>
  if (e.seatsFree / e.seatsTotal < 0.2) return <Badge tone="flare">{e.seatsFree} left</Badge>
  return null
}

function EventCard({ event }: { event: TicketEvent }) {
  return (
    <Link to={`/events/${event.id}`} className="group block space-y-3">
      <Poster
        slug={event.slug}
        title={event.title}
        category={event.category}
        dateLabel={formatEventDate(event.startsAt)}
        className="aspect-[4/3] transition-transform duration-200 ease-out group-hover:-translate-y-1"
      />
      <div className="space-y-1 px-0.5">
        <div className="flex items-start justify-between gap-2">
          <div className="text-sm text-fg">{event.subtitle}</div>
          {seatsBadge(event)}
        </div>
        <div className="flex items-baseline justify-between gap-2 text-xs text-muted">
          <span>
            {event.venue} · {event.city}
          </span>
          <span className="shrink-0 text-sm font-medium text-fg">from {formatMinor(event.priceMinor, event.currency)}</span>
        </div>
      </div>
    </Link>
  )
}

export function Home() {
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState<string | null>(null)

  // public read: no token, so a stale one left in storage can't turn it into a 401
  const events = useQuery({ queryKey: ['events'], queryFn: () => get<TicketEvent[]>('/api/v1/events', undefined) })

  const categories = useMemo(
    () => [...new Set((events.data ?? []).map((e) => e.category).filter((c): c is string => !!c))],
    [events.data],
  )

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase()
    return (events.data ?? []).filter(
      (e) => (!category || e.category === category) && (!q || [e.title, e.venue, e.city].some((s) => s?.toLowerCase().includes(q))),
    )
  }, [events.data, search, category])

  // soonest event among the biggest few, so the hero doesn't spotlight something that just sold out
  const featured = useMemo(() => {
    const soonest = [...(events.data ?? [])].sort((a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt)).slice(0, 5)
    return soonest.sort((a, b) => b.seatsFree - a.seatsFree)[0]
  }, [events.data])
  const grid = filtered.filter((e) => e.id !== featured?.id)

  return (
    <div className="space-y-10">
      <section className="space-y-4 py-6 text-center sm:text-left">
        <h1 className="font-display text-4xl font-extrabold tracking-tight text-fg sm:text-5xl">Tickets, straight from the ledger.</h1>
        <p className="text-sm text-muted sm:text-base">Browse what's on, hold your seats, pay from your wallet.</p>
        <div className="mx-auto flex max-w-md items-center gap-2 sm:mx-0">
          <div className="relative flex-1">
            <Search size={14} className="pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted" />
            <Input
              placeholder="Search events, venues, cities…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-8"
              aria-label="Search events"
            />
          </div>
        </div>
        {categories.length > 0 && (
          <div className="flex flex-wrap justify-center gap-2 sm:justify-start">
            <button
              onClick={() => setCategory(null)}
              className={`rounded-full border px-3 py-1 text-xs transition-colors duration-150 ${
                category === null ? 'border-brand-600 bg-brand-600 text-white' : 'border-line text-muted hover:text-fg'
              }`}
            >
              All
            </button>
            {categories.map((c) => (
              <button
                key={c}
                onClick={() => setCategory(c)}
                className={`rounded-full border px-3 py-1 text-xs transition-colors duration-150 ${
                  category === c ? 'border-brand-600 bg-brand-600 text-white' : 'border-line text-muted hover:text-fg'
                }`}
              >
                {c}
              </button>
            ))}
          </div>
        )}
      </section>

      {events.isLoading && <Skeleton rows={4} />}
      {events.isError && <ErrorBox error={events.error} />}
      {events.data && events.data.length === 0 && <Empty>No events on sale right now.</Empty>}

      {featured && (
        <section>
          <Link to={`/events/${featured.id}`} className="group grid items-center gap-6 md:grid-cols-[3fr_2fr]">
            <Poster
              slug={featured.slug}
              title={featured.title}
              category={featured.category}
              dateLabel={formatEventDate(featured.startsAt)}
              className="aspect-[16/10] transition-transform duration-200 ease-out group-hover:-translate-y-1"
            />
            <div className="space-y-4">
              <Badge tone="brand">Featured</Badge>
              <div>
                <div className="font-display text-3xl font-extrabold tracking-tight text-fg sm:text-4xl">{featured.title}</div>
                <div className="mt-1 text-muted">{featured.subtitle}</div>
              </div>
              <dl className="space-y-1.5 text-sm">
                <div className="flex items-center gap-2">
                  <CalendarDays size={15} className="text-muted" aria-hidden />
                  <dt className="sr-only">When</dt>
                  <dd>{formatEventDate(featured.startsAt)}</dd>
                </div>
                <div className="flex items-center gap-2">
                  <MapPin size={15} className="text-muted" aria-hidden />
                  <dt className="sr-only">Where</dt>
                  <dd>
                    {featured.venue}, {featured.city}
                  </dd>
                </div>
              </dl>
              <div className="space-y-1.5">
                <div className="flex justify-between text-xs text-muted">
                  <span>{featured.seatsFree} of {featured.seatsTotal} seats left</span>
                  <span>from {formatMinor(featured.priceMinor, featured.currency)}</span>
                </div>
                <div className="h-1.5 overflow-hidden rounded-full bg-surface-2">
                  <div className="h-full rounded-full bg-brand-500" style={{ width: `${(100 * (featured.seatsTotal - featured.seatsFree)) / featured.seatsTotal}%` }} />
                </div>
              </div>
              <span className="inline-flex items-center gap-1.5 rounded-control bg-brand-600 px-4 py-2.5 text-sm font-medium text-white transition-colors group-hover:bg-brand-500">
                Get tickets <ArrowRight size={15} aria-hidden />
              </span>
            </div>
          </Link>
        </section>
      )}

      {grid.length > 0 && (
        <section className="grid grid-cols-1 gap-6 sm:grid-cols-2 lg:grid-cols-3">
          {grid.map((e) => (
            <EventCard key={e.id} event={e} />
          ))}
        </section>
      )}
    </div>
  )
}
