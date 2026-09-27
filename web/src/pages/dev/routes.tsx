import { Routes, Route } from 'react-router'
import { Activity, Gauge, History, Waypoints } from 'lucide-react'
import { ConsoleShell, type ConsoleLink } from '../../components/AppShell'
import { Perf } from './Perf'
import { Live } from './Live'
import { Bench } from './Bench'
import { Traces } from './Traces'

// lane D adds a page here and a matching entry here - nothing else needs to know about it
// eslint-disable-next-line react-refresh/only-export-components -- colocated with the routes on purpose, see the lane brief
export const devLinks: ConsoleLink[] = [
  { to: '/dev', label: 'Performance', icon: Gauge },
  { to: '/dev/live', label: 'Live map', icon: Activity },
  { to: '/dev/bench', label: 'Bench history', icon: History },
  { to: '/dev/traces', label: 'Slow traces', icon: Waypoints },
]

export default function DevArea() {
  return (
    <ConsoleShell links={devLinks} area="dev">
      <Routes>
        <Route index element={<Perf />} />
        <Route path="live" element={<Live />} />
        <Route path="bench" element={<Bench />} />
        <Route path="traces" element={<Traces />} />
      </Routes>
    </ConsoleShell>
  )
}
