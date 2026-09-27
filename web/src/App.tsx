import { useEffect, type ReactNode } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuth } from 'react-oidc-context'
import { LedgerflowAuthProvider } from './auth'
import { setUnauthorizedHandler } from './api'
import { Layout } from './components/Layout'
import { Live } from './pages/Live'
import { Payments } from './pages/Payments'
import { PaymentDetail } from './pages/PaymentDetail'
import { Wallets } from './pages/Wallets'
import { Risk } from './pages/Risk'
import { Settlement } from './pages/Settlement'

const queryClient = new QueryClient()

function Gate({ children }: { children: ReactNode }) {
  const auth = useAuth()
  useEffect(() => setUnauthorizedHandler(() => auth.signinRedirect()), [auth])
  if (auth.isLoading) return <div className="p-8 text-sm text-slate-400">signing in…</div>
  if (!auth.isAuthenticated) {
    auth.signinRedirect()
    return <div className="p-8 text-sm text-slate-400">redirecting to login…</div>
  }
  return <>{children}</>
}

function App() {
  return (
    <LedgerflowAuthProvider>
      <QueryClientProvider client={queryClient}>
        <Gate>
          <BrowserRouter>
            <Routes>
              <Route element={<Layout />}>
                <Route index element={<Live />} />
                <Route path="payments" element={<Payments />} />
                <Route path="payments/:id" element={<PaymentDetail />} />
                <Route path="wallets" element={<Wallets />} />
                <Route path="risk" element={<Risk />} />
                <Route path="settlement" element={<Settlement />} />
              </Route>
            </Routes>
          </BrowserRouter>
        </Gate>
      </QueryClientProvider>
    </LedgerflowAuthProvider>
  )
}

export default App
