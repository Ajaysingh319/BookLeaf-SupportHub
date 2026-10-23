import { useState, type FormEvent } from 'react'
import { api, toApiError, type ApiError } from '../../lib/api.ts'
import type { TicketStatus } from '../../types/api.ts'
import { useConfig } from '../config/useConfig.ts'

/**
 * A follow-up message from the author. A resolved ticket reopens when they write; a closed one is final,
 * so the form is replaced by a note that points them to a new ticket (the server enforces the same rule).
 */
export function AuthorReplyForm({ ticketId, status, onSent }: { ticketId: string; status: TicketStatus; onSent: () => void }) {
  const { validation } = useConfig()
  const [body, setBody] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  if (status === 'CLOSED') {
    return (
      <div className="card muted">
        This ticket is closed, so you can no longer reply to it. If you need more help, please raise a new ticket.
      </div>
    )
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!body.trim()) return
    setSending(true)
    setError(null)
    try {
      await api.authorReply(ticketId, body.trim())
      setBody('')
      onSent()
    } catch (caught) {
      setError(toApiError(caught))
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="card" aria-labelledby="author-reply-heading">
      <h2 id="author-reply-heading">Add a message</h2>
      {status === 'RESOLVED' && (
        <p className="muted small">This ticket is marked resolved. If it is not, write here and we will reopen it.</p>
      )}
      <form onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label htmlFor="author-reply" className="visually-hidden">
            Your message
          </label>
          <textarea
            id="author-reply"
            rows={5}
            value={body}
            onChange={(event) => setBody(event.target.value)}
            maxLength={validation.messageMax}
            aria-describedby="author-reply-hint"
          />
          <p id="author-reply-hint" className="muted small">
            {body.length}/{validation.messageMax}
          </p>
        </div>
        {error && (
          <div className="banner banner-error" role="alert">
            <p>{error.message}</p>
          </div>
        )}
        <button type="submit" className="button" disabled={sending || !body.trim()}>
          {sending ? 'Sending…' : 'Send message'}
        </button>
      </form>
    </section>
  )
}
