import type { HTMLAttributes, TdHTMLAttributes, ThHTMLAttributes } from 'react'

export function Table({ className, ...props }: HTMLAttributes<HTMLTableElement>) {
  return <table {...props} className={`w-full text-sm ${className ?? ''}`} />
}
export function THead({ className, ...props }: HTMLAttributes<HTMLTableSectionElement>) {
  return <thead {...props} className={`sticky top-0 bg-surface text-left text-xs text-muted ${className ?? ''}`} />
}
export function TR({ className, ...props }: HTMLAttributes<HTMLTableRowElement>) {
  return <tr {...props} className={`border-t border-line hover:bg-surface-2 ${className ?? ''}`} />
}
export function TH({ className, ...props }: ThHTMLAttributes<HTMLTableCellElement>) {
  return <th {...props} className={`py-1.5 font-medium ${className ?? ''}`} />
}
export function TD({ className, ...props }: TdHTMLAttributes<HTMLTableCellElement>) {
  return <td {...props} className={`py-2 ${className ?? ''}`} />
}
