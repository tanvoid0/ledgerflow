import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Bot, Send, Sparkles } from 'lucide-react'
import { useToken } from '../../roles'
import { get, ApiError } from '../../api'
import { askLedger, proposeRefund, fireRefund, type Proposal } from '../../insights'
import type { Order } from '../../types'
import { formatMinor } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { Card } from '../../components/ui/Card'
import { Button, WriteButton } from '../../components/ui/Button'
import { StateBadge } from '../../components/ui/Badge'

// Phase 1 + 4 of the AI roadmap, made visible: ask the ledger a read-only question (streamed), and
// propose a refund the model never executes - a human approves it here, and only then does money move.

export function Assistant() {
  return (
    <div className="space-y-6">
      <PageHeader title="Assistant" subtitle="Ask the ledger, or propose a refund for approval. The model reads and suggests; it never moves money on its own." />
      <AskPanel />
      <ProposePanel />
    </div>
  )
}

/** Phase 1: a read-only question, answered token by token. */
function AskPanel() {
  const token = useToken()
  const [q, setQ] = useState('')
  const [answer, setAnswer] = useState('')
  const [busy, setBusy] = useState(false)

  async function ask() {
    if (!q.trim() || !token) return
    setBusy(true)
    setAnswer('')
    try {
      await askLedger(q.trim(), token, (delta) => setAnswer((a) => a + delta))
    } catch {
      setAnswer('The assistant is unavailable right now.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card className="space-y-3">
      <h2 className="flex items-center gap-2 text-sm font-medium text-fg">
        <Sparkles size={16} className="text-brand-600" /> Ask the ledger
      </h2>
      <div className="flex gap-2">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && ask()}
          placeholder="How many orders are awaiting payment?"
          className="flex-1 rounded-control border border-line bg-surface px-3 py-2 text-sm outline-none focus:border-brand-600"
        />
        <Button onClick={ask} loading={busy} disabled={!q.trim()}>
          <Send size={14} /> Ask
        </Button>
      </div>
      {answer && <p className="whitespace-pre-wrap rounded-control bg-surface-2 p-3 text-sm text-fg">{answer}</p>}
    </Card>
  )
}

/** Phase 4: propose a refund (nothing moves), then approve to fire it or reject to discard. */
function ProposePanel() {
  const token = useToken()
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState(false)
  const [proposal, setProposal] = useState<Proposal | null>(null)
  const seq = useRef(0) // drop a stale propose if a newer one started

  async function propose() {
    if (!q.trim() || !token) return
    const mine = ++seq.current
    setBusy(true)
    setProposal(null)
    try {
      const p = await proposeRefund(q.trim(), token)
      if (mine === seq.current) setProposal(p)
    } catch (e) {
      if (mine === seq.current) {
        const msg = e instanceof ApiError && e.status === 503 ? 'The assistant is unavailable right now.' : 'Could not reach the assistant.'
        setProposal({ action: null, orderId: null, reason: null, decline: msg })
      }
    } finally {
      if (mine === seq.current) setBusy(false)
    }
  }

  return (
    <Card className="space-y-3">
      <h2 className="flex items-center gap-2 text-sm font-medium text-fg">
        <Bot size={16} className="text-brand-600" /> Propose a refund
      </h2>
      <div className="flex gap-2">
        <input
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && propose()}
          placeholder="Refund alice's confirmed order for the cancelled show"
          className="flex-1 rounded-control border border-line bg-surface px-3 py-2 text-sm outline-none focus:border-brand-600"
        />
        <Button onClick={propose} loading={busy} disabled={!q.trim()}>
          Propose
        </Button>
      </div>

      {proposal && proposal.action !== 'refund_order' && (
        <p className="rounded-control bg-surface-2 p-3 text-sm text-muted">{proposal.decline ?? 'No order to refund.'}</p>
      )}
      {proposal?.action === 'refund_order' && proposal.orderId && (
        <RefundProposal orderId={proposal.orderId} reason={proposal.reason} onDone={() => setProposal(null)} />
      )}
    </Card>
  )
}

/** The proposed command, shown against the real order so a human approves what they can see. */
function RefundProposal({ orderId, reason, onDone }: { orderId: string; reason: string | null; onDone: () => void }) {
  const token = useToken()
  const [order, setOrder] = useState<Order | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'firing' | 'fired' | 'error'>('loading')
  const [error, setError] = useState('')

  // load the order once, for context; a 404 means the model named an id that isn't a refundable order
  useEffect(() => {
    if (!token) return
    let live = true
    get<Order>(`/api/v1/orders/${orderId}`, token)
      .then((o) => {
        if (!live) return
        setOrder(o)
        setState('ready')
      })
      .catch(() => live && setState('missing'))
    return () => {
      live = false
    }
  }, [orderId, token])

  async function approve() {
    if (!token) return
    setState('firing')
    try {
      await fireRefund(orderId, token)
      setState('fired')
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'refund failed')
      setState('error')
    }
  }

  const refundable = order?.status === 'CONFIRMED' && !!order.paymentId

  return (
    <div className="space-y-3 rounded-control border border-line bg-surface-2 p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium uppercase tracking-wide text-muted">Proposed: refund order</span>
        <code className="text-xs text-muted">{orderId}</code>
      </div>
      {reason && <p className="text-sm italic text-fg">“{reason}”</p>}

      {state === 'loading' && <p className="text-sm text-muted">Loading the order…</p>}
      {state === 'missing' && <p className="text-sm text-bad">That order was not found — nothing to refund.</p>}
      {order && (
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-fg">
          <span>{order.username}</span>
          <span className="text-muted">{order.eventTitle}</span>
          <span className="tabular">{order.seats.join(', ')}</span>
          <span className="tabular font-medium">{formatMinor(order.amountMinor, order.currency)}</span>
          <StateBadge state={order.status} />
        </div>
      )}
      {order && !refundable && state !== 'fired' && (
        <p className="text-sm text-bad">Only a confirmed, paid order can be refunded; this one is {order.status.toLowerCase()}.</p>
      )}

      {state === 'fired' ? (
        <p className="text-sm text-good">
          Refund fired. The order will move to REFUNDED shortly — see{' '}
          <Link to="/admin/orders" className="underline">
            Orders
          </Link>
          .
        </p>
      ) : (
        <div className="flex gap-2">
          <WriteButton
            variant="danger"
            onClick={approve}
            loading={state === 'firing'}
            disabled={state !== 'ready' || !refundable}
          >
            Approve &amp; refund
          </WriteButton>
          <Button variant="ghost" onClick={onDone} disabled={state === 'firing'}>
            Reject
          </Button>
        </div>
      )}
      {state === 'error' && <p className="text-sm text-bad">{error}</p>}
    </div>
  )
}
