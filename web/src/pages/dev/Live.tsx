import { useEffect, useMemo, useRef, useState } from 'react'
import { ReactFlow, Background, type Node, type Edge } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { Radio } from 'lucide-react'
import { useToken } from '../../roles'
import { useLedgerStream, recordKey } from '../../stream'
import type { StreamRecord } from '../../types'
import { formatTime } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { Card } from '../../components/ui/Card'
import { Input } from '../../components/ui/Field'
import { Empty } from '../../components/ui/States'

// Edges are straight, centre to centre (see .live-map in index.css), so this layout keeps every line clear of every other node.
const NODES: Node[] = [
  { id: 'notification', position: { x: 0, y: 60 }, data: { label: 'notification' } },
  { id: 'ledger', position: { x: 250, y: 60 }, data: { label: 'ledger' } },
  { id: 'risk', position: { x: 500, y: 60 }, data: { label: 'risk' } },
  { id: 'issuer', position: { x: 750, y: 60 }, data: { label: 'issuer' } },
  { id: 'payment', position: { x: 500, y: 200 }, data: { label: 'payment' } },
  { id: 'settlement', position: { x: 750, y: 200 }, data: { label: 'settlement' } },
  { id: 'balance', position: { x: 250, y: 340 }, data: { label: 'balance' } },
  { id: 'account', position: { x: 500, y: 340 }, data: { label: 'account' } },
  { id: 'ticketing', position: { x: 750, y: 340 }, data: { label: 'ticketing' } },
]

// One entry per topic that carries a real message; settlement->account is HTTP, drawn separately, never pulsed.
const TOPIC_EDGES: Record<string, [string, string][]> = {
  'ledgerflow.ledger.hold.commands.v1': [['payment', 'ledger']],
  'ledgerflow.ledger.wallet-hold.events.v1': [
    ['ledger', 'payment'],
    ['ledger', 'notification'],
    ['ledger', 'balance'],
  ],
  'ledgerflow.ledger.hold-rejected.events.v1': [['ledger', 'payment']],
  'ledgerflow.ledger.hold-closed.events.v1': [['ledger', 'balance']],
  'ledgerflow.account.entry.events.v1': [['account', 'balance']],
  'ledgerflow.issuer.authorization.commands.v1': [['payment', 'issuer']],
  'ledgerflow.issuer.authorization.events.v1': [['issuer', 'payment']],
  'ledgerflow.settlement.capture.commands.v1': [['payment', 'settlement']],
  'ledgerflow.settlement.capture.events.v1': [['settlement', 'payment']],
  'ledgerflow.payment.requested.events.v1': [['payment', 'risk']],
  'ledgerflow.payment.outcome.events.v1': [['payment', 'ticketing']],
}

const LINE_COLOR = 'var(--color-line)'
const LIT_COLOR = 'var(--color-brand-500)'

const STATIC_EDGES: Edge[] = Object.entries(TOPIC_EDGES).flatMap(([topic, pairs]) =>
  pairs.map(([source, target]) => ({
    id: `${source}-${target}-${topic}`,
    source,
    target,
    data: { topic: topic.replace('ledgerflow.', '').replace('.v1', '') },
    style: { stroke: LINE_COLOR },
  })),
)

const HTTP_EDGE: Edge = {
  id: 'settlement-account-http',
  source: 'settlement',
  target: 'account',
  label: 'capture (HTTP)',
  style: { stroke: LINE_COLOR, strokeDasharray: '4 4' },
}

const PULSE_MS = 600

/** Both directions between two services draw as one line, so they share one label slot. */
const lineOf = (e: Edge) => [e.source, e.target].sort().join('|')

