import { useAuth } from 'react-oidc-context'

export interface Roles {
  write: boolean
  read: boolean
  all: string[]
}

/** Roles live in the access token's realm_access.roles; no verification needed here, the gateway/services check the signature. */
export function decodeRoles(accessToken: string | undefined): Roles {
  if (!accessToken) return { write: false, read: false, all: [] }
  try {
    const payload = JSON.parse(atob(accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    const roles: string[] = payload.realm_access?.roles ?? []
    return { write: roles.includes('ledger-write'), read: roles.includes('account-read'), all: roles }
  } catch {
    return { write: false, read: false, all: [] }
  }
}

export function useToken(): string | undefined {
  return useAuth().user?.access_token
}

export function useRoles(): Roles {
  const auth = useAuth()
  return decodeRoles(auth.user?.access_token)
}
