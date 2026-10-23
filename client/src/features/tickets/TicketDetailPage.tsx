import { useCallback } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { EmptyState, ErrorMessage, Spinner, StatusChip } from '../../components/feedback.tsx'
import { AttachmentList } from '../../components/AttachmentList.tsx'
import { api, attachmentUrl } from '../../lib/api.ts'
import { useResource } from '../../lib/useResource.ts'
import { useConfig, useFormatters } from '../config/useConfig.ts'
import { AttachFiles } from './AttachFiles.tsx'
import { AuthorReplyForm } from './AuthorReplyForm.tsx'

export function TicketDetailPage() {
  const { id = '' } = useParams()
  const location = useLocation()
  const { ticketPollMs } = useConfig()
  const { dateTime, time } = useFormatters()
  const arrival = location.state as { justCreated?: boolean; failedUploads?: string[] } | null
  const justCreated = arrival?.justCreated === true
  const failedUploads = arrival?.failedUploads ?? []

  // TDD section 9: short polling while the page is open and visible (interval from server config).
  const load = useCallback((signal: AbortSignal) => api.getTicket(id, signal), [id])
  const ticket = useResource(load, ticketPollMs)

  if (ticket.loading && !ticket.data) return <Spinner label="Loading your ticket…" />

  if (!ticket.data) {
    if (ticket.error?.status === 404) {
      return (
        <EmptyState title="Ticket not found" action={{ to: '/author/tickets', label: 'Back to My Tickets' }}>
          This ticket does not exist or is not yours.
        </EmptyState>
      )
    }
    return ticket.error ? <ErrorMessage error={ticket.error} onRetry={ticket.reload} /> : null
  }

  const data = ticket.data
  const pollSeconds = Math.round(ticketPollMs / 1000)
  return (
    <>
      <p>
        <Link to="/author/tickets">← My Tickets</Link>
      </p>

      {justCreated && (
        <div className="banner banner-success" role="status">
          Your ticket has been submitted. Our team will reply here, and this page updates automatically.
        </div>
      )}
      {failedUploads.length > 0 && (
        <div className="banner banner-warning" role="alert">
          <p>Your ticket was submitted, but some files could not be attached. You can add them again below.</p>
          {failedUploads.map((failure) => (
            <p key={failure}>{failure}</p>
          ))}
        </div>
      )}
      {ticket.error && (
        <div className="banner banner-warning" role="status">
          <p>We could not refresh this ticket just now. We will keep trying.</p>
        </div>
      )}

      <header className="ticket-head">
        <h1>{data.subject}</h1>
        <StatusChip status={data.status} />
      </header>
      <p className="muted">
        {data.bookTitle ?? 'General / Account Level'} · Raised {dateTime(data.createdAt)}
      </p>

      <section className="card" aria-labelledby="query-heading">
        <h2 id="query-heading">Your query</h2>
        <p className="prewrap">{data.description}</p>
        <AttachmentList attachments={data.attachments} hrefFor={(file) => attachmentUrl('author', data.id, file.id)} />
      </section>

      <section aria-labelledby="replies-heading">
        <h2 id="replies-heading">Conversation</h2>
        {data.messages.length === 0 ? (
          <div className="card muted">
            No reply yet. Our team will respond here as soon as they can, and you do not need to refresh this page.
          </div>
        ) : (
          <ul className="messages" aria-live="polite">
            {data.messages.map((message) => (
              <li key={message.id} className={`card message ${message.senderRole === 'AUTHOR' ? 'message-own' : ''}`}>
                <p className="message-meta">
                  {message.senderRole === 'AUTHOR' ? (
                    <strong>You</strong>
                  ) : (
                    <>
                      <strong>{message.senderName}</strong> · Support team
                    </>
                  )}{' '}
                  · {dateTime(message.createdAt)}
                </p>
                <p className="prewrap">{message.body}</p>
              </li>
            ))}
          </ul>
        )}
      </section>

      <AuthorReplyForm ticketId={data.id} status={data.status} onSent={ticket.reload} />
      <AttachFiles ticketId={data.id} status={data.status} attachedCount={data.attachments.length} onDone={ticket.reload} />

      {ticket.lastUpdated && (
        <p className="muted small">
          Updated at {time(ticket.lastUpdated)} · checks for new replies every {pollSeconds} seconds
        </p>
      )}
    </>
  )
}
