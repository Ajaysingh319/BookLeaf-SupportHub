import { useState, type FormEvent } from 'react'
import { api, toApiError, type ApiError } from '../../lib/api.ts'
import { labelFor } from '../../lib/format.ts'
import type { AdminTicketSummary } from '../../types/api.ts'
import { useConfig } from '../config/useConfig.ts'

/**
 * Writes the public reply. An AI draft only fills the box: the admin reads and edits it, then sends
 * it themselves. Nothing is ever sent automatically, and if the AI is unavailable the box simply
 * stays available for a manual reply.
 */
export function ReplyComposer({ summary, onSent }: { summary: AdminTicketSummary; onSent: () => void }) {
  const { options, validation } = useConfig()
  const [body, setBody] = useState('')
  const [nextStatus, setNextStatus] = useState('')
  const [drafting, setDrafting] = useState(false)
  const [fromAi, setFromAi] = useState(false)
  const [draftError, setDraftError] = useState<ApiError | null>(null)
  const [sendError, setSendError] = useState<ApiError | null>(null)
  const [sending, setSending] = useState(false)

  const allowedNext = options.statuses.find((option) => option.value === summary.status)?.allowedNext ?? []

  const generateDraft = async () => {
    if (body.trim() && !window.confirm('Replace the text in the reply box with an AI draft?')) return
    setDrafting(true)
    setDraftError(null)
    try {
      const result = await api.adminDraft(summary.id)
      setBody(result.draft)
      setFromAi(true)
    } catch (caught) {
      setDraftError(toApiError(caught))
    } finally {
      setDrafting(false)
    }
  }

  const handleSend = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!body.trim()) return
    setSending(true)
    setSendError(null)
    try {
      await api.adminReply(summary.id, body.trim(), nextStatus || undefined)
      setBody('')
      setNextStatus('')
      setFromAi(false)
      onSent()
    } catch (caught) {
      setSendError(toApiError(caught))
    } finally {
      setSending(false)
    }
  }

  return (
    <section className="card" aria-labelledby="reply-heading">
      <h2 id="reply-heading">Reply to the author</h2>
      <p className="muted small">The author sees this reply on their ticket. Internal notes are separate.</p>

      <form onSubmit={handleSend} noValidate>
        <div className="field">
          <div className="field-head">
            <label htmlFor="reply-body">Your reply</label>
            <button type="button" className="button button-secondary" onClick={generateDraft} disabled={drafting || sending}>
              {drafting ? 'Generating draft…' : 'Generate AI draft'}
            </button>
          </div>
          <textarea
            id="reply-body"
            rows={10}
            value={body}
            onChange={(event) => setBody(event.target.value)}
            maxLength={validation.messageMax}
            aria-describedby="reply-hint"
          />
          <p id="reply-hint" className="muted small">
            {fromAi
              ? 'AI draft: read it, correct anything that is not right, then send. It has not been sent.'
              : 'Write your reply, or generate an AI draft to start from.'}{' '}
            {body.length}/{validation.messageMax}
          </p>
        </div>

        {draftError && (
          <div className="banner banner-info" role="status">
            <p>{draftError.message}</p>
          </div>
        )}
        {sendError && (
          <div className="banner banner-error" role="alert">
            <p>{sendError.message}</p>
            {sendError.details.map((detail) => (
              <p key={detail.field + detail.message} className="small">
                {detail.message}
              </p>
            ))}
          </div>
        )}

        <div className="field">
          <label htmlFor="reply-status">After sending</label>
          <select id="reply-status" value={nextStatus} onChange={(event) => setNextStatus(event.target.value)}>
            <option value="">Keep status ({labelFor(options.statuses, summary.status)})</option>
            {allowedNext.map((value) => (
              <option key={value} value={value}>
                Set to {labelFor(options.statuses, value)}
              </option>
            ))}
          </select>
        </div>

        <button type="submit" className="button" disabled={sending || !body.trim()}>
          {sending ? 'Sending…' : 'Send reply'}
        </button>
      </form>
    </section>
  )
}
