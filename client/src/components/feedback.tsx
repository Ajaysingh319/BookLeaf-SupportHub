import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { useConfig } from '../features/config/useConfig.ts'
import { ApiError } from '../lib/api.ts'
import { labelFor } from '../lib/format.ts'
import type { TicketStatus } from '../types/api.ts'

export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="state" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <span>{label}</span>
    </div>
  )
}

/** A failed load, with a retry button. The request id (when the server sent one) helps support trace it. */
export function ErrorMessage({ error, onRetry }: { error: ApiError; onRetry?: () => void }) {
  return (
    <div className="banner banner-error" role="alert">
      <p>{error.message}</p>
      {error.requestId && <p className="muted small">Reference: {error.requestId}</p>}
      {onRetry && (
        <button type="button" className="button button-secondary" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  )
}

export function EmptyState({
  title,
  children,
  action,
}: {
  title: string
  children?: ReactNode
  action?: { to: string; label: string }
}) {
  return (
    <div className="state empty">
      <h2>{title}</h2>
      {children && <p className="muted">{children}</p>}
      {action && (
        <Link className="button" to={action.to}>
          {action.label}
        </Link>
      )}
    </div>
  )
}

/** Status is shown as text as well as colour, so it never relies on colour alone. */
export function StatusChip({ status }: { status: TicketStatus }) {
  const { options } = useConfig()
  return (
    <span className={`chip chip-${status.toLowerCase().replace('_', '-')}`}>{labelFor(options.statuses, status)}</span>
  )
}

/** Priority label from the server options, with the value in the class name for colour. */
export function PriorityChip({ priority }: { priority: string }) {
  const { options } = useConfig()
  return (
    <span className={`chip chip-priority-${priority.toLowerCase()}`}>{labelFor(options.priorities, priority)}</span>
  )
}
