import { useCallback, useEffect, type ReactNode } from 'react'
import { useAuth } from 'react-oidc-context'
import { Link, useLocation } from 'react-router'
import { useRoles, type Roles } from '../roles'
import { Card } from './ui/Card'

type Gate = keyof Pick<Roles, 'customer' | 'admin' | 'dev'>

/** Gates a subtree on sign-in, then on one role. The app-wide 401 handler lives in App, not here. */
export function RequireRole({ role, children }: { role: Gate; children: ReactNode }) {
  const auth = useAuth()
  const roles = useRoles()
  const location = useLocation()
  const signIn = useCallback(() => auth.signinRedirect({ state: location.pathname + location.search }), [auth, location])
  const needsLogin = !auth.isLoading && !auth.isAuthenticated && !auth.activeNavigator && !auth.error
  useEffect(() => {
    if (needsLogin) signIn()
  }, [needsLogin, signIn])

  if (auth.error) {
    return (
      <div className="p-8 text-sm text-bad">
        sign-in failed: {auth.error.message}{' '}
        <button onClick={signIn} className="underline">
          try again
        </button>
      </div>
    )
  }
  if (!auth.isAuthenticated) return <div className="p-8 text-sm text-muted">signing in…</div>
  if (!roles[role]) {
    return (
      <div className="p-8">
        <Card className="mx-auto max-w-sm space-y-3 text-center">
          <p className="text-sm text-muted">
            You need the <span className="font-mono text-fg">{role}</span> role for this page.
          </p>
          <Link to="/" className="text-sm text-brand-600 underline">
            Back home
          </Link>
        </Card>
      </div>
    )
  }
  return <>{children}</>
}
