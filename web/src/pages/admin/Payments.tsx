import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { useToken } from '../../roles'
import { get, post } from '../../api'
import type { Account, PaymentState } from '../../types'
import { formatMinor } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { Card } from '../../components/ui/Card'
import { StateBadge } from '../../components/ui/Badge'
import { WriteButton } from '../../components/ui/Button'
import { Field, Input } from '../../components/ui/Field'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

// A handful of amounts that walk the saga down each documented path (README "Run it").
const PRESETS = [
  { label: 'Happy path', amountMinor: 4500 },
  { label: 'Declined by issuer', amountMinor: 1 },
  { label: 'Capture fails', amountMinor: 20000 },
  { label: 'Insufficient funds', amountMinor: 99_999_999 },
]

const SEED_ACCOUNT = '11111111-1111-1111-1111-111111111111'
// the funding source: exempt from the funds check (V5), so paying from it would mint money
const TREASURY = 'TREASURY'

export function Payments() {
  const token = useToken()
  const navigate = useNavigate()
  const qc = useQueryClient()

  const payments = useQuery({
    queryKey: ['payments'],
    queryFn: () => get<PaymentState[]>('/api/v1/payments?limit=50', token),
    refetchInterval: 3000,
  })
  const account = useQuery({
    queryKey: ['account', SEED_ACCOUNT],
    queryFn: () => get<Account>(`/api/v1/accounts/${SEED_ACCOUNT}`, token),
  })

  const [wallets, setWallets] = useState<string[]>([])
  const [amountMinor, setAmountMinor] = useState(4500)
  const [currency, setCurrency] = useState('GBP')
  const [beneficiary, setBeneficiary] = useState('')

  const start = useMutation({
    mutationFn: () =>
      post<PaymentState>('/api/v1/payments', token, {
        accountId: SEED_ACCOUNT,
        wallets,
        amountMinor,
        currency,
        beneficiary: beneficiary || undefined,
      }),
    onSuccess: ({ data, requestId }) => {
      qc.invalidateQueries({ queryKey: ['payments'] })
      navigate(`/admin/payments/${data.paymentId}?correlationId=${requestId}`)
    },
  })

  function toggleWallet(label: string) {
    setWallets((w) => (w.includes(label) ? w.filter((x) => x !== label) : [...w, label]))
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Payments" />

      <form
        onSubmit={(e) => {
          e.preventDefault()
          start.mutate()
        }}
      >
        <Card className="space-y-3">
          <div className="text-sm font-medium text-fg">New payment</div>
          <div className="flex flex-wrap gap-2 text-xs">
            {account.data?.wallets.filter((w) => w.label !== TREASURY).map((w) => (
              <label key={w.id} className="flex items-center gap-1 rounded-control border border-line px-2 py-1 text-fg">
                <input type="checkbox" checked={wallets.includes(w.label)} onChange={() => toggleWallet(w.label)} />
                {w.label}
              </label>
            ))}
          </div>
          <div className="flex gap-2">
            <Field label="Amount (minor)" >
              <Input type="number" className="w-40" value={amountMinor} onChange={(e) => setAmountMinor(Number(e.target.value))} min={1} />
            </Field>
            <Field label="Currency">
              <Input className="w-20 uppercase" value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} maxLength={3} />
            </Field>
            <Field label="Beneficiary (optional)">
              <Input value={beneficiary} onChange={(e) => setBeneficiary(e.target.value)} />
            </Field>
          </div>
          <div className="flex flex-wrap gap-2 text-xs">
            {PRESETS.map((p) => (
              <button
                key={p.label}
                type="button"
                onClick={() => setAmountMinor(p.amountMinor)}
                className="rounded-control border border-line px-2 py-1 text-muted hover:bg-surface-2 hover:text-fg"
              >
                {p.label}
              </button>
            ))}
          </div>
          <WriteButton type="submit" disabled={wallets.length === 0} loading={start.isPending}>
            Start payment
          </WriteButton>
          {start.isError && <ErrorBox error={start.error} />}
        </Card>
      </form>

      {payments.isLoading && <Skeleton />}
      {payments.isError && <ErrorBox error={payments.error} />}
      {payments.data && payments.data.length === 0 && <Empty>No payments yet.</Empty>}
      {payments.data && payments.data.length > 0 && (
        <Table>
          <THead>
            <tr>
              <TH>Payment</TH>
              <TH>State</TH>
              <TH>Amount</TH>
            </tr>
          </THead>
          <tbody>
            {payments.data.map((p) => (
              <TR key={p.paymentId} className="cursor-pointer" onClick={() => navigate(`/admin/payments/${p.paymentId}`)}>
                <TD className="font-mono text-xs">
                  {/* no handler: Enter on the focused button clicks it, and the click bubbles to the row */}
                  <button type="button">{p.paymentId.slice(0, 8)}</button>
                </TD>
                <TD><StateBadge state={p.state} /></TD>
                <TD className="tabular">{'amount' in p ? formatMinor(p.amount.minorUnits, p.amount.currency) : '-'}</TD>
              </TR>
            ))}
          </tbody>
        </Table>
      )}
    </div>
  )
}
