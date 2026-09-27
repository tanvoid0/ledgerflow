import { useCallback, useEffect, useMemo, useState, type KeyboardEvent, type MouseEvent } from 'react'
import { Table, THead, TR, TH, TD } from '../ui/Table'
import { Empty } from '../ui/States'

export interface Series {
  key: string
  points: [number, number][] // [epoch ms, value]; NaN/Infinity values are dropped before drawing
}

const SERIES_COLORS = ['var(--color-series-1)', 'var(--color-series-2)', 'var(--color-series-3)', 'var(--color-series-4)', 'var(--color-series-5)']

/** Clean round tick values (1/2/5 x 10^n), the way every other chart on the page ticks its axis. */
function niceTicks(min: number, max: number, count = 4): number[] {
  if (min === max) return [min]
  const raw = (max - min) / count
  const mag = 10 ** Math.floor(Math.log10(raw))
  const norm = raw / mag
  const step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag
  const start = Math.ceil(min / step) * step
  const ticks: number[] = []
  for (let t = start; t <= max + step * 1e-6; t += step) ticks.push(Math.round(t / step) * step)
  return ticks
}

function formatValue(v: number): string {
  return Math.abs(v) < 10 && v % 1 !== 0 ? v.toFixed(1) : String(Math.round(v))
}

const formatX = (t: number) => new Date(t).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false })
const formatXFull = (t: number) => new Date(t).toLocaleTimeString('en-GB', { hour12: false })

const PAD = { top: 10, right: 12, bottom: 22 }

/**
 * Multi-series time series, inline SVG (no chart lib - see the dataviz method in the lane brief).
 * One y-axis, up to 5 series (caller enforces via topk), fixed colour per series identity (never by
 * rank - see colorOf/knownKeys), crosshair + tooltip with keyboard nav, a Table toggle, and a
 * dimmed-not-skeleton redraw while `loading` (a refetch) is true so the chart never flashes empty on
 * its own refresh.
 */
