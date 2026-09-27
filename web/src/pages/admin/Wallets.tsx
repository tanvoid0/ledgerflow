import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useToken } from '../../roles'
import { get, getWithHeaders, post } from '../../api'
import type { Account, AccountBalances } from '../../types'
import { formatMinor } from '../../format'
import { PageHeader } from '../../components/ui/PageHeader'
import { Card } from '../../components/ui/Card'
import { WriteButton } from '../../components/ui/Button'
import { Field, Select, Input } from '../../components/ui/Field'
import { Table, THead, TR, TH, TD } from '../../components/ui/Table'
import { Skeleton, Empty, ErrorBox } from '../../components/ui/States'

export function Wallets() {
  const token = useToken()
  const qc = useQueryClient()
  const [selected, setSelected] = useState<string | null>(null)
  const [projection, setProjection] = useState<string | null>(null)

  const accounts = useQuery({ queryKey: ['accounts'], queryFn: () => get<Account[]>('/api/v1/accounts', token) })
  const accountId = selected ?? accounts.data?.[0]?.id ?? null
  const account = accounts.data?.find((a) => a.id === accountId)

  const balances = useQuery({
    enabled: !!accountId,
    queryKey: ['balances', accountId],
    queryFn: () => get<AccountBalances>(`/api/v1/balances/${accountId}`, token),
  })

  const [fromWalletId, setFromWalletId] = useState('')
  const [toWalletId, setToWalletId] = useState('')
  const [amountMinor, setAmountMinor] = useState(1000)
  const labelOf = (walletId: string) => account?.wallets.find((w) => w.id === walletId)?.label

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
      // read-your-own-write: only the single-wallet read takes ?after - it waits for this request's event
      // and says caught-up vs lagging in X-Projection (ADR 0002). One event moves both wallets, so either will do.
      const { headers } = await getWithHeaders(
        `/api/v1/balances/${accountId}/${encodeURIComponent(labelOf(toWalletId) ?? '')}?after=${requestId}`,
        token,
      )
      setProjection(headers.get('X-Projection'))
      qc.invalidateQueries({ queryKey: ['balances', accountId] })
    },
  })

  return (
    <div className="space-y-6">
      <PageHeader title="Wallets" />

      {accounts.isLoading && <Skeleton />}
      {accounts.isError && <ErrorBox error={accounts.error} />}

      <Field label="Account">
        <Select
          className="max-w-xs"
          value={accountId ?? ''}
          onChange={(e) => {
            setSelected(e.target.value)
            setFromWalletId('') // wallet ids belong to the account they were picked from
            setToWalletId('')
          }}
        >
          {accounts.data?.map((a) => (
            <option key={a.id} value={a.id}>
              {a.name}
            </option>
          ))}
        </Select>
      </Field>

      {balances.isLoading && <Skeleton />}
      {balances.isError && <ErrorBox error={balances.error} />}
      {balances.data && balances.data.wallets.length === 0 && <Empty>No wallets for this account.</Empty>}
      {balances.data && balances.data.wallets.length > 0 && (
        <Table>
          <THead>
            <tr>
              <TH>Wallet</TH>
              <TH>Balance</TH>
              <TH>Held</TH>
              <TH>Available</TH>
            </tr>
          </THead>
          <tbody>
            {balances.data.wallets.map((w) => (
              <TR key={w.label}>
                <TD className="font-medium text-fg">{w.label}</TD>
                <TD className="tabular">{formatMinor(w.balanceMinor, w.currency)}</TD>
                <TD className="tabular">{formatMinor(w.heldMinor, w.currency)}</TD>
                <TD className="tabular">{formatMinor(w.availableMinor, w.currency)}</TD>
              </TR>
            ))}
          </tbody>
        </Table>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault()
          transfer.mutate()
        }}
      >
        <Card className="space-y-3">
          <div className="text-sm font-medium text-fg">Transfer between wallets</div>
          <div className="flex gap-2">
            <Field label="From">
              <Select value={fromWalletId} onChange={(e) => setFromWalletId(e.target.value)}>
                <option value="">from wallet…</option>
                {account?.wallets.map((w) => <option key={w.id} value={w.id}>{w.label}</option>)}
              </Select>
            </Field>
            <Field label="To">
              <Select value={toWalletId} onChange={(e) => setToWalletId(e.target.value)}>
                <option value="">to wallet…</option>
                {account?.wallets.map((w) => <option key={w.id} value={w.id}>{w.label}</option>)}
              </Select>
            </Field>
            <Field label="Amount (minor)">
              <Input type="number" className="w-32" value={amountMinor} onChange={(e) => setAmountMinor(Number(e.target.value))} min={1} />
            </Field>
          </div>
          <WriteButton type="submit" disabled={!fromWalletId || !toWalletId || fromWalletId === toWalletId} loading={transfer.isPending}>
            Transfer
          </WriteButton>
          {transfer.isError && <ErrorBox error={transfer.error} />}
          {projection && (
            <div className="text-xs text-muted">
              re-read after write: <span className="font-mono">{projection}</span>
            </div>
          )}
        </Card>
      </form>
    </div>
  )
}
