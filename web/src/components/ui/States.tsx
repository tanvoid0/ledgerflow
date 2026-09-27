import type { ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'
import { ApiError } from '../../api'

export function Skeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div className="animate-pulse space-y-2">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="h-10 rounded-control bg-surface-2" />
      ))}
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="p-8 text-center text-sm text-muted">{children}</div>
}

export function ErrorBox({ error }: { error: unknown }) {
  const message =
    error instanceof ApiError && error.status === 0
      ? 'gateway not reachable on 8088'
      : error instanceof Error
        ? error.message
        : String(error)
  return (
    <div className="flex items-center gap-2 rounded-control border border-bad/30 bg-bad/10 p-4 text-sm text-bad">
      <AlertTriangle size={16} />
      <span>{message}</span>
    </div>
  )
}
