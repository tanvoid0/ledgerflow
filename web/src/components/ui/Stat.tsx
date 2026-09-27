import type { ReactNode } from 'react'
import { Card } from './Card'

export function Stat({ label, value, delta }: { label: string; value: ReactNode; delta?: ReactNode }) {
  return (
    <Card className="space-y-1">
      <div className="text-xs text-muted">{label}</div>
      <div className="font-mono text-xl tabular-nums text-fg">{value}</div>
      {delta && <div className="text-xs text-muted">{delta}</div>}
    </Card>
  )
}
