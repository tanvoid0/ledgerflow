import type { ButtonHTMLAttributes } from 'react'
import { useRoles } from '../roles'

/** A button that needs ledger-write: disabled with a tooltip explaining why, for reader tokens. */
export function WriteButton({ className, ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  const { write } = useRoles()
  return (
    <button
      {...props}
      disabled={!write || props.disabled}
      title={!write ? 'requires the ledger-write role' : props.title}
      className={`${className ?? ''} disabled:cursor-not-allowed disabled:opacity-50`}
    />
  )
}
