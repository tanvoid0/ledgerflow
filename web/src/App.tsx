import { lazy, Suspense, useEffect } from 'react'
import { BrowserRouter, Routes, Route, useLocation } from 'react-router'
import { useAuth } from 'react-oidc-context'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LedgerflowAuthProvider } from './auth'
import { setUnauthorizedHandler } from './api'
import { RequireRole } from './components/RequireRole'
import { StoreShell } from './components/AppShell'
import { Home } from './pages/store/Home'
import { EventPage } from './pages/store/EventPage'
import { MyOrders } from './pages/store/MyOrders'
import { OrderPage } from './pages/store/OrderPage'

const queryClient = new QueryClient()

// admin/dev pull in Payments/Wallets/Risk/Settlement/Live and their own icon set - split them out
// of the storefront's chunk, the public path most visitors take
const AdminArea = lazy(() => import('./pages/admin/routes'))
const DevArea = lazy(() => import('./pages/dev/routes'))

/** The app's one 401 handler: back through sign-in to whatever page the visitor is on now. */
function UnauthorizedRedirect() {
  const auth = useAuth()
  const location = useLocation()
  useEffect(() => {
    setUnauthorizedHandler(() => auth.signinRedirect({ state: location.pathname + location.search }))
  }, [auth, location])
  return null
}

function AreaFallback() {
  return <div className="p-8 text-sm text-muted">loading…</div>
}

function App() {
  return (
    <LedgerflowAuthProvider>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <UnauthorizedRedirect />
          <Routes>
            <Route element={<StoreShell />}>
              <Route index element={<Home />} />
              <Route path="events/:id" element={<EventPage />} />
              <Route
                path="tickets"
                element={
                  <RequireRole role="customer">
                    <MyOrders />
                  </RequireRole>
                }
              />
              <Route
                path="orders/:id"
                element={
                  <RequireRole role="customer">
                    <OrderPage />
                  </RequireRole>
                }
              />
            </Route>
            <Route
              path="admin/*"
              element={
                <RequireRole role="admin">
                  <Suspense fallback={<AreaFallback />}>
                    <AdminArea />
                  </Suspense>
                </RequireRole>
              }
            />
            <Route
              path="dev/*"
              element={
                <RequireRole role="dev">
                  <Suspense fallback={<AreaFallback />}>
                    <DevArea />
                  </Suspense>
                </RequireRole>
              }
            />
          </Routes>
        </BrowserRouter>
      </QueryClientProvider>
    </LedgerflowAuthProvider>
  )
}

export default App
