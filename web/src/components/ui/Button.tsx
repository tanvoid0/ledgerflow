import type { ButtonHTMLAttributes } from 'react'
import { Loader2 } from 'lucide-react'
import { useRoles } from '../../roles'

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger'
type Size = 'sm' | 'md' | 'lg'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-brand-600 text-white hover:bg-brand-500',
  secondary: 'border border-line bg-surface text-fg hover:bg-surface-2',
  ghost: 'text-fg hover:bg-surface-2',
  danger: 'bg-bad text-white hover:opacity-90',
}
const SIZES: Record<Size, string> = {
  sm: 'px-2.5 py-1 text-xs',
  md: 'px-4 py-2 text-sm',
  lg: 'px-5 py-2.5 text-base',
}

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  size?: Size
  loading?: boolean
}

/** The one clickable primitive: variant for weight, size for density, loading swaps in a spinner and disables. */
export function Button({ variant = 'primary', size = 'md', loading, disabled, className, children, ...props }: ButtonProps) {
  return (
    <button
      {...props}
      disabled={disabled || loading}
      className={`inline-flex items-center justify-center gap-1.5 rounded-control font-medium transition-colors duration-150 ease-out disabled:cursor-not-allowed disabled:opacity-50 ${VARIANTS[variant]} ${SIZES[size]} ${className ?? ''}`}
    >
      {loading && <Loader2 size={14} className="animate-spin" />}
      {children}
    </button>
  )
}

/** A Button that needs ledger-write: disabled with a tooltip explaining why, for reader tokens. */
export function WriteButton(props: ButtonProps) {
  const { write } = useRoles()
  return <Button {...props} disabled={!write || props.disabled} title={!write ? 'requires the ledger-write role' : props.title} />
}
