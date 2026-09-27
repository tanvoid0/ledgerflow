import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { useToken } from '../roles'
import { get, post } from '../api'
import type { JobExecution } from '../types'
import { WriteButton } from '../components/WriteButton'
import { Loading, Empty, ErrorBox } from '../components/States'

export function Settlement() {
  const token = useToken()
  const jobs = useQuery({ queryKey: ['jobs'], queryFn: () => get<string[]>('/api/v1/batch/jobs', token) })

  const [jobName, setJobName] = useState('')
  const [businessDate, setBusinessDate] = useState('')
  const [executionId, setExecutionId] = useState('')
  const [last, setLast] = useState<JobExecution | null>(null)

  const launch = useMutation({
    mutationFn: () => post<JobExecution>(`/api/v1/batch/jobs/${jobName}`, token, { businessDate }),
    onSuccess: ({ data }) => setLast(data),
  })

  // one mutation for all three, so the error shown is always the last action's
  const act = useMutation({
    mutationFn: (action: 'restart' | 'recover' | 'stop') =>
      post<JobExecution>(`/api/v1/batch/executions/${executionId}/${action}`, token, {}),
    onSuccess: ({ data }) => setLast(data),
  })

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold">Settlement</h1>

      {jobs.isLoading && <Loading />}
      {jobs.isError && <ErrorBox error={jobs.error} />}
      {jobs.data && jobs.data.length === 0 && <Empty>No batch jobs registered.</Empty>}

      <form
        onSubmit={(e) => {
          e.preventDefault()
          launch.mutate()
        }}
        className="space-y-3 rounded border border-slate-200 p-4 dark:border-slate-800"
      >
        <div className="text-sm font-medium">Launch a job</div>
        <div className="flex gap-2 text-sm">
          <select
            className="rounded border border-slate-200 px-2 py-1 dark:border-slate-700 dark:bg-slate-900"
            value={jobName}
            onChange={(e) => setJobName(e.target.value)}
          >
            <option value="">job…</option>
            {jobs.data?.map((n) => (
              <option key={n} value={n}>
                {n}
              </option>
            ))}
          </select>
          <input
            type="date"
            className="rounded border border-slate-200 px-2 py-1 dark:border-slate-700 dark:bg-slate-900"
            value={businessDate}
            onChange={(e) => setBusinessDate(e.target.value)}
          />
        </div>
        <WriteButton
          type="submit"
          disabled={!jobName || !businessDate || launch.isPending}
          className="rounded bg-indigo-600 px-4 py-2 text-sm text-white hover:bg-indigo-500"
        >
          {launch.isPending ? 'Launching…' : 'Launch'}
        </WriteButton>
        {launch.isError && <ErrorBox error={launch.error} />}
      </form>

      {/* No endpoint lists executions by job (BatchLaunchController only names jobs); an execution id
          is what restart/recover/stop take, so it's a field here rather than a row action. */}
      <div className="space-y-3 rounded border border-slate-200 p-4 dark:border-slate-800">
        <div className="text-sm font-medium">Act on an execution</div>
        <input
          className="w-40 rounded border border-slate-200 px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
          placeholder="execution id"
          value={executionId}
          onChange={(e) => setExecutionId(e.target.value)}
        />
        <div className="flex gap-2">
          <WriteButton
            onClick={() => act.mutate('restart')}
            disabled={!executionId || act.isPending}
            className="rounded border border-slate-200 px-3 py-1.5 text-sm dark:border-slate-700"
          >
            Restart
          </WriteButton>
          <WriteButton
            onClick={() => act.mutate('recover')}
            disabled={!executionId || act.isPending}
            className="rounded border border-slate-200 px-3 py-1.5 text-sm dark:border-slate-700"
          >
            Recover
          </WriteButton>
          <WriteButton
            onClick={() => act.mutate('stop')}
            disabled={!executionId || act.isPending}
            className="rounded border border-slate-200 px-3 py-1.5 text-sm dark:border-slate-700"
          >
            Stop
          </WriteButton>
        </div>
        {act.isError && <ErrorBox error={act.error} />}
      </div>

      {last && (
        <div className="rounded border border-slate-200 p-4 text-sm dark:border-slate-800">
          <div className="font-medium">{last.jobName} #{last.executionId}</div>
          <div className="text-xs text-slate-400">
            {last.status} / {last.exitCode} · {last.startTime ?? '-'} → {last.endTime ?? '-'}
          </div>
        </div>
      )}
    </div>
  )
}
