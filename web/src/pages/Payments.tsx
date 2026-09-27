import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { useToken } from '../roles'
import { get, post } from '../api'
import type { Account, PaymentState } from '../types'
import { formatMinor } from '../format'
import { StateBadge } from '../components/StateBadge'
import { WriteButton } from '../components/WriteButton'
import { Loading, Empty, ErrorBox } from '../components/States'

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
      navigate(`/payments/${data.paymentId}?correlationId=${requestId}`)
    },
  })

  function toggleWallet(label: string) {
    setWallets((w) => (w.includes(label) ? w.filter((x) => x !== label) : [...w, label]))
  }

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold">Payments</h1>

      <form
        onSubmit={(e) => {
          e.preventDefault()
          start.mutate()
        }}
        className="space-y-3 rounded border border-slate-200 p-4 dark:border-slate-800"
      >
        <div className="text-sm font-medium">New payment</div>
        <div className="flex flex-wrap gap-2 text-xs">
          {account.data?.wallets.filter((w) => w.label !== TREASURY).map((w) => (
            <label key={w.id} className="flex items-center gap-1 rounded border border-slate-200 px-2 py-1 dark:border-slate-700">
              <input type="checkbox" checked={wallets.includes(w.label)} onChange={() => toggleWallet(w.label)} />
              {w.label}
            </label>
          ))}
        </div>
        <div className="flex gap-2">
          <input
            type="number"
            className="w-40 rounded border border-slate-200 px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
            value={amountMinor}
            onChange={(e) => setAmountMinor(Number(e.target.value))}
            min={1}
          />
          <input
            className="w-20 rounded border border-slate-200 px-2 py-1 text-sm uppercase dark:border-slate-700 dark:bg-slate-900"
            value={currency}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
            maxLength={3}
          />
          <input
            className="flex-1 rounded border border-slate-200 px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
            placeholder="beneficiary (optional)"
            value={beneficiary}
            onChange={(e) => setBeneficiary(e.target.value)}
          />
        </div>
        <div className="flex flex-wrap gap-2 text-xs">
          {PRESETS.map((p) => (
            <button
              key={p.label}
              type="button"
              onClick={() => setAmountMinor(p.amountMinor)}
              className="rounded border border-slate-200 px-2 py-1 hover:bg-slate-50 dark:border-slate-700 dark:hover:bg-slate-800"
            >
              {p.label}
            </button>
          ))}
        </div>
        <WriteButton
          type="submit"
          disabled={wallets.length === 0 || start.isPending}
          className="rounded bg-indigo-600 px-4 py-2 text-sm text-white hover:bg-indigo-500"
        >
          {start.isPending ? 'Starting…' : 'Start payment'}
        </WriteButton>
        {start.isError && <ErrorBox error={start.error} />}
      </form>

      {payments.isLoading && <Loading />}
      {payments.isError && <ErrorBox error={payments.error} />}
      {payments.data && payments.data.length === 0 && <Empty>No payments yet.</Empty>}
      {payments.data && payments.data.length > 0 && (
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-slate-400">
            <tr>
              <th className="py-1">Payment</th>
              <th>State</th>
              <th>Amount</th>
            </tr>
          </thead>
          <tbody>
            {payments.data.map((p) => (
              <tr
                key={p.paymentId}
                className="cursor-pointer border-t border-slate-100 hover:bg-slate-50 dark:border-slate-800 dark:hover:bg-slate-900"
                onClick={() => navigate(`/payments/${p.paymentId}`)}
              >
                <td className="py-2 font-mono text-xs">
                  {/* no handler: Enter on the focused button clicks it, and the click bubbles to the row */}
                  <button type="button">{p.paymentId.slice(0, 8)}</button>
                </td>
                <td><StateBadge state={p.state} /></td>
                <td className="tabular">
                  {'amount' in p ? formatMinor(p.amount.minorUnits, p.amount.currency) : '-'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
