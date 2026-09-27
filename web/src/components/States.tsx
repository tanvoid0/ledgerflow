import type { ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'
import { ApiError } from '../api'

export function Loading({ rows = 3 }: { rows?: number }) {
  return (
    <div className="space-y-2 animate-pulse">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="h-10 rounded bg-slate-100 dark:bg-slate-800" />
      ))}
    </div>
  )
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="p-8 text-center text-sm text-slate-400">{children}</div>
}

export function ErrorBox({ error }: { error: unknown }) {
  const message =
    error instanceof ApiError
      ? error.status === 0
        ? 'gateway not reachable on 8088'
        : `${error.status} ${error.statusText} on ${error.path}`
      : String(error)
  return (
    <div className="flex items-center gap-2 rounded border border-red-300 bg-red-50 p-4 text-sm text-red-700 dark:border-red-800 dark:bg-red-950 dark:text-red-300">
      <AlertTriangle size={16} />
      <span>{message}</span>
    </div>
  )
}
