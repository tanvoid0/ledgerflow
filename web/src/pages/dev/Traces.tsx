import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ExternalLink } from 'lucide-react'
import { useToken } from '../../roles'
import { get } from '../../api'
import { PageHeader } from '../../components/ui/PageHeader'
import { Field, Select } from '../../components/ui/Field'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

const THRESHOLDS = [100, 250, 500, 1000] as const

interface TempoTrace {
  traceID: string
  rootServiceName?: string
  rootTraceName?: string
  startTimeUnixNano: string
  durationMs?: number
  // durationMs can be missing on a trace whose root span hasn't arrived yet - then the matched span's
  // own duration (what qualified it for the search) stands in
  spanSet?: { spans?: { durationNanos?: string }[] }
}

function durationOf(t: TempoTrace): number {
  return t.durationMs ?? Math.round(Number(t.spanSet?.spans?.[0]?.durationNanos ?? 0) / 1e6)
}

/** Same deep link shape scripts/scenario-authorize-capture.sh builds by hand, just JSON-encoded instead of typed out. */
function grafanaTraceUrl(traceId: string): string {
  const panes = {
    t: {
      datasource: 'tempo',
      queries: [{ query: traceId, queryType: 'traceql', datasource: { type: 'tempo', uid: 'tempo' }, refId: 'A' }],
      range: { from: 'now-1h', to: 'now' },
    },
  }
  return `http://localhost:3000/explore?schemaVersion=1&panes=${encodeURIComponent(JSON.stringify(panes))}&orgId=1`
}

export function Traces() {
  const token = useToken()
  const [threshold, setThreshold] = useState<(typeof THRESHOLDS)[number]>(250)

  const traces = useQuery({
    queryKey: ['tempo-search', threshold],
    queryFn: () => {
      const end = Math.floor(Date.now() / 1000)
      const q = `{duration>${threshold}ms}`
      return get<{ traces?: TempoTrace[] }>(
        `/api/v1/dev/tempo/search?q=${encodeURIComponent(q)}&limit=20&start=${end - 3600}&end=${end}`,
        token,
      )
    },
    refetchInterval: 15_000,
  })

  const rows = (traces.data?.traces ?? []).map((t) => ({ ...t, ms: durationOf(t) })).sort((a, b) => b.ms - a.ms)
  const slowest = rows[0]?.ms || 1

  return (
    <div className="space-y-4">
      <PageHeader title="Slow traces" subtitle="Tempo search, last hour" />

      <Field label="Threshold">
        <Select className="max-w-40" value={threshold} onChange={(e) => setThreshold(Number(e.target.value) as (typeof THRESHOLDS)[number])}>
          {THRESHOLDS.map((t) => <option key={t} value={t}>{'>'}{t} ms</option>)}
        </Select>
      </Field>

      {traces.isLoading && <Skeleton rows={6} />}
      {traces.isError && <ErrorBox error={traces.error} />}
      {traces.data && rows.length === 0 && <Empty>no traces over {threshold}ms in the last hour</Empty>}
      {rows.length > 0 && (
        <Table>
          <THead>
            <tr>
              <TH>Start</TH>
              <TH>Root service</TH>
              <TH>Root span</TH>
              <TH>Duration</TH>
              <TH></TH>
            </tr>
          </THead>
          <tbody>
            {rows.map((t) => (
              <TR key={t.traceID}>
                <TD className="tabular">{new Date(Number(t.startTimeUnixNano) / 1e6).toLocaleTimeString('en-GB', { hour12: false })}</TD>
                <TD>{t.rootServiceName ?? '-'}</TD>
                <TD className="text-xs text-muted">{t.rootTraceName ?? '-'}</TD>
                <TD>
                  <div className="flex items-center gap-2">
                    <div className="h-1.5 w-20 rounded-full bg-surface-2">
                      <div className="h-1.5 rounded-full bg-flare" style={{ width: `${Math.max(4, (t.ms / slowest) * 100)}%` }} />
                    </div>
                    <span className="tabular text-xs text-fg">{t.ms} ms</span>
                  </div>
                </TD>
                <TD>
                  <a
                    href={grafanaTraceUrl(t.traceID)}
                    target="_blank"
                    rel="noreferrer"
                    className="flex items-center gap-1 text-xs text-brand-600 hover:underline dark:text-brand-400"
                  >
                    trace <ExternalLink size={12} />
                  </a>
                </TD>
              </TR>
            ))}
          </tbody>
        </Table>
      )}
    </div>
  )
}
