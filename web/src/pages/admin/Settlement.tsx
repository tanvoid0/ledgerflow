import { useState } from 'react'
import { useQuery, useMutation } from '@tanstack/react-query'
import { useToken } from '../../roles'
import { get, post } from '../../api'
import type { JobExecution } from '../../types'
import { PageHeader } from '../../components/ui/PageHeader'
import { Card } from '../../components/ui/Card'
import { WriteButton } from '../../components/ui/Button'
import { Field, Select, Input } from '../../components/ui/Field'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

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
      <PageHeader title="Settlement" />

      {jobs.isLoading && <Skeleton />}
      {jobs.isError && <ErrorBox error={jobs.error} />}
      {jobs.data && jobs.data.length === 0 && <Empty>No batch jobs registered.</Empty>}

      <form
        onSubmit={(e) => {
          e.preventDefault()
          launch.mutate()
        }}
      >
        <Card className="space-y-3">
          <div className="text-sm font-medium text-fg">Launch a job</div>
          <div className="flex gap-2">
            <Field label="Job">
              <Select value={jobName} onChange={(e) => setJobName(e.target.value)}>
                <option value="">job…</option>
                {jobs.data?.map((n) => <option key={n} value={n}>{n}</option>)}
              </Select>
            </Field>
            <Field label="Business date">
              <Input type="date" value={businessDate} onChange={(e) => setBusinessDate(e.target.value)} />
            </Field>
          </div>
          <WriteButton type="submit" disabled={!jobName || !businessDate} loading={launch.isPending}>
            Launch
          </WriteButton>
          {launch.isError && <ErrorBox error={launch.error} />}
        </Card>
      </form>

      {/* No endpoint lists executions by job (BatchLaunchController only names jobs); an execution id
          is what restart/recover/stop take, so it's a field here rather than a row action. */}
      <Card className="space-y-3">
        <div className="text-sm font-medium text-fg">Act on an execution</div>
        <Field label="Execution id">
          <Input className="w-40" value={executionId} onChange={(e) => setExecutionId(e.target.value)} />
        </Field>
        <div className="flex gap-2">
          <WriteButton variant="secondary" size="sm" disabled={!executionId} loading={act.isPending} onClick={() => act.mutate('restart')}>
            Restart
          </WriteButton>
          <WriteButton variant="secondary" size="sm" disabled={!executionId} loading={act.isPending} onClick={() => act.mutate('recover')}>
            Recover
          </WriteButton>
          <WriteButton variant="secondary" size="sm" disabled={!executionId} loading={act.isPending} onClick={() => act.mutate('stop')}>
            Stop
          </WriteButton>
        </div>
        {act.isError && <ErrorBox error={act.error} />}
      </Card>

      {last && (
        <Card className="text-sm">
          <div className="font-medium text-fg">{last.jobName} #{last.executionId}</div>
          <div className="text-xs text-muted">
            {last.status} / {last.exitCode} · {last.startTime ?? '-'} → {last.endTime ?? '-'}
          </div>
        </Card>
      )}
    </div>
  )
}
