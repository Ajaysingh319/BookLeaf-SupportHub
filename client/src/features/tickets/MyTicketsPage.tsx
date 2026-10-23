import { useCallback, useState } from 'react'
import { Link } from 'react-router-dom'
import { EmptyState, ErrorMessage, Spinner, StatusChip } from '../../components/feedback.tsx'
import { api } from '../../lib/api.ts'
import { useResource } from '../../lib/useResource.ts'
import { useConfig, useFormatters } from '../config/useConfig.ts'

export function MyTicketsPage() {
  const { defaultPageSize, listPollMs } = useConfig()
  const { dateTime, time } = useFormatters()
  const [page, setPage] = useState(0)
  const load = useCallback(
    (signal: AbortSignal) => api.listTickets(page, defaultPageSize, signal),
    [page, defaultPageSize],
  )
  const tickets = useResource(load, listPollMs)

  const pagination = tickets.data?.pagination

  return (
    <>
      <div className="section-head">
        <h1>My Tickets</h1>
        <Link className="button" to="/author/tickets/new">
          New ticket
        </Link>
      </div>

      {tickets.loading && !tickets.data && <Spinner label="Loading your tickets…" />}
      {tickets.error && <ErrorMessage error={tickets.error} onRetry={tickets.reload} />}

      {tickets.data && tickets.data.data.length === 0 && (
        <EmptyState title="You have not raised any tickets" action={{ to: '/author/tickets/new', label: 'Raise a query' }}>
          Questions about royalties, ISBNs, printing quality or distribution go here, and replies from our team will
          appear on the ticket.
        </EmptyState>
      )}

      {tickets.data && tickets.data.data.length > 0 && (
        <>
          <ul className="list card">
            {tickets.data.data.map((ticket) => (
              <li key={ticket.id} className="list-row">
                <div>
                  <Link to={`/author/tickets/${ticket.id}`} className="row-title">
                    {ticket.subject}
                  </Link>
                  <p className="muted small">
                    {ticket.bookTitle ?? 'General / Account Level'} · Raised {dateTime(ticket.createdAt)}
                    {ticket.updatedAt !== ticket.createdAt && <> · Updated {dateTime(ticket.updatedAt)}</>}
                  </p>
                </div>
                <StatusChip status={ticket.status} />
              </li>
            ))}
          </ul>

          {pagination && pagination.totalPages > 1 && (
            <nav className="pager" aria-label="Ticket pages">
              <button
                type="button"
                className="button button-secondary"
                onClick={() => setPage((current) => Math.max(current - 1, 0))}
                disabled={page === 0}
              >
                Previous
              </button>
              <span className="muted">
                Page {pagination.page + 1} of {pagination.totalPages}
              </span>
              <button
                type="button"
                className="button button-secondary"
                onClick={() => setPage((current) => current + 1)}
                disabled={page + 1 >= pagination.totalPages}
              >
                Next
              </button>
            </nav>
          )}
          {tickets.lastUpdated && (
            <p className="muted small" aria-live="off">
              This list refreshes automatically. Last checked {time(tickets.lastUpdated)}.
            </p>
          )}
        </>
      )}
    </>
  )
}
