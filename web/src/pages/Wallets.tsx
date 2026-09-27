import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useToken } from '../roles'
import { get, post } from '../api'
import type { Account, AccountBalances } from '../types'
import { formatMinor } from '../format'
import { WriteButton } from '../components/WriteButton'
import { Loading, Empty, ErrorBox } from '../components/States'

export function Wallets() {
  const token = useToken()
  const qc = useQueryClient()
  const [selected, setSelected] = useState<string | null>(null)
  const [projection, setProjection] = useState<string | null>(null)

  const accounts = useQuery({ queryKey: ['accounts'], queryFn: () => get<Account[]>('/api/v1/accounts', token) })
  const accountId = selected ?? accounts.data?.[0]?.id ?? null

  const balances = useQuery({
    enabled: !!accountId,
    queryKey: ['balances', accountId],
    queryFn: () => get<AccountBalances>(`/api/v1/balances/${accountId}`, token),
  })

  const [fromWalletId, setFromWalletId] = useState('')
  const [toWalletId, setToWalletId] = useState('')
  const [amountMinor, setAmountMinor] = useState(1000)

  const transfer = useMutation({
    mutationFn: () =>
      post(
        '/api/v1/transfers',
        token,
        { fromWalletId, toWalletId, amountMinor, currency: 'GBP', description: 'demo transfer' },
        { 'Idempotency-Key': crypto.randomUUID() },
      ),
    onSuccess: async ({ requestId }) => {
      setProjection(null)
      await qc.invalidateQueries({ queryKey: ['balances', accountId] })
      // read-your-own-write: re-fetch with ?after so the response tells us caught-up vs lagging (ADR 0002)
      const res = await fetch(`/api/v1/balances/${accountId}?after=${requestId}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      setProjection(res.headers.get('X-Projection'))
      qc.invalidateQueries({ queryKey: ['balances', accountId] })
    },
  })

  const account = accounts.data?.find((a) => a.id === accountId)

  return (
    <div className="space-y-6">
      <h1 className="text-lg font-semibold">Wallets</h1>

      {accounts.isLoading && <Loading />}
      {accounts.isError && <ErrorBox error={accounts.error} />}

      <select
        className="rounded border border-slate-200 px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
        value={accountId ?? ''}
        onChange={(e) => setSelected(e.target.value)}
      >
        {accounts.data?.map((a) => (
          <option key={a.id} value={a.id}>
            {a.name}
          </option>
        ))}
      </select>

      {balances.isLoading && <Loading />}
      {balances.isError && <ErrorBox error={balances.error} />}
      {balances.data && balances.data.wallets.length === 0 && <Empty>No wallets for this account.</Empty>}
      {balances.data && balances.data.wallets.length > 0 && (
        <table className="w-full text-sm">
          <thead className="text-left text-xs text-slate-400">
            <tr>
              <th className="py-1">Wallet</th>
              <th>Balance</th>
              <th>Held</th>
              <th>Available</th>
            </tr>
          </thead>
          <tbody>
            {balances.data.wallets.map((w) => (
              <tr key={w.label} className="border-t border-slate-100 dark:border-slate-800">
                <td className="py-2">{w.label}</td>
                <td className="tabular">{formatMinor(w.balanceMinor, w.currency)}</td>
                <td className="tabular">{formatMinor(w.heldMinor, w.currency)}</td>
                <td className="tabular">{formatMinor(w.availableMinor, w.currency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault()
          transfer.mutate()
        }}
        className="space-y-3 rounded border border-slate-200 p-4 dark:border-slate-800"
      >
        <div className="text-sm font-medium">Transfer between wallets</div>
        <div className="flex gap-2 text-sm">
          <select
            className="flex-1 rounded border border-slate-200 px-2 py-1 dark:border-slate-700 dark:bg-slate-900"
            value={fromWalletId}
            onChange={(e) => setFromWalletId(e.target.value)}
          >
            <option value="">from wallet…</option>
            {account?.wallets.map((w) => (
              <option key={w.id} value={w.id}>
                {w.label}
              </option>
            ))}
          </select>
          <select
            className="flex-1 rounded border border-slate-200 px-2 py-1 dark:border-slate-700 dark:bg-slate-900"
            value={toWalletId}
            onChange={(e) => setToWalletId(e.target.value)}
          >
            <option value="">to wallet…</option>
            {account?.wallets.map((w) => (
              <option key={w.id} value={w.id}>
                {w.label}
              </option>
            ))}
          </select>
          <input
            type="number"
            className="w-32 rounded border border-slate-200 px-2 py-1 dark:border-slate-700 dark:bg-slate-900"
            value={amountMinor}
            onChange={(e) => setAmountMinor(Number(e.target.value))}
            min={1}
          />
        </div>
        <WriteButton
          type="submit"
          disabled={!fromWalletId || !toWalletId || transfer.isPending}
          className="rounded bg-indigo-600 px-4 py-2 text-sm text-white hover:bg-indigo-500"
        >
          {transfer.isPending ? 'Transferring…' : 'Transfer'}
        </WriteButton>
        {transfer.isError && <ErrorBox error={transfer.error} />}
        {projection && (
          <div className="text-xs text-slate-400">
            re-read after write: <span className="font-mono">{projection}</span>
          </div>
        )}
      </form>
    </div>
  )
}
