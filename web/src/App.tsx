import { useCallback, useEffect, type ReactNode } from 'react'
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
  // the page asked for rides along as state; auth.tsx puts it back after the callback
  const signIn = useCallback(() => auth.signinRedirect({ state: window.location.pathname + window.location.search }), [auth])
  const needsLogin = !auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error
  useEffect(() => setUnauthorizedHandler(signIn), [signIn])
  useEffect(() => {
    if (needsLogin) signIn()
  }, [needsLogin, signIn])

  if (auth.error) {
    return (
      <div className="p-8 text-sm text-red-600 dark:text-red-400">
        sign-in failed: {auth.error.message}{' '}
        <button onClick={signIn} className="underline">
          try again
        </button>
      </div>
    )
  }
  if (!auth.isAuthenticated) return <div className="p-8 text-sm text-slate-400">signing in…</div>
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
