const COLORS: Record<string, string> = {
  Requested: 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300',
  AuthorizationPending: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300',
  CapturePending: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300',
  Captured: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-300',
  Failed: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300',
  REVIEW: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-300',
  BLOCK: 'bg-red-100 text-red-800 dark:bg-red-900 dark:text-red-300',
}

export function StateBadge({ state }: { state: string }) {
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${COLORS[state] ?? 'bg-slate-100 text-slate-700'}`}>{state}</span>
  )
}
