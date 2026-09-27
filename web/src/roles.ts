import { useAuth } from 'react-oidc-context'

export interface Roles {
  write: boolean
  read: boolean
  admin: boolean // write or read - either gets into /admin
  dev: boolean
  customer: boolean
  accountId: string | undefined // the token's `sub`; the account seed makes it equal the account id
  all: string[]
}

const EMPTY: Roles = { write: false, read: false, admin: false, dev: false, customer: false, accountId: undefined, all: [] }

/** Roles live in the access token's realm_access.roles, accountId in `sub`; no verification needed here, the gateway/services check the signature. */
export function decodeRoles(accessToken: string | undefined): Roles {
  if (!accessToken) return EMPTY
  try {
    const payload = JSON.parse(atob(accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    const roles: string[] = payload.realm_access?.roles ?? []
    const write = roles.includes('ledger-write')
    const read = roles.includes('account-read')
    return { write, read, admin: write || read, dev: roles.includes('dev'), customer: roles.includes('customer'), accountId: payload.sub, all: roles }
  } catch {
    return EMPTY
  }
}

export function useToken(): string | undefined {
  return useAuth().user?.access_token
}

export function useRoles(): Roles {
  const auth = useAuth()
  return decodeRoles(auth.user?.access_token)
}
