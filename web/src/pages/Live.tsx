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

export function Live() {
  const token = useToken()
  const { records, status } = useLedgerStream(token)
  const [follow, setFollow] = useState('')
  const [selected, setSelected] = useState<StreamRecord | null>(null)
  const [pulsedEdges, setPulsedEdges] = useState<Set<string>>(new Set())
  const [pulsedNodes, setPulsedNodes] = useState<Set<string>>(new Set())
  const [counts, setCounts] = useState<Record<string, number>>({})
  const last = useRef<StreamRecord | undefined>(undefined)

  // process only records after the last one seen, so the buffer doesn't replay itself; found by identity,
  // not by count, because the buffer's length stops growing at its cap
  useEffect(() => {
    const fresh = records.slice(last.current ? records.lastIndexOf(last.current) + 1 : 0)
    last.current = records.at(-1)
    if (fresh.length === 0) return
    const edgeIds = new Set<string>()
    const nodeIds = new Set<string>()
    const nodeCounts: Record<string, number> = {}
    for (const r of fresh) {
      for (const [source, target] of TOPIC_EDGES[r.topic] ?? []) {
        edgeIds.add(`${source}-${target}-${r.topic}`)
        nodeIds.add(target)
        nodeCounts[target] = (nodeCounts[target] ?? 0) + 1
      }
    }
    // deferred a tick: this reacts to the stream buffer (an external store), not to React's own
    // state, so the lint rule against setState-in-effect-body wants it out of the synchronous pass
    setTimeout(() => {
      if (edgeIds.size > 0) {
        setPulsedEdges(edgeIds)
        setPulsedNodes(nodeIds)
        setTimeout(() => {
          setPulsedEdges(new Set())
          setPulsedNodes(new Set())
        }, PULSE_MS)
      }
      setCounts((c) => {
        const next = { ...c }
        for (const [node, n] of Object.entries(nodeCounts)) next[node] = (next[node] ?? 0) + n
        return next
      })
    }, 0)
  }, [records])

  const filtered = follow ? records.filter((r) => r.value.correlationId === follow) : records

  const nodes = useMemo(
    () =>
      NODES.map((n) => ({
        ...n,
        data: { label: `${n.data.label as string}${counts[n.id] ? ` (${counts[n.id]})` : ''}` },
        style: {
          padding: 8,
          borderRadius: 8,
          border: pulsedNodes.has(n.id) ? '2px solid #6366f1' : '1px solid #cbd5e1',
          transition: 'border-color 150ms',
        },
      })),
    [counts, pulsedNodes],
  )

  const edges = useMemo(
    () => [
      HTTP_EDGE,
      ...STATIC_EDGES.map((e) => ({
        ...e,
        animated: pulsedEdges.has(e.id),
        // a topic name only while a record is crossing: opposite directions share one line, so fixed labels would stack
        label: pulsedEdges.has(e.id) ? (e.data?.topic as string) : undefined,
        style: { ...e.style, stroke: pulsedEdges.has(e.id) ? '#6366f1' : '#cbd5e1', strokeWidth: pulsedEdges.has(e.id) ? 2.5 : 1 },
      })),
    ],
    [pulsedEdges],
  )

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
