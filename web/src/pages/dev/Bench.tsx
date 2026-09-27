import { useMemo } from 'react'
import { PageHeader } from '../../components/ui/PageHeader'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Empty } from '../../components/ui/States'
import { LineChart } from '../../components/charts/LineChart'

interface TrendStats {
  med: number
  'p(95)': number
  'p(99)': number
}
interface BenchRun {
  meta: { rate: number; duration: string; scenario: 'transfer' | 'holds' | 'payments'; label: string; commit: string; date: string; host: string }
  metrics: {
    http_req_duration: { values: TrendStats }
    http_req_failed: { values: { rate: number } }
    payment_settled?: { values: TrendStats & { captured: number; failed: number } }
  }
}

// perf/history.sh builds the same picture for the repo's README; this is its live equivalent.
// eager+default: vite.config's `fs.allow: ['..']` is what lets this glob reach above web/ at all.
const RUNS = Object.values(
  import.meta.glob('../../../../docs/perf/*.json', { eager: true, import: 'default' }) as Record<string, BenchRun>,
)

const SCENARIOS: { key: BenchRun['meta']['scenario']; title: string }[] = [
  { key: 'transfer', title: 'Transfer · POST /api/v1/transfers' },
  { key: 'holds', title: 'Hold · POST /api/v1/holds' },
  { key: 'payments', title: 'Payment · POST /api/v1/payments, the saga across four services' },
]

function pct(rate: number) {
  return `${(rate * 100).toFixed(2)}%`
}

function ScenarioSection({ scenario, title }: { scenario: BenchRun['meta']['scenario']; title: string }) {
  const runs = useMemo(
    () => RUNS.filter((r) => r.meta.scenario === scenario).sort((a, b) => Date.parse(b.meta.date) - Date.parse(a.meta.date)),
    [scenario],
  )
  // only the step* checkpoints trend across the whole build; one-off experiments (tuned-*, frozen-*) would zig-zag the line
  const steps = useMemo(
    () => runs.filter((r) => r.meta.label.startsWith('step')).slice().reverse(),
    [runs],
  )

  if (runs.length === 0) return null
  return (
    <section className="space-y-3">
      <h2 className="font-display text-lg font-semibold text-fg">{title}</h2>

      {/* ponytail: x-axis ticks show time-of-day only; the hover tooltip's full date disambiguates
          two step checkpoints that land at the same time on different days */}
      <LineChart
        title="p99, ms"
        subtitle={`${steps.length} step checkpoints, oldest to newest`}
        unit="ms"
        series={[{ key: 'p99', points: steps.map((r, i) => [i, r.metrics.http_req_duration.values['p(99)']]) }]}
        ordinal
        xFormat={(i) => steps[i]?.meta.label ?? ''}
      />

      <Table>
        <THead>
          <tr>
            <TH>Date</TH>
            <TH>Commit</TH>
            <TH>Label</TH>
            <TH>Load</TH>
            <TH className="text-right">p50</TH>
            <TH className="text-right">p95</TH>
            <TH className="text-right">p99</TH>
            {scenario === 'payments' && (
              <>
                <TH className="text-right">settled p50</TH>
                <TH className="text-right">settled p99</TH>
              </>
            )}
            <TH className="text-right">failed</TH>
          </tr>
        </THead>
        <tbody>
          {runs.map((r) => (
            <TR key={r.meta.label + r.meta.date}>
              <TD className="tabular">{r.meta.date.slice(0, 10)}</TD>
              <TD className="font-mono text-xs">{r.meta.commit}</TD>
              <TD>{r.meta.label}</TD>
              <TD className="text-xs text-muted">{r.meta.rate}/s for {r.meta.duration}</TD>
              <TD className="text-right tabular">{Math.round(r.metrics.http_req_duration.values.med)}</TD>
              <TD className="text-right tabular">{Math.round(r.metrics.http_req_duration.values['p(95)'])}</TD>
              <TD className="text-right tabular">{Math.round(r.metrics.http_req_duration.values['p(99)'])}</TD>
              {scenario === 'payments' && (
                <>
                  <TD className="text-right tabular">{r.metrics.payment_settled ? Math.round(r.metrics.payment_settled.values.med) : '-'}</TD>
                  <TD className="text-right tabular">{r.metrics.payment_settled ? Math.round(r.metrics.payment_settled.values['p(99)']) : '-'}</TD>
                </>
              )}
              <TD className="text-right tabular">{pct(r.metrics.http_req_failed.values.rate)}</TD>
            </TR>
          ))}
        </tbody>
      </Table>
    </section>
  )
}

export function Bench() {
  return (
    <div className="space-y-8">
      <PageHeader title="Bench history" subtitle="every perf/bench.sh run in docs/perf, newest first" />
      {RUNS.length === 0 && <Empty>no runs under docs/perf</Empty>}
      {SCENARIOS.map((s) => (
        <ScenarioSection key={s.key} scenario={s.key} title={s.title} />
      ))}
    </div>
  )
}
