import { Routes, Route } from 'react-router'
import { ArrowLeftRight, CalendarClock, CreditCard, LayoutDashboard, ShieldAlert, Ticket, ClipboardList } from 'lucide-react'
import { ConsoleShell, type ConsoleLink } from '../../components/AppShell'
import { Overview } from './Overview'
import { Events } from './Events'
import { Orders } from './Orders'
import { Payments } from './Payments'
import { PaymentDetail } from './PaymentDetail'
import { Wallets } from './Wallets'
import { Risk } from './Risk'
import { Settlement } from './Settlement'

// lane D adds a page here and a matching entry here - nothing else needs to know about it
// eslint-disable-next-line react-refresh/only-export-components -- colocated with the routes on purpose, see the lane brief
export const adminLinks: ConsoleLink[] = [
  { to: '/admin', label: 'Overview', icon: LayoutDashboard },
  { to: '/admin/events', label: 'Events', icon: Ticket },
  { to: '/admin/orders', label: 'Orders', icon: ClipboardList },
  { to: '/admin/payments', label: 'Payments', icon: CreditCard },
  { to: '/admin/wallets', label: 'Wallets', icon: ArrowLeftRight },
  { to: '/admin/risk', label: 'Risk', icon: ShieldAlert },
  { to: '/admin/settlement', label: 'Settlement', icon: CalendarClock },
]

export default function AdminArea() {
  return (
    <ConsoleShell links={adminLinks} area="ops">
      <Routes>
        <Route index element={<Overview />} />
        <Route path="events" element={<Events />} />
        <Route path="orders" element={<Orders />} />
        <Route path="payments" element={<Payments />} />
        <Route path="payments/:id" element={<PaymentDetail />} />
        <Route path="wallets" element={<Wallets />} />
        <Route path="risk" element={<Risk />} />
        <Route path="settlement" element={<Settlement />} />
      </Routes>
    </ConsoleShell>
  )
}
