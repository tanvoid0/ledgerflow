import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useToken } from '../../roles'
import { get, post } from '../../api'
import type { TicketEvent } from '../../types'
import { formatEventDate, formatMinor } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { Card } from '../../components/ui/Card'
import { Poster } from '../../components/ui/Poster'
import { Button, WriteButton } from '../../components/ui/Button'
import { StateBadge } from '../../components/ui/Badge'
import { Field, Input, Select } from '../../components/ui/Field'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

const CATEGORIES = ['Music', 'Comedy', 'Theatre', 'Sport'] as const

const emptyForm = {
  title: '',
  subtitle: '',
  category: CATEGORIES[0] as string,
  venue: '',
  city: '',
  startsAt: '',
  organiser: '',
  price: '',
  rows: '10',
  seatsPerRow: '12',
}

function NewEventForm({ onDone }: { onDone: () => void }) {
  const token = useToken()
  const qc = useQueryClient()
  const [form, setForm] = useState(emptyForm)
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }))

  const create = useMutation({
    mutationFn: () =>
      post('/api/v1/events', token, {
        title: form.title,
        subtitle: form.subtitle,
        category: form.category,
        venue: form.venue,
        city: form.city,
        startsAt: form.startsAt ? new Date(form.startsAt).toISOString() : null,
        organiser: form.organiser,
        priceMinor: Math.round(Number(form.price) * 100),
        currency: 'GBP',
        rows: Number(form.rows),
        seatsPerRow: Number(form.seatsPerRow),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['events-admin'] })
      onDone()
    },
  })

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        create.mutate()
      }}
    >
      <Card className="grid gap-3 sm:grid-cols-2">
        <Field label="Title"><Input required value={form.title} onChange={set('title')} /></Field>
        <Field label="Subtitle"><Input value={form.subtitle} onChange={set('subtitle')} /></Field>
        <Field label="Category">
          <Select value={form.category} onChange={set('category')}>
            {CATEGORIES.map((c) => <option key={c}>{c}</option>)}
          </Select>
        </Field>
        <Field label="Organiser"><Input required value={form.organiser} onChange={set('organiser')} /></Field>
        <Field label="Venue"><Input required value={form.venue} onChange={set('venue')} /></Field>
        <Field label="City"><Input required value={form.city} onChange={set('city')} /></Field>
        <Field label="Starts at"><Input required type="datetime-local" value={form.startsAt} onChange={set('startsAt')} /></Field>
        <Field label="Price (GBP)"><Input required type="number" min="0" step="0.01" value={form.price} onChange={set('price')} /></Field>
        <Field label="Rows"><Input required type="number" min="1" value={form.rows} onChange={set('rows')} /></Field>
        <Field label="Seats per row"><Input required type="number" min="1" value={form.seatsPerRow} onChange={set('seatsPerRow')} /></Field>
        <div className="flex items-center gap-2 sm:col-span-2">
          <WriteButton type="submit" loading={create.isPending}>Create event</WriteButton>
          <Button type="button" variant="ghost" onClick={onDone}>Cancel</Button>
        </div>
        {create.isError && <div className="sm:col-span-2"><ErrorBox error={create.error} /></div>}
      </Card>
    </form>
  )
}

function CancelAction({ event }: { event: TicketEvent }) {
  const token = useToken()
  const qc = useQueryClient()
  const [confirming, setConfirming] = useState(false)
  const cancel = useMutation({
    mutationFn: () => post(`/api/v1/events/${event.id}/cancel`, token, {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['events-admin'] })
      setConfirming(false)
    },
  })
  if (event.status !== 'ON_SALE') return null
  if (!confirming) {
    return (
      <Button variant="ghost" size="sm" onClick={() => setConfirming(true)}>
        Cancel
      </Button>
    )
  }
  return (
    <div className="flex items-center gap-1">
      <span className="text-xs text-muted">sure?</span>
      <WriteButton variant="danger" size="sm" loading={cancel.isPending} onClick={() => cancel.mutate()}>
        Confirm
      </WriteButton>
      <Button variant="ghost" size="sm" onClick={() => setConfirming(false)}>Back</Button>
    </div>
  )
}

export function Events() {
  const token = useToken()
  const [showForm, setShowForm] = useState(false)
  const events = useQuery({ queryKey: ['events-admin'], queryFn: () => get<TicketEvent[]>('/api/v1/events', token) })

  return (
    <div className="space-y-6">
      <PageHeader
        title="Events"
        actions={
          <WriteButton onClick={() => setShowForm((s) => !s)}>
            <Plus size={14} /> New event
          </WriteButton>
        }
      />

      {showForm && <NewEventForm onDone={() => setShowForm(false)} />}

      {events.isLoading && <Skeleton rows={5} />}
      {events.isError && <ErrorBox error={events.error} />}
      {events.data && events.data.length === 0 && <Empty>No events yet.</Empty>}
      {events.data && events.data.length > 0 && (
        <Table>
          <THead>
            <tr>
              <TH></TH>
              <TH>Event</TH>
              <TH>Date</TH>
              <TH>Venue</TH>
              <TH>Price</TH>
              <TH>Sold</TH>
              <TH>Status</TH>
              <TH></TH>
            </tr>
          </THead>
          <tbody>
            {events.data.map((e) => (
              <TR key={e.id}>
                <TD><Poster slug={e.slug} title="" className="h-12 w-16" /></TD>
                <TD className="font-medium text-fg">{e.title}</TD>
                <TD className="tabular">{formatEventDate(e.startsAt)}</TD>
                <TD>{e.venue} · {e.city}</TD>
                <TD className="tabular">{formatMinor(e.priceMinor, e.currency)}</TD>
                <TD>
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-16 rounded-full bg-surface-2">
                      <div
                        className="h-1.5 rounded-full bg-brand-600"
                        style={{ width: `${e.seatsTotal > 0 ? ((e.seatsTotal - e.seatsFree) / e.seatsTotal) * 100 : 0}%` }}
                      />
                    </div>
                    <span className="tabular text-xs text-muted">{e.seatsTotal - e.seatsFree}/{e.seatsTotal}</span>
                  </div>
                </TD>
                <TD><StateBadge state={e.status} /></TD>
                <TD><CancelAction event={e} /></TD>
              </TR>
            ))}
          </tbody>
        </Table>
      )}
    </div>
  )
}
