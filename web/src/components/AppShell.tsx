import { useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet } from 'react-router'
import { useAuth } from 'react-oidc-context'
import { useQuery } from '@tanstack/react-query'
import { LogIn, LogOut, Menu, ShieldCheck, Store, Terminal, Ticket, X, type LucideIcon } from 'lucide-react'
import { useRoles, useToken } from '../roles'
import { get } from '../api'
import type { AccountBalances } from '../types'
import { formatMinor } from '../format'
import { ThemeToggle } from './ui/ThemeToggle'

export interface ConsoleLink {
  to: string
  label: string
  icon: LucideIcon
}

/** Storefront/ops/dev, whichever the current roles allow - so nobody lands on a 403 by clicking around. */
function AreaSwitcher() {
  const roles = useRoles()
  const areas = [
    { to: '/', label: 'Store', icon: Store, show: true },
    { to: '/admin', label: 'Ops', icon: ShieldCheck, show: roles.admin },
    { to: '/dev', label: 'Dev', icon: Terminal, show: roles.dev },
  ].filter((a) => a.show)
  if (areas.length < 2) return null
  return (
    <div className="flex items-center gap-1 rounded-full border border-line bg-surface-2 p-0.5 text-xs">
      {areas.map(({ to, label, icon: Icon }) => (
        <NavLink
          key={to}
          to={to}
          end={to === '/'}
          className={({ isActive }) =>
            `flex items-center gap-1 rounded-full px-2.5 py-1 transition-colors duration-150 ${
              isActive ? 'bg-brand-600 text-white' : 'text-muted hover:text-fg'
            }`
          }
        >
          <Icon size={12} /> {label}
        </NavLink>
      ))}
    </div>
  )
}

function UserMenu() {
  const auth = useAuth()
  const roles = useRoles()
  const name = auth.user?.profile.preferred_username

  if (!auth.isAuthenticated) {
    return (
      <button
        onClick={() => auth.signinRedirect({ state: window.location.pathname + window.location.search })}
        className="flex items-center gap-1.5 rounded-control bg-brand-600 px-3 py-1.5 text-sm text-white hover:bg-brand-500"
      >
        <LogIn size={14} /> Sign in
      </button>
    )
  }
  return (
    <div className="flex items-center gap-2 text-sm">
      <span className="hidden text-muted sm:inline">{name}</span>
      <button
        onClick={() => auth.signoutRedirect()}
        aria-label="Sign out"
        title={roles.all.join(', ') || 'no roles'}
        className="rounded-control p-1.5 text-muted hover:bg-surface-2 hover:text-fg"
      >
        <LogOut size={16} />
      </button>
    </div>
  )
}

/** MAIN wallet's available balance, customers only - the storefront's one read of the payment-service balances endpoint. */
function BalanceChip() {
  const token = useToken()
  const roles = useRoles()
  const balance = useQuery({
    enabled: !!roles.customer && !!roles.accountId,
    queryKey: ['my-balance', roles.accountId],
    queryFn: () => get<AccountBalances>(`/api/v1/balances/${roles.accountId}`, token),
    refetchInterval: 15_000,
  })
  const main = balance.data?.wallets.find((w) => w.label === 'MAIN')
  if (!main) return null
  return (
    <span className="hidden rounded-full bg-surface-2 px-2.5 py-1 font-mono text-xs tabular-nums text-fg md:inline">
      {formatMinor(main.availableMinor, main.currency)}
    </span>
  )
}

/** Public storefront chrome: brand, nav, balance, area switcher, theme, sign-in/user menu, footer. */
export function StoreShell() {
  const roles = useRoles()
  return (
    <div className="flex min-h-screen flex-col bg-bg">
      <header className="sticky top-0 z-10 border-b border-line bg-surface/90 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center gap-4 px-4 py-3">
          <Link to="/" className="flex items-center gap-1.5 font-display text-lg font-bold text-fg">
            ledgerflow
            <span className="flex items-center gap-1 rounded-full bg-brand-100 px-2 py-0.5 text-[10px] font-medium text-brand-700 dark:bg-brand-700/25 dark:text-brand-400">
              <Ticket size={10} /> tickets
            </span>
          </Link>
          <nav className="hidden items-center gap-4 text-sm text-muted sm:flex">
            <NavLink to="/" end className={({ isActive }) => (isActive ? 'text-fg' : 'hover:text-fg')}>
              Events
            </NavLink>
            {roles.customer && (
              <NavLink to="/tickets" className={({ isActive }) => (isActive ? 'text-fg' : 'hover:text-fg')}>
                My tickets
              </NavLink>
            )}
          </nav>
          <div className="ml-auto flex items-center gap-3">
            <BalanceChip />
            <AreaSwitcher />
            <ThemeToggle />
            <UserMenu />
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6">
        <Outlet />
      </main>
      <footer className="border-t border-line px-4 py-6 text-center text-xs text-muted">ledgerflow · tickets, run on the ledger</footer>
    </div>
  )
}

/** Ops/dev chrome: sidebar of `links`, collapses to a drawer under md; same area switcher, user block, theme. */
export function ConsoleShell({ links, area, children }: { links: ConsoleLink[]; area: string; children: ReactNode }) {
  const auth = useAuth()
  const roles = useRoles()
  const [open, setOpen] = useState(false)
  const name = auth.user?.profile.preferred_username ?? '-'

  const nav = (
    <>
      <div className="px-4 py-4 font-display text-sm font-semibold tracking-tight text-fg">ledgerflow {area}</div>
      <div className="flex-1 space-y-1 px-2">
        {links.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            end
            onClick={() => setOpen(false)}
            className={({ isActive }) =>
              `flex items-center gap-2 rounded-control px-3 py-2 text-sm transition-colors duration-150 ${
                isActive ? 'bg-brand-600 text-white' : 'text-muted hover:bg-surface-2 hover:text-fg'
              }`
            }
          >
            <Icon size={16} /> {label}
          </NavLink>
        ))}
      </div>
      <div className="space-y-2 border-t border-line p-3">
        <AreaSwitcher />
        <div className="flex items-center justify-between text-xs">
          <div>
            <div className="font-medium text-fg">{name}</div>
            <div className="text-muted">{roles.all.join(', ') || 'no roles'}</div>
          </div>
          <ThemeToggle />
        </div>
        <button onClick={() => auth.signoutRedirect()} className="flex items-center gap-1 text-xs text-muted hover:text-fg">
          <LogOut size={14} /> sign out
        </button>
      </div>
    </>
  )

  return (
    <div className="flex min-h-screen bg-bg">
      <nav className="hidden w-56 flex-col border-r border-line bg-surface md:flex">{nav}</nav>
      {open && (
        <div className="fixed inset-0 z-20 flex md:hidden">
          <div className="flex w-64 flex-col border-r border-line bg-surface">{nav}</div>
          <button aria-label="Close menu" className="flex-1 bg-black/30" onClick={() => setOpen(false)} />
        </div>
      )}
      <div className="flex flex-1 flex-col">
        <div className="flex items-center gap-2 border-b border-line p-3 md:hidden">
          <button aria-label={open ? 'Close menu' : 'Open menu'} onClick={() => setOpen((o) => !o)} className="rounded-control p-1.5 hover:bg-surface-2">
            {open ? <X size={18} /> : <Menu size={18} />}
          </button>
          <span className="font-display text-sm font-semibold">ledgerflow {area}</span>
        </div>
        <main className="flex-1 overflow-auto p-6">{children}</main>
      </div>
    </div>
  )
}
