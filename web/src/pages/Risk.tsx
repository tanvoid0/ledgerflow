import { Fragment, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useToken } from '../roles'
import { get } from '../api'
import type { RiskCase } from '../types'
import { formatTime } from '../format'
import { StateBadge } from '../components/StateBadge'
import { Loading, Empty, ErrorBox } from '../components/States'

export function Risk() {
  const token = useToken()
  const [open, setOpen] = useState<string | null>(null)

  const cases = useQuery({ queryKey: ['cases'], queryFn: () => get<RiskCase[]>('/api/v1/cases', token) })

  return (
    <div className="space-y-4">
      <h1 className="text-lg font-semibold">Risk cases</h1>

      {cases.isLoading && <Loading />}
      {cases.isError && <ErrorBox error={cases.error} />}
      {cases.data && cases.data.length === 0 && <Empty>No REVIEW or BLOCK cases.</Empty>}
      {cases.data && cases.data.length > 0 && (
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-slate-400">
            <tr>
              <th className="py-1">Payment</th>
              <th>Decision</th>
              <th>Rule</th>
              <th>Score</th>
              <th>Model</th>
              <th>Time</th>
            </tr>
          </thead>
          <tbody>
            {cases.data.map((c) => (
              <Fragment key={c.paymentId}>
                <tr
                  className="cursor-pointer border-t border-slate-100 hover:bg-slate-50 dark:border-slate-800 dark:hover:bg-slate-900"
                  onClick={() => setOpen(open === c.paymentId ? null : c.paymentId)}
                >
                  <td className="py-2 font-mono text-xs">{c.paymentId.slice(0, 8)}</td>
                  <td><StateBadge state={c.decision} /></td>
                  <td className="text-xs">{c.ruleFired ?? '-'}</td>
                  <td className="w-32">
                    <div className="h-2 rounded bg-slate-100 dark:bg-slate-800">
                      <div className="h-2 rounded bg-indigo-500" style={{ width: `${Math.round(c.score * 100)}%` }} />
                    </div>
                  </td>
                  <td className="text-xs">{c.modelVersion}</td>
                  <td className="tabular text-xs">{formatTime(c.decidedAt)}</td>
                </tr>
                {open === c.paymentId && (
                  <tr className="border-t border-slate-100 dark:border-slate-800">
                    <td colSpan={6} className="bg-slate-50 p-3 text-xs dark:bg-slate-900">
                      <div className="mb-2">{c.narrative ?? 'no narrative written yet'}</div>
                      <pre className="overflow-auto whitespace-pre-wrap font-mono">{c.features}</pre>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
