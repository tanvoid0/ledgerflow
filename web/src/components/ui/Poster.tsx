// A tiny deterministic string hash - not crypto, just needs to look different per slug and be stable across renders.
function hash(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (Math.imul(h, 31) + s.charCodeAt(i)) | 0
  // murmur3 finaliser: similar slugs otherwise land on the same palette and layout
  h ^= h >>> 16
  h = Math.imul(h, 0x85ebca6b)
  h ^= h >>> 13
  h = Math.imul(h, 0xc2b2ae35)
  h ^= h >>> 16
  return h >>> 0
}

// Curated [paper, shape, accent, ink] sets. Random hues mixed to mud; screen-print pairs don't.
const INKS = [
  ['oklch(0.42 0.19 293)', 'oklch(0.84 0.15 80)', 'oklch(0.68 0.2 20)', 'oklch(0.97 0.02 90)'],
  ['oklch(0.36 0.07 200)', 'oklch(0.74 0.16 35)', 'oklch(0.88 0.1 95)', 'oklch(0.97 0.02 90)'],
  ['oklch(0.2 0.02 264)', 'oklch(0.88 0.2 128)', 'oklch(0.62 0.2 293)', 'oklch(0.97 0.01 128)'],
  ['oklch(0.45 0.2 262)', 'oklch(0.78 0.14 350)', 'oklch(0.9 0.08 220)', 'oklch(0.98 0.01 262)'],
  ['oklch(0.58 0.21 30)', 'oklch(0.93 0.05 85)', 'oklch(0.3 0.08 30)', 'oklch(0.98 0.02 85)'],
  ['oklch(0.34 0.08 155)', 'oklch(0.9 0.12 100)', 'oklch(0.7 0.15 45)', 'oklch(0.97 0.03 100)'],
  ['oklch(0.93 0.03 85)', 'oklch(0.22 0.02 264)', 'oklch(0.6 0.22 28)', 'oklch(0.2 0.02 264)'],
  ['oklch(0.28 0.08 250)', 'oklch(0.82 0.13 210)', 'oklch(0.75 0.17 60)', 'oklch(0.97 0.02 210)'],
]

// one noise tile, rasterised once by the browser and repeated - a live SVG filter per card made scrolling paint slowly
const GRAIN =
  "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='160' height='160'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='0.55'/%3E%3C/svg%3E\")"

function Shapes({ kind, h, paper, shape, accent }: { kind: number; h: number; paper: string; shape: string; accent: string }) {
  const x = 60 + (h % 280)
  const y = 40 + ((h >> 5) % 140)
  switch (kind) {
    case 0: // sunset: a big disc cut by bars
      return (
        <>
          <circle cx={x} cy={y} r={120} fill={shape} />
          {[0, 1, 2, 3, 4].map((i) => (
            <rect key={i} x={0} y={y + 10 + i * 22} width={400} height={6 + i * 3} fill={paper} />
          ))}
          <circle cx={x > 200 ? 60 : 340} cy={150} r={30} fill={accent} />
        </>
      )
    case 1: // rings off one corner
      return (
        <>
          {[170, 130, 90, 50].map((r, i) => (
            <circle key={r} cx={x > 200 ? 380 : 20} cy={30} r={r} fill="none" stroke={i % 2 ? accent : shape} strokeWidth={18} />
          ))}
        </>
      )
    case 2: // diagonal bands
      return (
        <g transform={`rotate(${-25 - (h % 20)} 200 150)`}>
          <rect x={-100} y={60} width={600} height={46} fill={shape} />
          <rect x={-100} y={124} width={600} height={18} fill={accent} />
          <rect x={-100} y={160} width={600} height={46} fill={shape} />
        </g>
      )
    default: // halftone: dots grow toward one corner
      return (
        <>
          {Array.from({ length: 12 * 9 }, (_, i) => {
            const cx = (i % 12) * 34 + 17
            const cy = Math.floor(i / 12) * 34 + 17
            // the bottom band stays quiet for the title
            const r = Math.max(0, 15 * (1 - Math.hypot(cx - x, cy - y) / 330)) * (cy > 190 ? 0.35 : 1)
            return r > 1 ? <circle key={i} cx={cx} cy={cy} r={r} fill={i === 12 * 2 + 5 ? accent : shape} /> : null
          })}
        </>
      )
  }
}

/**
 * No event art exists, so this stands in: a screen-print style poster keyed off the event slug (same slug, same
 * poster). Caller sizes the box (e.g. `aspect-[4/3]`); type scales with it, and below ~10rem wide only the art shows.
 */
export function Poster({
  slug,
  title,
  category,
  dateLabel,
  className,
}: {
  slug: string
  title: string
  category?: string
  dateLabel?: string
  className?: string
}) {
  const h = hash(slug)
  const [paper, shape, accent, ink] = INKS[(h >>> 8) % INKS.length]

  return (
    <div
      className={`@container relative isolate overflow-hidden rounded-card ${className ?? ''}`}
      style={{ background: paper, color: ink }}
    >
      <svg viewBox="0 0 400 300" preserveAspectRatio="xMidYMid slice" className="absolute inset-0 h-full w-full" aria-hidden="true">
        <Shapes kind={(h >>> 3) % 4} h={h} paper={paper} shape={shape} accent={accent} />
      </svg>
      <div className="pointer-events-none absolute inset-0 opacity-25 mix-blend-overlay" style={{ backgroundImage: GRAIN }} />
      <div className="relative flex h-full flex-col justify-between p-[5cqw] @max-[10rem]:hidden">
        <div className="flex items-start justify-between gap-2 text-[max(10px,2.6cqw)] font-medium uppercase tracking-[0.14em]">
          {category && (
            <span className="rounded-full px-2 py-0.5" style={{ background: paper }}>
              {category}
            </span>
          )}
          {dateLabel && (
            <span className="rounded-full px-2 py-0.5 font-mono normal-case tracking-normal" style={{ background: paper }}>
              {dateLabel}
            </span>
          )}
        </div>
        <div className="font-display text-[11cqw] leading-[0.88] font-extrabold tracking-[-0.03em] text-balance [text-shadow:0_2px_18px_rgb(0_0_0/0.25)]">
          {title}
        </div>
      </div>
    </div>
  )
}
