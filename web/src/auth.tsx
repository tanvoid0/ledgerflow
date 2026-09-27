import { AuthProvider } from 'react-oidc-context'
import type { ReactNode } from 'react'
import { WebStorageStateStore } from 'oidc-client-ts'

const authority = import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8180/realms/ledgerflow'
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID ?? 'ledgerflow-web'

export function LedgerflowAuthProvider({ children }: { children: ReactNode }) {
  return (
    <AuthProvider
      authority={authority}
      client_id={clientId}
      redirect_uri={window.location.origin + '/'}
      scope="openid"
      automaticSilentRenew
      userStore={new WebStorageStateStore({ store: window.sessionStorage })}
      // back to the page that sent us to login (Gate passes it as state), minus the code/state params; runs before the router mounts
      onSigninCallback={(user) =>
        window.history.replaceState({}, document.title, typeof user?.state === 'string' ? user.state : '/')
      }
    >
      {children}
    </AuthProvider>
  )
}
