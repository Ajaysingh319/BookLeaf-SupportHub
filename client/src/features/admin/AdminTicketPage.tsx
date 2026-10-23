import { useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'
import { EmptyState, ErrorMessage, PriorityChip, Spinner, StatusChip } from '../../components/feedback.tsx'
import { api } from '../../lib/api.ts'
import { formatAge, labelFor } from '../../lib/format.ts'
import { useResource } from '../../lib/useResource.ts'
import type { AdminTicketDetail } from '../../types/api.ts'
import { useConfig, useFormatters } from '../config/useConfig.ts'
import { NoteForm } from './NoteForm.tsx'
import { ReplyComposer } from './ReplyComposer.tsx'
import { TicketControls } from './TicketControls.tsx'

function AiPanel({ detail }: { detail: AdminTicketDetail }) {
  const { options } = useConfig()
  const { ai } = detail
  return (
    <section className="card" aria-labelledby="ai-heading">
      <h2 id="ai-heading">AI triage</h2>
      {ai.outcome === 'PENDING' && <p className="muted">AI triage is still running. This updates by itself.</p>}
      {ai.outcome === 'SUCCESS' && (
        <>
          <p>
            Suggested: <strong>{labelFor(options.categories, ai.suggestedCategory)}</strong> ·{' '}
            <strong>{labelFor(options.priorities, ai.suggestedPriority)}</strong>
          </p>
          {ai.reason && <p className="muted small">{ai.reason}</p>}
          <p className="muted small">
            The category and priority above in “Manage ticket” start from this suggestion and can be changed.
          </p>
        </>
      )}
      {ai.outcome === 'FALLBACK' && (
        <div className="banner banner-warning" role="status">
          <p>
            <strong>AI unavailable; manual review needed.</strong>
          </p>
          <p className="small">
            The category and priority were set by simple rules, not by the AI. Please check them.
          </p>
        </div>
      )}
    </section>
  )
}

export function AdminTicketPage() {
  const { id = '' } = useParams()
  const { ticketPollMs } = useConfig()
  const { dateTime, money, count, date, time } = useFormatters()

  // Polled so a second admin's reply, or a late AI result, shows up. The forms keep their own state.
  const load = useCallback((signal: AbortSignal) => api.adminGetTicket(id, signal), [id])
  const ticket = useResource(load, ticketPollMs)

  if (ticket.loading && !ticket.data) return <Spinner label="Loading ticket…" />
  if (!ticket.data) {
    if (ticket.error?.status === 404) {
      return (
        <EmptyState title="Ticket not found" action={{ to: '/admin/tickets', label: 'Back to the queue' }}>
          This ticket does not exist.
        </EmptyState>
      )
    }
    return ticket.error ? <ErrorMessage error={ticket.error} onRetry={ticket.reload} /> : null
  }

  const detail = ticket.data
  const summary = detail.summary
  const book = detail.book
  // Changes whenever the saved values change, so the form below resets to what the server holds.
  const controlsKey = [summary.status, summary.category, summary.priority, summary.assignedAdminId].join('|')

  return (
    <>
      <p>
        <Link to="/admin/tickets">← Ticket queue</Link>
      </p>

      {ticket.error && (
        <div className="banner banner-warning" role="status">
          <p>We could not refresh this ticket just now. We will keep trying.</p>
        </div>
      )}

      <header className="ticket-head">
        <h1>{summary.subject}</h1>
        <StatusChip status={summary.status} />
        <PriorityChip priority={summary.priority} />
      </header>
      <p className="muted">
        Raised {dateTime(summary.createdAt)} · waiting {formatAge(summary.ageHours)} ·{' '}
        {detail.repliedAt ? `last reply sent ${dateTime(detail.repliedAt)}` : 'no reply sent yet'}
      </p>

      <div className="admin-layout">
        <div className="admin-main">
          <section className="card" aria-labelledby="query-heading">
            <h2 id="query-heading">Author’s query</h2>
            <p className="prewrap">{detail.description}</p>
          </section>

          <section aria-labelledby="conversation-heading">
            <h2 id="conversation-heading">Conversation</h2>
            {detail.messages.length === 0 ? (
              <div className="card muted">No replies or notes yet.</div>
            ) : (
              <ul className="messages">
                {detail.messages.map((message) => (
                  <li
                    key={message.id}
                    className={`card message ${message.visibility === 'INTERNAL' ? 'message-internal' : ''}`}
                  >
                    <p className="message-meta">
                      <strong>{message.senderName}</strong> · {dateTime(message.createdAt)}{' '}
                      <span className={`chip ${message.visibility === 'INTERNAL' ? 'chip-internal' : 'chip-public'}`}>
                        {message.visibility === 'INTERNAL' ? 'Internal note (not visible to author)' : 'Reply sent to author'}
                      </span>
                    </p>
                    <p className="prewrap">{message.body}</p>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <ReplyComposer summary={summary} onSent={ticket.reload} />
          <NoteForm ticketId={summary.id} onAdded={ticket.reload} />
        </div>

        <aside className="admin-side">
          <TicketControls key={controlsKey} detail={detail} onChanged={ticket.reload} />
          <AiPanel detail={detail} />

          <section className="card" aria-labelledby="author-heading">
            <h2 id="author-heading">Author</h2>
            <p>
              <strong>{detail.author.name}</strong>
              <br />
              <a href={`mailto:${detail.author.email}`}>{detail.author.email}</a>
              {detail.author.city && (
                <>
                  <br />
                  <span className="muted">{detail.author.city}</span>
                </>
              )}
            </p>
          </section>

          <section className="card" aria-labelledby="book-heading">
            <h2 id="book-heading">Book</h2>
            {book === null ? (
              <p className="muted">General / Account Level: no book selected.</p>
            ) : (
              <>
                <p>
                  <strong>{book.title}</strong>
                  <br />
                  <span className="muted">
                    {book.genre} · {book.status}
                  </span>
                </p>
                <dl className="facts facts-compact">
                  <div>
                    <dt>ISBN</dt>
                    <dd>{book.isbn}</dd>
                  </div>
                  <div>
                    <dt>Published</dt>
                    <dd>{book.publicationDate ? date(book.publicationDate) : 'Not yet'}</dd>
                  </div>
                  <div>
                    <dt>MRP</dt>
                    <dd>{money(book.mrp)}</dd>
                  </div>
                  <div>
                    <dt>Copies sold</dt>
                    <dd>{count(book.totalCopiesSold)}</dd>
                  </div>
                  <div>
                    <dt>Royalty earned</dt>
                    <dd>{money(book.totalRoyaltyEarned)}</dd>
                  </div>
                  <div>
                    <dt>Royalty paid</dt>
                    <dd>{money(book.royaltyPaid)}</dd>
                  </div>
                  <div>
                    <dt>Royalty pending</dt>
                    <dd className="emphasis">{money(book.royaltyPending)}</dd>
                  </div>
                  <div>
                    <dt>Last payout</dt>
                    <dd>{book.lastRoyaltyPayoutDate ? date(book.lastRoyaltyPayoutDate) : 'None yet'}</dd>
                  </div>
                  <div>
                    <dt>Print partner</dt>
                    <dd>{book.printPartner ?? '—'}</dd>
                  </div>
                  <div>
                    <dt>Listed on</dt>
                    <dd>{book.availableOn.length > 0 ? book.availableOn.join(', ') : 'Not listed yet'}</dd>
                  </div>
                </dl>
              </>
            )}
          </section>

          {ticket.lastUpdated && <p className="muted small">Updated at {time(ticket.lastUpdated)}</p>}
        </aside>
      </div>
    </>
  )
}
