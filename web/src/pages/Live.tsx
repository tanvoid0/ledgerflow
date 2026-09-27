import { useEffect, useMemo, useRef, useState } from 'react'
import { ReactFlow, Background, type Node, type Edge } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useToken } from '../roles'
import { useLedgerStream, recordKey } from '../stream'
import type { StreamRecord } from '../types'
import { formatTime } from '../format'

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
}

const STATIC_EDGES: Edge[] = Object.entries(TOPIC_EDGES).flatMap(([topic, pairs]) =>
  pairs.map(([source, target]) => ({
    id: `${source}-${target}-${topic}`,
    source,
    target,
    data: { topic: topic.replace('ledgerflow.', '').replace('.v1', '') },
    style: { stroke: '#cbd5e1' },
  })),
)

const HTTP_EDGE: Edge = {
  id: 'settlement-account-http',
  source: 'settlement',
  target: 'account',
  label: 'capture (HTTP)',
  style: { stroke: '#cbd5e1', strokeDasharray: '4 4' },
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
          border: lit.has(n.id) ? '2px solid #6366f1' : '1px solid #cbd5e1',
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
          style: { ...e.style, stroke: lit ? '#6366f1' : '#cbd5e1', strokeWidth: lit ? 2.5 : 1 },
        }
      }),
    ]
  }, [pulses])

  return (
    <div className="flex h-full gap-4">
      <div className="live-map flex-1 rounded border border-slate-200 dark:border-slate-800">
        <ReactFlow nodes={nodes} edges={edges} defaultEdgeOptions={{ type: 'straight' }} fitView colorMode="system">
          <Background />
        </ReactFlow>
      </div>
      <div className="flex w-96 flex-col gap-2">
        <div className="text-xs text-slate-400">stream: {status}</div>
        <input
          className="rounded border border-slate-200 px-2 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
          placeholder="follow correlation id…"
          value={follow}
          onChange={(e) => setFollow(e.target.value)}
        />
        <div className="flex-1 overflow-auto rounded border border-slate-200 dark:border-slate-800">
          {filtered
            .slice()
            .reverse()
            .slice(0, 200)
            .map((r) => (
              <button
                key={recordKey(r)}
                onClick={() => setSelected(r)}
                className="block w-full border-b border-slate-100 px-2 py-1.5 text-left text-xs hover:bg-slate-50 dark:border-slate-800 dark:hover:bg-slate-900"
              >
                <div className="flex justify-between">
                  <span className="font-medium">{r.value.eventType}</span>
                  <span className="tabular text-slate-400">{formatTime(r.value.occurredAt)}</span>
                </div>
                <div className="truncate text-slate-400">
                  {r.key} · <span className="rounded bg-slate-100 px-1 dark:bg-slate-800">{r.value.correlationId?.slice(0, 8) ?? "—"}</span>
                </div>
              </button>
            ))}
        </div>
        {selected && (
          <pre className="max-h-64 overflow-auto rounded border border-slate-200 bg-slate-50 p-2 text-xs dark:border-slate-800 dark:bg-slate-900">
            {JSON.stringify(selected, null, 2)}
          </pre>
        )}
      </div>
    </div>
  )
}