export function Live() {
  const token = useToken()
  const { records, status } = useLedgerStream(token)
  const [follow, setFollow] = useState('')
  const [selected, setSelected] = useState<StreamRecord | null>(null)
  // edge id -> when a record last crossed it; each edge goes dark PULSE_MS after its own crossing
  const [pulses, setPulses] = useState<Map<string, number>>(new Map())
  const [counts, setCounts] = useState<Record<string, number>>({})
  const last = useRef<StreamRecord | undefined>(undefined)

  // process only records after the last one seen, so the buffer doesn't replay itself; found by identity,
  // not by count, because the buffer's length stops growing at its cap
  useEffect(() => {
    const fresh = records.slice(last.current ? records.lastIndexOf(last.current) + 1 : 0)
    last.current = records.at(-1)
    if (fresh.length === 0) return
    const edgeIds = new Set<string>()
    const nodeCounts: Record<string, number> = {}
    for (const r of fresh) {
      for (const [source, target] of TOPIC_EDGES[r.topic] ?? []) {
        edgeIds.add(`${source}-${target}-${r.topic}`)
        nodeCounts[target] = (nodeCounts[target] ?? 0) + 1
      }
    }
    // deferred a tick: this reacts to the stream buffer (an external store), not to React's own
    // state, so the lint rule against setState-in-effect-body wants it out of the synchronous pass
    setTimeout(() => {
      if (edgeIds.size > 0) {
        const at = performance.now()
        setPulses((p) => new Map([...p, ...[...edgeIds].map((id) => [id, at] as const)]))
        // only this batch's crossings: an edge crossed again since keeps its newer time and stays lit
        setTimeout(() => setPulses((p) => new Map([...p].filter(([, t]) => t !== at))), PULSE_MS)
      }
      setCounts((c) => {
        const next = { ...c }
        for (const [node, n] of Object.entries(nodeCounts)) next[node] = (next[node] ?? 0) + n
        return next
      })
    }, 0)
  }, [records])

  const filtered = follow ? records.filter((r) => r.value.correlationId === follow) : records

  const nodes = useMemo(() => {
    const lit = new Set(STATIC_EDGES.filter((e) => pulses.has(e.id)).map((e) => e.target))
    return NODES.map((n) => ({
        ...n,
        data: { label: `${n.data.label as string}${counts[n.id] ? ` (${counts[n.id]})` : ''}` },
        style: {
          padding: 8,
          borderRadius: 8,
          border: lit.has(n.id) ? `2px solid ${LIT_COLOR}` : `1px solid ${LINE_COLOR}`,
          transition: 'border-color 150ms',
        },
      }))
  }, [counts, pulses])

  const edges = useMemo(() => {
    // a topic name only while a record is crossing, and one per line - the newest crossing's - so labels never stack
    const newest = new Map<string, string>()
    for (const e of STATIC_EDGES) {
      const t = pulses.get(e.id)
      const held = newest.get(lineOf(e))
      if (t !== undefined && (held === undefined || t > pulses.get(held)!)) newest.set(lineOf(e), e.id)
    }
    return [
      HTTP_EDGE,
      ...STATIC_EDGES.map((e) => {
        const lit = pulses.has(e.id)
        return {
          ...e,
          animated: lit,
          label: newest.get(lineOf(e)) === e.id ? (e.data?.topic as string) : undefined,
          style: { ...e.style, stroke: lit ? LIT_COLOR : LINE_COLOR, strokeWidth: lit ? 2.5 : 1 },
        }
      }),
    ]
  }, [pulses])

  return (
    <div className="flex h-full flex-col gap-4">
      <PageHeader
        title="Live map"
        subtitle="every crossing lights its edge for 600ms - a topic, not a request, is the unit here"
        actions={
          <span className="flex items-center gap-1.5 text-xs text-muted">
            <Radio size={12} className={status === 'open' ? 'text-ok' : 'text-muted'} /> stream: {status}
          </span>
        }
      />
      <div className="flex flex-1 gap-4 overflow-hidden">
        <div className="live-map flex-1 rounded-card border border-line bg-surface-2/40">
          <ReactFlow nodes={nodes} edges={edges} defaultEdgeOptions={{ type: 'straight' }} fitView colorMode="system">
            <Background />
          </ReactFlow>
        </div>
        <div className="flex w-96 flex-col gap-2">
          <Input placeholder="follow correlation id…" value={follow} onChange={(e) => setFollow(e.target.value)} className="text-xs" />
          <Card className="flex-1 overflow-auto p-0">
            {filtered.length === 0 && <Empty>nothing on the stream yet</Empty>}
            {filtered
              .slice()
              .reverse()
              .slice(0, 200)
              .map((r) => (
                <button
                  key={recordKey(r)}
                  onClick={() => setSelected(r)}
                  className="block w-full border-b border-line px-2 py-1.5 text-left text-xs last:border-0 hover:bg-surface-2"
                >
                  <div className="flex justify-between">
                    <span className="font-medium text-fg">{r.value.eventType}</span>
                    <span className="tabular text-muted">{formatTime(r.value.occurredAt)}</span>
                  </div>
                  <div className="truncate text-muted">
                    {r.key} · <span className="rounded-full bg-surface-2 px-1.5">{r.value.correlationId?.slice(0, 8) ?? '—'}</span>
                  </div>
                </button>
              ))}
          </Card>
          {selected && (
            <pre className="max-h-64 overflow-auto rounded-card border border-line bg-surface-2 p-2 font-mono text-xs text-fg">
              {JSON.stringify(selected, null, 2)}
            </pre>
          )}
        </div>
      </div>
    </div>
  )
}
