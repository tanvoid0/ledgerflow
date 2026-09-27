import type { HTMLAttributes } from 'react'

/** The one surface primitive: hairline border, soft radius. Raise it with a shadow only where that's warranted (sticky panels, menus). */
export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={`rounded-card border border-line bg-surface p-4 ${className ?? ''}`} {...props} />
}
