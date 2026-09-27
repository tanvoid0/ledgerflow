import { NavLink, Outlet } from 'react-router'
import { useAuth } from 'react-oidc-context'
import { Activity, ArrowLeftRight, ShieldAlert, CreditCard, CalendarClock, LogOut } from 'lucide-react'
import { useRoles } from '../roles'

const links = [
  { to: '/', label: 'Live', icon: Activity },
  { to: '/payments', label: 'Payments', icon: CreditCard },
  { to: '/wallets', label: 'Wallets', icon: ArrowLeftRight },
  { to: '/risk', label: 'Risk', icon: ShieldAlert },
  { to: '/settlement', label: 'Settlement', icon: CalendarClock },
]

export function Layout() {
  const auth = useAuth()
  const roles = useRoles()
  const name = auth.user?.profile.preferred_username ?? '-'

  return (
    <div className="flex h-screen">
      <nav className="flex w-48 flex-col border-r border-slate-200 bg-slate-50 dark:border-slate-800 dark:bg-slate-900">
        <div className="px-4 py-4 text-sm font-semibold tracking-tight">LedgerFlow</div>
        <div className="flex-1 space-y-1 px-2">
          {links.map(({ to, label, icon: Icon }) => (
            <NavLink
              key={to}
              to={to}
              end={to === '/'}
              className={({ isActive }) =>
                `flex items-center gap-2 rounded px-3 py-2 text-sm ${
                  isActive ? 'bg-indigo-600 text-white' : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'
                }`
              }
            >
              <Icon size={16} /> {label}
            </NavLink>
          ))}
        </div>
        <div className="border-t border-slate-200 p-3 text-xs dark:border-slate-800">
          <div className="font-medium">{name}</div>
          <div className="text-slate-400">{roles.all.join(', ') || 'no roles'}</div>
          <button
            onClick={() => auth.signoutRedirect()}
            className="mt-2 flex items-center gap-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200"
          >
            <LogOut size={14} /> sign out
          </button>
        </div>
      </nav>
      <main className="flex-1 overflow-auto p-6">
        <Outlet />
      </main>
    </div>
  )
}