export function LineChart({
  title,
  subtitle,
  unit,
  series,
  loading,
  height = 220,
  ordinal,
  xFormat,
}: {
  title: string
  subtitle?: string
  unit?: string
  series: Series[]
  loading?: boolean
  height?: number
  /** evenly spaced points instead of a time scale - bench checkpoints are steps, not a clock */
  ordinal?: boolean
  xFormat?: (t: number) => string
}) {
  const [width, setWidth] = useState(560)
  const [table, setTable] = useState(false)
  const [hoverIdx, setHoverIdx] = useState<number | null>(null)
  // first-seen order, not rank: a key keeps its slot even as topk's membership shifts underneath it.
  const [knownKeys, setKnownKeys] = useState<string[]>([])

  // a callback ref, not a mount effect: the plot div comes and goes (empty state, Table toggle), and
  // disconnecting before it unmounts keeps the removal's 0x0 report from zeroing the width
  const wrapRef = useCallback((el: HTMLDivElement | null) => {
    if (!el) return
    const ro = new ResizeObserver(([entry]) => setWidth(entry.contentRect.width))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const clean = useMemo(
    () => series.map((s) => ({ key: s.key, points: s.points.filter(([, v]) => Number.isFinite(v)) })),
    [series],
  )
  const times = useMemo(() => [...new Set(clean.flatMap((s) => s.points.map(([t]) => t)))].sort((a, b) => a - b), [clean])
  const maps = useMemo(() => clean.map((s) => new Map(s.points)), [clean])

  // deferred a tick, same reason as Live.tsx's pulse state: this derives from a prop that changes on
  // its own schedule (a refetch), not from React state, so the lint rule against setState-in-effect
  // wants it out of the synchronous pass
  useEffect(() => {
    const keys = clean.map((s) => s.key)
    setTimeout(() => {
      setKnownKeys((prev) => {
        const missing = keys.filter((k) => !prev.includes(k))
        return missing.length > 0 ? [...prev, ...missing] : prev
      })
    }, 0)
  }, [clean])
  const colorOf = (key: string) => SERIES_COLORS[Math.max(0, knownKeys.indexOf(key)) % SERIES_COLORS.length]

  if (times.length === 0) {
    return (
      <div className="rounded-card border border-line bg-surface p-4">
        <div className="mb-2">
          <div className="font-display text-sm font-semibold text-fg">{title}</div>
          {subtitle && <div className="text-xs text-muted">{subtitle}</div>}
        </div>
        <Empty>no data for {title.toLowerCase()}</Empty>
      </div>
    )
  }

  const values = clean.flatMap((s) => s.points.map(([, v]) => v))
  const yMin = Math.min(0, ...values)
  const yMax = Math.max(...values) * 1.05 || 1
  // counts (lag, messages) never get a "0.2 msgs" tick
  const yTicks = values.every(Number.isInteger) ? niceTicks(yMin, Math.max(yMax, 1)).filter(Number.isInteger) : niceTicks(yMin, yMax)
  // as many decimals as the tick step needs: 0.05 steps must not both print "0.1"
  const tickStep = yTicks.length > 1 ? yTicks[1] - yTicks[0] : 1
  const decimals = Math.max(0, -Math.floor(Math.log10(tickStep)))
  const tickLabel = (t: number) => t.toFixed(decimals) + (unit ? ` ${unit}` : '')
  // wide enough for the longest tick label (~6px a glyph at 10px), so "0.8 msgs" never loses its first digit
  const padLeft = Math.max(36, 10 + 6 * Math.max(...yTicks.map((t) => tickLabel(t).length)))
  const fmtX = xFormat ?? formatX
  const fmtXFull = xFormat ?? formatXFull
  const xMin = times[0]
  const xMax = times[times.length - 1] || xMin + 1
  const plotW = Math.max(10, width - padLeft - PAD.right)
  const plotH = height - PAD.top - PAD.bottom
  const indexOf = new Map(times.map((t, i) => [t, i]))
  const xOf = (t: number) =>
    padLeft + (ordinal ? (indexOf.get(t) ?? 0) / (times.length - 1 || 1) : (t - xMin) / (xMax - xMin || 1)) * plotW
  const yOf = (v: number) => PAD.top + plotH - ((v - yMin) / (yMax - yMin || 1)) * plotH

  const xTickIdx = [...new Set([0, 1, 2, 3, 4].map((i) => Math.round((i * (times.length - 1)) / 4)))]
  const showLegend = clean.length >= 2

  function moveCrosshair(e: MouseEvent<SVGSVGElement>) {
    const rect = e.currentTarget.getBoundingClientRect()
    const x = e.clientX - rect.left
    let nearest = 0
    let best = Infinity
    times.forEach((ti, i) => {
      const d = Math.abs(xOf(ti) - x)
      if (d < best) { best = d; nearest = i }
    })
    setHoverIdx(nearest)
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft') setHoverIdx((i) => Math.max(0, (i ?? times.length) - 1))
    else if (e.key === 'ArrowRight') setHoverIdx((i) => Math.min(times.length - 1, (i ?? -1) + 1))
    else return
    e.preventDefault()
  }

  const hoverT = hoverIdx !== null ? times[hoverIdx] : null
  const tooltipRows =
    hoverT !== null
      ? clean
          .map((s, i) => ({ key: s.key, v: maps[i].get(hoverT), color: colorOf(s.key) }))
          .filter((r): r is { key: string; v: number; color: string } => r.v !== undefined)
          .sort((a, b) => b.v - a.v)
      : []
  const tooltipLeft = hoverT !== null && xOf(hoverT) > width * 0.65

  return (
    <div className="rounded-card border border-line bg-surface p-4">
      <div className="mb-2 flex items-start justify-between gap-2">
        <div>
          <div className="font-display text-sm font-semibold text-fg">{title}</div>
          {subtitle && <div className="text-xs text-muted">{subtitle}</div>}
        </div>
        <button onClick={() => setTable((t) => !t)} className="shrink-0 rounded-control px-2 py-1 text-xs text-muted hover:bg-surface-2 hover:text-fg">
          {table ? 'Chart' : 'Table'}
        </button>
      </div>

      {table ? (
        <div className="max-h-64 overflow-auto">
          <Table>
            <THead>
              <tr>
                <TH>Time</TH>
                {clean.map((s) => (
                  <TH key={s.key} className="text-right tabular">{s.key}</TH>
                ))}
              </tr>
            </THead>
            <tbody>
              {times.slice().reverse().map((t) => (
                <TR key={t}>
                  <TD className="tabular">{fmtXFull(t)}</TD>
                  {maps.map((m, i) => (
                    <TD key={clean[i].key} className="text-right tabular">{m.has(t) ? formatValue(m.get(t)!) : '-'}</TD>
                  ))}
                </TR>
              ))}
            </tbody>
          </Table>
        </div>
      ) : (
        <div ref={wrapRef} className={`transition-opacity duration-150 ${loading ? 'opacity-60' : ''}`}>
          <svg
            width={width}
            height={height}
            tabIndex={0}
            role="img"
            aria-label={title}
            onMouseMove={moveCrosshair}
            onMouseLeave={() => setHoverIdx(null)}
            onKeyDown={onKeyDown}
            className="focus:outline-none"
          >
            {yTicks.map((t) => (
              <g key={t}>
                <line x1={padLeft} x2={width - PAD.right} y1={yOf(t)} y2={yOf(t)} stroke="var(--color-line)" strokeWidth={1} />
                <text x={padLeft - 6} y={yOf(t)} dy={3} textAnchor="end" fontSize={10} className="fill-muted">
                  {tickLabel(t)}
                </text>
              </g>
            ))}
            {xTickIdx.map((i) => (
              <text
                key={i}
                x={xOf(times[i])}
                y={height - 6}
                textAnchor={i === 0 ? 'start' : i === times.length - 1 ? 'end' : 'middle'}
                fontSize={10}
                className="fill-muted"
              >
                {fmtX(times[i])}
              </text>
            ))}
            {clean.map((s) => (
              <path
                key={s.key}
                d={s.points.map(([t, v], i) => `${i === 0 ? 'M' : 'L'}${xOf(t)},${yOf(v)}`).join(' ')}
                fill="none"
                stroke={colorOf(s.key)}
                strokeWidth={2}
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            ))}
            {hoverT !== null && (
              <line x1={xOf(hoverT)} x2={xOf(hoverT)} y1={PAD.top} y2={PAD.top + plotH} stroke="var(--color-muted)" strokeWidth={1} />
            )}
          </svg>
          {hoverT !== null && tooltipRows.length > 0 && (
            <div
              className="pointer-events-none relative -mt-2 w-max max-w-56 rounded-control border border-line bg-surface p-2 text-xs shadow-md"
              style={{ marginLeft: tooltipLeft ? undefined : Math.min(width - 180, xOf(hoverT) + 8), marginRight: tooltipLeft ? width - xOf(hoverT) + 8 : undefined }}
            >
              <div className="mb-1 text-muted">{fmtXFull(hoverT)}</div>
              {tooltipRows.map((r) => (
                <div key={r.key} className="flex items-center gap-1.5">
                  <span className="h-0.5 w-3 shrink-0" style={{ backgroundColor: r.color }} />
                  <span className="font-mono font-semibold tabular-nums text-fg">
                    {formatValue(r.v)}
                    {unit ? ` ${unit}` : ''}
                  </span>
                  <span className="truncate text-muted">{r.key}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* ponytail: no direct end-labels (only legend + tooltip) - legend already names every series unambiguously;
          add end-labels when a ≤4-series chart wants on-canvas identification too */}
      {showLegend && !table && (
        <div className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted">
          {clean.map((s) => (
            <span key={s.key} className="flex items-center gap-1.5">
              <span className="h-0.5 w-3" style={{ backgroundColor: colorOf(s.key) }} />
              {s.key}
            </span>
          ))}
        </div>
      )}
    </div>
  )
}
