import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useToken } from '../../roles'
import { get } from '../../api'
import { PageHeader } from '../../components/ui/PageHeader'
import { Stat } from '../../components/ui/Stat'
import { Field, Select } from '../../components/ui/Field'
import { Sparkline } from '../../components/charts/Sparkline'
import { LineChart, type Series } from '../../components/charts/LineChart'
import { ErrorBox } from '../../components/ui/States'

// PromQL, checked against the live cluster's Prometheus on 2026-09-27. Micrometer/OTLP names timers in milliseconds and
// pushes every 60 s, so rate()'s window is a fixed 4m and no step is shorter than a push (a 30 s step drew a sawtooth).
// %SVC% -> '' (All) or `service_name="x",` (PromQL accepts the trailing comma), filled in by withService() below.
const RATE_WINDOW = '4m'
// probes are most of the raw request count (~50 req/s of /actuator/health on settlement alone) and none of it is load;
// the SSE stream is one request per open tab lasting hours, which would own every mean
const API = `uri!~"/actuator.*|/api/v1/stream"`
// only the gateway publishes an HTTP histogram (gateway application.yml): it sees every API call end to end, so p99 is
// taken there, by route, without the stream for the same reason as above
const EDGE = `service_name="gateway-service", uri=~"/api/.*", uri!="/api/v1/stream"`
const Q_HTTP_P99_TOTAL = `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_milliseconds_bucket{${EDGE}}[${RATE_WINDOW}])))`
const Q_HTTP_P99_BY_ROUTE = `topk(5, histogram_quantile(0.99, sum by (uri, le) (rate(http_server_requests_milliseconds_bucket{${EDGE}}[${RATE_WINDOW}]))))`
// every service has _sum/_count without a histogram, so the per-service view is the mean
const Q_LATENCY_AVG_BY_SERVICE = `topk(5, sum by (service_name) (rate(http_server_requests_milliseconds_sum{%SVC%${API}}[${RATE_WINDOW}])) / sum by (service_name) (rate(http_server_requests_milliseconds_count{%SVC%${API}}[${RATE_WINDOW}])))`
const Q_THROUGHPUT_TOTAL = `sum(rate(http_server_requests_milliseconds_count{%SVC%${API}}[${RATE_WINDOW}]))`
const Q_THROUGHPUT_BY_SERVICE = `topk(5, sum by (service_name) (rate(http_server_requests_milliseconds_count{%SVC%${API}}[${RATE_WINDOW}])))`
// Payments.apply records it with a histogram; absent until payment-service rolls out with it - LineChart's empty state covers that
const Q_SAGA_P50_BY_OUTCOME = `histogram_quantile(0.50, sum by (outcome, le) (rate(ledgerflow_saga_duration_milliseconds_bucket[${RATE_WINDOW}])))`
const Q_SAGA_P99_BY_OUTCOME = `histogram_quantile(0.99, sum by (outcome, le) (rate(ledgerflow_saga_duration_milliseconds_bucket[${RATE_WINDOW}])))`
const Q_SAGA_P99_TOTAL = `histogram_quantile(0.99, sum by (le) (rate(ledgerflow_saga_duration_milliseconds_bucket[${RATE_WINDOW}])))`
// no "consumer group" label on this metric (only client_id, which embeds it) - every consumer group in this
// app is one service's own, so service_name coincides with it; swap in a real group label if that ever diverges
const Q_LAG_BY_GROUP = `topk(5, sum by (service_name) (kafka_consumer_fetch_manager_records_lag{%SVC%}))`
const Q_LAG_TOTAL = `sum(kafka_consumer_fetch_manager_records_lag{%SVC%})`
// DeadLetters counts every record that lands on a DLT; absent until the first one, so `or vector(0)` reads 0, not a dash
const Q_DLT_TOTAL = `sum(increase(ledgerflow_dead_letters_total{%SVC%}[1h])) or vector(0)`

const RANGES = ['15m', '1h', '6h'] as const
type RangeKey = (typeof RANGES)[number]
const RANGE_SECONDS: Record<RangeKey, number> = { '15m': 900, '1h': 3600, '6h': 21600 }
const stepFor = (r: RangeKey) => Math.max(60, Math.round(RANGE_SECONDS[r] / 120))

function withService(query: string, service: string): string {
  return query.replaceAll('%SVC%', service === 'All' ? '' : `service_name="${service}",`)
}

interface PromRangeResponse {
  data: { result: { metric: Record<string, string>; values: [number, string][] }[] }
}

/** One query_range per chart/tile; the tile's headline is just that series' latest point, so nothing fetches twice. */
function useRangeQuery(query: string, range: RangeKey, service: string) {
  const token = useToken()
  const q = withService(query, service)
  return useQuery({
    queryKey: ['prom-range', q, range],
    queryFn: () => {
      const end = Math.floor(Date.now() / 1000)
      const start = end - RANGE_SECONDS[range]
      return get<PromRangeResponse>(`/api/v1/dev/prom/query_range?query=${encodeURIComponent(q)}&start=${start}&end=${end}&step=${stepFor(range)}`, token)
    },
    refetchInterval: 10_000,
    placeholderData: (prev) => prev,
  })
}

