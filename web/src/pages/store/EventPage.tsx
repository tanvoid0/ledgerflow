import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router'
import { useAuth } from 'react-oidc-context'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarDays, Check, MapPin, Ticket, X } from 'lucide-react'
import { ApiError, get, post } from '../../api'
import { useRoles, useToken } from '../../roles'
import type { AccountBalances, Order, Seat, TicketEvent } from '../../types'
import { formatCountdown, formatEventDate, formatMinor } from '../../format'
import { Poster } from '../../components/ui/Poster'
import { Card } from '../../components/ui/Card'
import { Button } from '../../components/ui/Button'
import { Skeleton, ErrorBox } from '../../components/ui/States'

// the hold-size cap isn't exposed by the API yet; hardcoded until it is
const MAX_SEATS = 6

// the detail endpoint returns the seats themselves, not the list endpoint's counts
type EventDetail = Omit<TicketEvent, 'seatsTotal' | 'seatsFree'> & { seats: Seat[] }
type SeatVisual = 'FREE' | 'SELECTED' | 'HELD' | 'SOLD'

// free seats carry the brand tint so "what can I pick" reads at a glance; taken seats recede
const SEAT_CLASSES: Record<SeatVisual, string> = {
  FREE: 'border-brand-500/60 bg-brand-500/10 hover:bg-brand-500/35 hover:scale-110',
  SELECTED: 'border-brand-600 bg-brand-600 text-white scale-110 shadow-sm shadow-brand-600/40',
  HELD: 'cursor-not-allowed border-line bg-[repeating-linear-gradient(45deg,var(--color-line),var(--color-line)_2px,transparent_2px,transparent_5px)]',
  SOLD: 'cursor-not-allowed border-transparent bg-line/70',
}

