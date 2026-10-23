import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { EmptyState, ErrorMessage, Spinner, StatusChip } from '../../components/feedback.tsx'
import { api } from '../../lib/api.ts'
import { firstName } from '../../lib/format.ts'
import { useResource } from '../../lib/useResource.ts'
import { useAuth } from '../auth/useAuth.ts'
import { useConfig, useFormatters } from '../config/useConfig.ts'

export function AuthorHomePage() {
  const { user } = useAuth()
  const { listPollMs, dashboardRecentTickets } = useConfig()
  const { money, date } = useFormatters()
  const loadBooks = useCallback((signal: AbortSignal) => api.listBooks(signal), [])
  const loadTickets = useCallback(
    (signal: AbortSignal) => api.listTickets(0, dashboardRecentTickets, signal),
    [dashboardRecentTickets],
  )
  const books = useResource(loadBooks)
  const tickets = useResource(loadTickets, listPollMs)

  const pendingRoyalty = (books.data ?? []).reduce((sum, book) => sum + (book.royaltyPending ?? 0), 0)
  const unresolved = (tickets.data?.data ?? []).filter((t) => t.status === 'OPEN' || t.status === 'IN_PROGRESS').length

  return (
    <>
      <h1>Welcome{user ? `, ${firstName(user.name)}` : ''}</h1>
      <p className="muted">Here is where your books and support requests stand.</p>

      <section className="summary-grid" aria-label="Summary">
        <Link className="card summary-card" to="/author/books">
          <span className="summary-label">Your books</span>
          <span className="summary-value">{books.data ? books.data.length : '—'}</span>
          <span className="muted small">
            {books.data ? `${money(pendingRoyalty)} royalty pending` : 'View royalties and status'}
          </span>
        </Link>
        <Link className="card summary-card" to="/author/tickets">
          <span className="summary-label">Open tickets</span>
          <span className="summary-value">{tickets.data ? unresolved : '—'}</span>
          <span className="muted small">Track replies from our team</span>
        </Link>
        <Link className="card summary-card summary-action" to="/author/tickets/new">
          <span className="summary-label">Need help?</span>
          <span className="summary-value summary-cta">Raise a query</span>
          <span className="muted small">Royalties, ISBN, printing and more</span>
        </Link>
      </section>

      {books.error && <ErrorMessage error={books.error} onRetry={books.reload} />}

      <section aria-labelledby="recent-heading">
        <div className="section-head">
          <h2 id="recent-heading">Recent tickets</h2>
          <Link to="/author/tickets">View all</Link>
        </div>
        {tickets.loading && !tickets.data && <Spinner label="Loading your tickets…" />}
        {tickets.error && !tickets.data && <ErrorMessage error={tickets.error} onRetry={tickets.reload} />}
        {tickets.data && tickets.data.data.length === 0 && (
          <EmptyState title="No tickets yet" action={{ to: '/author/tickets/new', label: 'Raise your first query' }}>
            When you have a question about royalties, ISBNs, printing or distribution, raise a ticket and we will reply
            here.
          </EmptyState>
        )}
        {tickets.data && tickets.data.data.length > 0 && (
          <ul className="list card">
            {tickets.data.data.map((ticket) => (
              <li key={ticket.id} className="list-row">
                <div>
                  <Link to={`/author/tickets/${ticket.id}`} className="row-title">
                    {ticket.subject}
                  </Link>
                  <p className="muted small">
                    {ticket.bookTitle ?? 'General / Account Level'} · Raised {date(ticket.createdAt.slice(0, 10))}
                  </p>
                </div>
                <StatusChip status={ticket.status} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}
