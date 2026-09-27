import { Fragment, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useToken } from '../../roles'
import { get } from '../../api'
import type { RiskCase } from '../../types'
import { formatTime } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { StateBadge } from '../../components/ui/Badge'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

export function Risk() {
  const token = useToken()
  const [open, setOpen] = useState<string | null>(null)

  const cases = useQuery({ queryKey: ['cases'], queryFn: () => get<RiskCase[]>('/api/v1/cases', token) })

  return (
    <div className="space-y-4">
      <PageHeader title="Risk cases" />

      {cases.isLoading && <Skeleton />}
      {cases.isError && <ErrorBox error={cases.error} />}
      {cases.data && cases.data.length === 0 && <Empty>No REVIEW or BLOCK cases.</Empty>}
      {cases.data && cases.data.length > 0 && (
        <Table>
          <THead>
            <tr>
              <TH>Payment</TH>
              <TH>Decision</TH>
              <TH>Rule</TH>
              <TH>Score</TH>
              <TH>Model</TH>
              <TH>Time</TH>
            </tr>
          </THead>
          <tbody>
            {cases.data.map((c) => (
              <Fragment key={c.paymentId}>
                <TR className="cursor-pointer" onClick={() => setOpen(open === c.paymentId ? null : c.paymentId)}>
                  <TD className="font-mono text-xs">
                    <button type="button" aria-expanded={open === c.paymentId}>{c.paymentId.slice(0, 8)}</button>
                  </TD>
                  <TD><StateBadge state={c.decision} /></TD>
                  <TD className="text-xs">{c.ruleFired ?? '-'}</TD>
                  <TD className="w-32">
                    <div className="h-2 rounded-full bg-surface-2">
                      <div className="h-2 rounded-full bg-brand-600" style={{ width: `${Math.round(c.score * 100)}%` }} />
                    </div>
                  </TD>
                  <TD className="text-xs">{c.modelVersion}</TD>
                  <TD className="tabular text-xs">{formatTime(c.decidedAt)}</TD>
                </TR>
                {open === c.paymentId && (
                  <tr className="border-t border-line">
                    <td colSpan={6} className="bg-surface-2 p-3 text-xs">
                      <div className="mb-2 text-fg">{c.narrative ?? 'no narrative written yet'}</div>
                      <pre className="overflow-auto whitespace-pre-wrap font-mono text-muted">{c.features}</pre>
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </Table>
      )}
    </div>
  )
}