function SeatButton({ seat, state, price, onClick }: { seat: Seat; state: SeatVisual; price: string; onClick: () => void }) {
  const disabled = state === 'HELD' || state === 'SOLD'
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      aria-label={`Row ${seat.row} seat ${seat.number}, ${state === 'FREE' ? 'available' : state.toLowerCase()}, ${price}`}
      title={`${seat.seat} · ${state === 'FREE' || state === 'SELECTED' ? price : state.toLowerCase()}`}
      className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-t-lg rounded-b-sm border transition-all duration-150 ease-out ${SEAT_CLASSES[state]}`}
    >
      {state === 'SELECTED' && <Check size={12} strokeWidth={3} />}
    </button>
  )
}

function Legend() {
  const items: [SeatVisual, string][] = [
    ['FREE', `border ${SEAT_CLASSES.FREE}`],
    ['SELECTED', 'border border-brand-600 bg-brand-600'],
    ['HELD', `border ${SEAT_CLASSES.HELD}`],
    ['SOLD', `border ${SEAT_CLASSES.SOLD}`],
  ]
  return (
    <div className="flex flex-wrap justify-center gap-4 text-xs text-muted">
      {items.map(([label, cls]) => (
        <div key={label} className="flex items-center gap-1.5">
          <span className={`h-4 w-4 rounded-t-lg rounded-b-sm ${cls}`} />
          {label === 'FREE' ? 'Available' : label.charAt(0) + label.slice(1).toLowerCase()}
        </div>
      ))}
    </div>
  )
}

export function EventPage() {
  const { id } = useParams<{ id: string }>()
  const auth = useAuth()
  const token = useToken()
  const roles = useRoles()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const storageKey = `ledgerflow:selection:${id}`
  // a lazy initializer, not an effect: this only ever needs to run once, before the first paint
  const [selected, setSelected] = useState<string[]>(() => {
    try {
      const raw = sessionStorage.getItem(storageKey)
      if (raw) {
        sessionStorage.removeItem(storageKey)
        return JSON.parse(raw)
      }
    } catch {
      // sessionStorage blocked - the visitor just re-picks seats
    }
    return []
  })
  const [ownHold, setHold] = useState<Order | null>(null)
  const [conflict, setConflict] = useState(false)
  const [expired, setExpired] = useState(false)
  const [now, setNow] = useState(() => Date.now()) // refreshed every second while held, so the countdown reads live

  const event = useQuery({
    enabled: !!id,
    queryKey: ['event', id],
    queryFn: () => get<EventDetail>(`/api/v1/events/${id}`, undefined), // public read, never a (possibly stale) token
    refetchInterval: 5000,
  })

  // a reload or a trip to My tickets drops component state, but the HELD order is still on the server: pick it back up.
  // Only a fetch from this mount counts - a cached copy can be an order that has since gone to checkout.
  const myOrders = useQuery({
    enabled: auth.isAuthenticated && roles.customer,
    queryKey: ['my-orders'],
    queryFn: () => get<Order[]>('/api/v1/orders', token),
  })
  const restored = myOrders.isFetchedAfterMount
    ? myOrders.data?.find((o) => o.eventId === id && o.status === 'HELD' && Date.parse(o.expiresAt) > now)
    : undefined
  const hold = ownHold ?? restored ?? null

  // no effect needed: a stale pick (someone else took it, seen on the next 5s poll) is filtered out
  // at render time below, never written back into `selected` itself
  const activeSelected = event.data ? selected.filter((code) => event.data.seats.find((x) => x.seat === code)?.status === 'FREE') : selected

  useEffect(() => {
    if (!hold) return
    const timer = setInterval(() => {
      const current = Date.now()
      setNow(current)
      if (Date.parse(hold.expiresAt) - current <= 0) {
        setExpired(true)
        setHold(null)
        setSelected([])
        qc.invalidateQueries({ queryKey: ['event', id] })
      }
    }, 1000)
    return () => clearInterval(timer)
  }, [hold, id, qc])

  const holds = useMutation({
    mutationFn: () => post<Order>(`/api/v1/events/${id}/holds`, token, { seats: activeSelected }),
    onSuccess: ({ data }) => {
      setHold(data)
      setConflict(false)
      setNow(Date.now())
    },
    onError: (err) => {
      setConflict(err instanceof ApiError && err.status === 409)
      qc.invalidateQueries({ queryKey: ['event', id] })
    },
  })

  const checkout = useMutation({
    mutationFn: () => post<Order>(`/api/v1/orders/${hold!.id}/checkout`, token, {}),
    onSuccess: ({ data }) => navigate(`/orders/${data.id}`),
  })

  const balance = useQuery({
    enabled: !!hold && !!roles.accountId,
    queryKey: ['my-balance', roles.accountId],
    queryFn: () => get<AccountBalances>(`/api/v1/balances/${roles.accountId}`, token),
  })
  const main = balance.data?.wallets.find((w) => w.label === 'MAIN')

  function toggleSeat(seat: Seat) {
    if (hold || seat.status !== 'FREE') return
    setExpired(false)
    setConflict(false)
    holds.reset()
    setSelected((s) => (s.includes(seat.seat) ? s.filter((x) => x !== seat.seat) : s.length >= MAX_SEATS ? s : [...s, seat.seat]))
  }

  function holdSeatsClick() {
    if (!auth.isAuthenticated) {
      try {
        sessionStorage.setItem(storageKey, JSON.stringify(activeSelected))
      } catch {
        // no persistence - the redirect still happens, the selection just won't survive it
      }
      auth.signinRedirect({ state: `/events/${id}` })
      return
    }
    holds.mutate()
  }

  if (event.isLoading) return <Skeleton rows={6} />
  if (event.isError) return <ErrorBox error={event.error} />
  if (!event.data) return null
  const e = event.data
  const price = formatMinor(e.priceMinor, e.currency)

  const rows = [...new Set(e.seats.map((s) => s.row))].sort()
  function visualOf(seat: Seat): SeatVisual {
    if (hold && hold.seats.includes(seat.seat)) return 'SELECTED'
    if (!hold && activeSelected.includes(seat.seat)) return 'SELECTED'
    return seat.status
  }

  const remaining = hold ? Date.parse(hold.expiresAt) - now : 0
  const total = hold ? Date.parse(hold.expiresAt) - Date.parse(hold.createdAt) : 1
  const pct = hold ? Math.max(0, Math.min(100, (remaining / total) * 100)) : 0

  const panel = !hold ? (
    <>
      <div className="text-sm font-medium text-fg">Your seats</div>
      {activeSelected.length === 0 && <p className="text-xs text-muted">Pick up to {MAX_SEATS} seats from the map.</p>}
      {activeSelected.length > 0 && (
        <div className="flex flex-wrap gap-1.5">
          {activeSelected.map((seat) => (
            <button
              key={seat}
              onClick={() => setSelected((s) => s.filter((x) => x !== seat))}
              className="flex items-center gap-1 rounded-full bg-surface-2 px-2 py-1 text-xs text-fg"
            >
              {seat} <X size={10} />
            </button>
          ))}
        </div>
      )}
      <div className="flex items-center justify-between text-sm">
        <span className="text-muted">Total</span>
        <span className="font-mono tabular-nums text-fg">{formatMinor(e.priceMinor * activeSelected.length, e.currency)}</span>
      </div>
      {expired && <p className="text-xs text-warn">Your hold expired — seats were released. Pick again.</p>}
      {conflict && <p className="text-xs text-bad">someone just took one of those seats</p>}
      {holds.isError && !conflict && <ErrorBox error={holds.error} />}
      <Button disabled={activeSelected.length === 0 || holds.isPending} loading={holds.isPending} onClick={holdSeatsClick}>
        Hold seats
      </Button>
    </>
  ) : (
    <>
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium text-fg">Seats held</span>
        <span className={`font-mono tabular-nums ${remaining < 60_000 ? 'text-bad' : 'text-fg'}`}>{formatCountdown(remaining)}</span>
      </div>
      <div className="h-1.5 rounded-full bg-surface-2">
        <div
          className={`h-1.5 rounded-full transition-all duration-1000 ease-linear ${remaining < 60_000 ? 'bg-bad' : 'bg-brand-600'}`}
          style={{ width: `${pct}%` }}
        />
      </div>
      <div className="flex flex-wrap gap-1.5">
        {hold.seats.map((s) => (
          <span key={s} className="rounded-full bg-surface-2 px-2 py-1 text-xs text-fg">
            {s}
          </span>
        ))}
      </div>
      <div className="flex items-center justify-between text-sm">
        <span className="text-muted">Amount</span>
        <span className="font-mono tabular-nums text-fg">{formatMinor(hold.amountMinor, hold.currency)}</span>
      </div>
      <div className="text-xs text-muted">Pay from your MAIN wallet · available {main ? formatMinor(main.availableMinor, main.currency) : '…'}</div>
      <Button disabled={checkout.isPending} loading={checkout.isPending} onClick={() => checkout.mutate()}>
        Pay {formatMinor(hold.amountMinor, hold.currency)}
      </Button>
      {checkout.isError && <ErrorBox error={checkout.error} />}
    </>
  )

  return (
    <div className="space-y-6 pb-24 md:pb-0">
      <section className="grid items-center gap-6 md:grid-cols-[2fr_3fr]">
        <Poster slug={e.slug} title={e.title} category={e.category} dateLabel={formatEventDate(e.startsAt)} className="aspect-[4/3]" />
        <div className="space-y-4">
          <div>
            <h1 className="font-display text-3xl font-extrabold tracking-tight text-fg sm:text-5xl">{e.title}</h1>
            <p className="mt-1 text-muted sm:text-lg">{e.subtitle}</p>
          </div>
          <dl className="space-y-1.5 text-sm">
            <div className="flex items-center gap-2">
              <CalendarDays size={15} className="text-muted" aria-hidden />
              <dt className="sr-only">When</dt>
              <dd>{formatEventDate(e.startsAt)}</dd>
            </div>
            <div className="flex items-center gap-2">
              <MapPin size={15} className="text-muted" aria-hidden />
              <dt className="sr-only">Where</dt>
              <dd>
                {e.venue}, {e.city}
              </dd>
            </div>
            <div className="flex items-center gap-2">
              <Ticket size={15} className="text-muted" aria-hidden />
              <dt className="sr-only">Price</dt>
              <dd>
                {price} per seat · {e.seats.filter((s) => s.status === 'FREE').length} of {e.seats.length} left
              </dd>
            </div>
          </dl>
        </div>
      </section>

      <div className="grid gap-6 md:grid-cols-[1fr_320px]">
        <div className="min-w-0 space-y-5 rounded-card border border-line bg-surface p-4 sm:p-8">
          <div className="mx-auto w-2/3 rounded-b-full border-b-4 border-brand-500/50 py-2 text-center text-[10px] uppercase tracking-[0.3em] text-muted">
            stage
          </div>
          <div className="overflow-x-auto">
            <div className="mx-auto flex w-max flex-col gap-1.5">
              {rows.map((row) => {
                const seats = e.seats.filter((s) => s.row === row).sort((a, b) => a.number - b.number)
                const mid = Math.ceil(seats.length / 2)
                return (
                  <div key={row} className="flex items-center gap-2">
                    <span className="w-4 text-center text-xs text-muted">{row}</span>
                    <div className="flex gap-1">
                      {seats.slice(0, mid).map((s) => (
                        <SeatButton key={s.seat} seat={s} state={visualOf(s)} price={price} onClick={() => toggleSeat(s)} />
                      ))}
                    </div>
                    <div className="w-6" aria-hidden />
                    <div className="flex gap-1">
                      {seats.slice(mid).map((s) => (
                        <SeatButton key={s.seat} seat={s} state={visualOf(s)} price={price} onClick={() => toggleSeat(s)} />
                      ))}
                    </div>
                    <span className="w-4 text-center text-xs text-muted">{row}</span>
                  </div>
                )
              })}
            </div>
          </div>
          <Legend />
        </div>

        <Card className="hidden flex-col gap-3 self-start md:sticky md:top-20 md:flex">{panel}</Card>
      </div>

      <div className="fixed inset-x-0 bottom-0 z-20 border-t border-line bg-surface p-3 md:hidden">
        <div className="mx-auto flex max-w-md flex-col gap-2">{panel}</div>
      </div>
    </div>
  )
}
