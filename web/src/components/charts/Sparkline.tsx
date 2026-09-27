/** A stat tile's inline trend, nothing more: no axes, no legend, no interaction - the tile's own label already names it. */
export function Sparkline({ values, width = 72, height = 24 }: { values: number[]; width?: number; height?: number }) {
  const finite = values.filter(Number.isFinite)
  if (finite.length < 2) return null
  const min = Math.min(...finite)
  const max = Math.max(...finite)
  const span = max - min || 1
  const step = width / (finite.length - 1)
  const d = finite.map((v, i) => `${i === 0 ? 'M' : 'L'}${i * step},${height - ((v - min) / span) * height}`).join(' ')
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="text-muted" aria-hidden="true">
      <path d={d} fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