function seriesKeyOf(metric: Record<string, string>): string {
  return metric.service_name ?? metric.outcome ?? metric.uri ?? metric.topic ?? 'value'
}

function toSeries(resp: PromRangeResponse | undefined, keyPrefix = ''): Series[] {
  return (resp?.data.result ?? []).map((r) => ({
    key: keyPrefix + seriesKeyOf(r.metric),
    points: r.values.map(([t, v]) => [t * 1000, Number(v)] as [number, number]),
  }))
}

function latestOf(resp: PromRangeResponse | undefined): number[] {
  return (resp?.data.result[0]?.values ?? []).map(([, v]) => Number(v)).filter(Number.isFinite)
}

function formatStat(v: number, unit?: string): string {
  const n = Math.abs(v) < 10 && v % 1 !== 0 ? v.toFixed(1) : String(Math.round(v))
  return unit ? `${n} ${unit}` : n
}

function StatTile({ label, query, unit, range, service }: { label: string; query: string; unit?: string; range: RangeKey; service: string }) {
  const q = useRangeQuery(query, range, service)
  const points = latestOf(q.data)
  const latest = points.at(-1)
  return (
    <Stat
      label={label}
      value={q.isLoading ? '…' : latest !== undefined ? formatStat(latest, unit) : '-'}
      delta={<Sparkline values={points} />}
    />
  )
}

function LineChartQuery({
  title, subtitle, unit, query, range, service,
}: { title: string; subtitle: string; unit?: string; query: string; range: RangeKey; service: string }) {
  const q = useRangeQuery(query, range, service)
  return <LineChart title={title} subtitle={subtitle} unit={unit} series={toSeries(q.data)} loading={q.isFetching && !q.isLoading} />
}

export function Perf() {
  const token = useToken()
  const [range, setRange] = useState<RangeKey>('1h')
  const [service, setService] = useState('All')

  const services = useQuery({
    queryKey: ['prom-services'],
    queryFn: () => get<{ data: string[] }>('/api/v1/dev/prom/label/service_name/values', token),
    staleTime: 5 * 60_000,
  })

  const p50 = useRangeQuery(Q_SAGA_P50_BY_OUTCOME, range, service)
  const p99 = useRangeQuery(Q_SAGA_P99_BY_OUTCOME, range, service)
  const sagaSeries = [...toSeries(p50.data, 'p50 '), ...toSeries(p99.data, 'p99 ')]

  return (
    <div className="space-y-6">
      <PageHeader title="Performance" subtitle="Prometheus through the gateway, refreshed every 10 s" />

      <div className="flex flex-wrap items-end gap-4">
        <Field label="Range">
          <div className="flex gap-1">
            {RANGES.map((r) => (
              <button
                key={r}
                onClick={() => setRange(r)}
                className={`rounded-control border px-3 py-1.5 text-sm transition-colors duration-150 ${
                  range === r ? 'border-brand-600 bg-brand-600 text-white' : 'border-line text-muted hover:text-fg'
                }`}
              >
                {r}
              </button>
            ))}
          </div>
        </Field>
        <Field label="Service">
          <Select className="max-w-56" value={service} onChange={(e) => setService(e.target.value)}>
            <option>All</option>
            {services.data?.data.map((s) => <option key={s}>{s}</option>)}
          </Select>
        </Field>
      </div>
      {services.isError && <ErrorBox error={services.error} />}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <StatTile label="Requests/s" query={Q_THROUGHPUT_TOTAL} unit="req/s" range={range} service={service} />
        <StatTile label="API p99 at the gateway" query={Q_HTTP_P99_TOTAL} unit="ms" range={range} service={service} />
        <StatTile label="Saga p99" query={Q_SAGA_P99_TOTAL} unit="ms" range={range} service={service} />
        <StatTile label="Consumer lag" query={Q_LAG_TOTAL} unit="msgs" range={range} service={service} />
        <StatTile label="Dead-letter records" query={Q_DLT_TOTAL} range={range} service={service} />
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        <LineChartQuery title="API p99 by route" subtitle="at the gateway, top 5 routes, SSE excluded" unit="ms" query={Q_HTTP_P99_BY_ROUTE} range={range} service={service} />
        <LineChartQuery title="Mean latency by service" subtitle="top 5 services, probes excluded" unit="ms" query={Q_LATENCY_AVG_BY_SERVICE} range={range} service={service} />
        <LineChartQuery title="Throughput by service" subtitle="top 5 services, probes excluded" unit="req/s" query={Q_THROUGHPUT_BY_SERVICE} range={range} service={service} />
        <LineChart
          title="Saga duration by outcome"
          subtitle="p50 + p99, top 5 series"
          unit="ms"
          series={sagaSeries}
          loading={(p50.isFetching || p99.isFetching) && !(p50.isLoading || p99.isLoading)}
        />
        <LineChartQuery title="Consumer lag by consumer group" subtitle="top 5 groups" unit="msgs" query={Q_LAG_BY_GROUP} range={range} service={service} />
      </div>
    </div>
  )
}
