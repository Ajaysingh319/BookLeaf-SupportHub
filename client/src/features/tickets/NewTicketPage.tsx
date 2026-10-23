import { useCallback, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { ErrorMessage } from '../../components/feedback.tsx'
import { api, toApiError, type ApiError } from '../../lib/api.ts'
import { useResource } from '../../lib/useResource.ts'
import { useConfig } from '../config/useConfig.ts'

export function NewTicketPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { validation } = useConfig()
  const loadBooks = useCallback((signal: AbortSignal) => api.listBooks(signal), [])
  const books = useResource(loadBooks)

  const [chosenBookId, setBookId] = useState(searchParams.get('book') ?? '')
  // A ?book= link with an id that is not one of the author's books falls back to General / Account Level.
  const bookId = books.data && !books.data.some((book) => book.id === chosenBookId) ? '' : chosenBookId
  const [subject, setSubject] = useState('')
  const [description, setDescription] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<ApiError | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // The same limits the server enforces, read from its config so the two can never disagree.
  const validate = (): Record<string, string> => {
    const problems: Record<string, string> = {}
    const subjectLength = subject.trim().length
    const descriptionLength = description.trim().length
    if (subjectLength < validation.subjectMin || subjectLength > validation.subjectMax) {
      problems.subject = `Subject must be between ${validation.subjectMin} and ${validation.subjectMax} characters.`
    }
    if (descriptionLength < validation.descriptionMin || descriptionLength > validation.descriptionMax) {
      problems.description = `Please describe your query in at least ${validation.descriptionMin} characters.`
    }
    return problems
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError(null)
    const problems = validate()
    setFieldErrors(problems)
    if (Object.keys(problems).length > 0) {
      const first = Object.keys(problems)[0]
      if (first) document.getElementById(first)?.focus()
      return
    }

    setSubmitting(true)
    try {
      const ticket = await api.createTicket({
        bookId: bookId === '' ? null : bookId,
        subject: subject.trim(),
        description: description.trim(),
      })
      navigate(`/author/tickets/${ticket.id}`, { state: { justCreated: true } })
    } catch (caught) {
      const apiError = toApiError(caught)
      const serverFieldErrors: Record<string, string> = {}
      apiError.details.forEach((detail) => {
        serverFieldErrors[detail.field] = detail.message
      })
      setFieldErrors(serverFieldErrors)
      setError(apiError)
      setSubmitting(false)
    }
  }

  return (
    <>
      <h1>Raise a support query</h1>
      <p className="muted">
        Tell us what you need help with. Our team replies here, and you will see the status change as we work on it.
      </p>

      {error && Object.keys(fieldErrors).length === 0 && <ErrorMessage error={error} />}

      <form className="card form-card" onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label htmlFor="bookId">Which book is this about?</label>
          <select id="bookId" value={bookId} onChange={(event) => setBookId(event.target.value)}>
            <option value="">General / Account Level</option>
            {(books.data ?? []).map((book) => (
              <option key={book.id} value={book.id}>
                {book.title}
              </option>
            ))}
          </select>
          {books.error && <p className="muted small">Your books could not be loaded; you can still choose General.</p>}
          {fieldErrors.bookId && <p className="field-error">{fieldErrors.bookId}</p>}
        </div>

        <div className="field">
          <label htmlFor="subject">Subject</label>
          <input
            id="subject"
            type="text"
            value={subject}
            onChange={(event) => setSubject(event.target.value)}
            maxLength={validation.subjectMax}
            aria-invalid={Boolean(fieldErrors.subject)}
            aria-describedby={fieldErrors.subject ? 'subject-error' : undefined}
            required
          />
          {fieldErrors.subject && (
            <p id="subject-error" className="field-error">
              {fieldErrors.subject}
            </p>
          )}
        </div>

        <div className="field">
          <label htmlFor="description">Details</label>
          <textarea
            id="description"
            rows={8}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
            maxLength={validation.descriptionMax}
            aria-invalid={Boolean(fieldErrors.description)}
            aria-describedby={fieldErrors.description ? 'description-error' : 'description-hint'}
            required
          />
          <p id="description-hint" className="muted small">
            Include dates, amounts or what you have already tried. {description.length}/{validation.descriptionMax}
          </p>
          {fieldErrors.description && (
            <p id="description-error" className="field-error">
              {fieldErrors.description}
            </p>
          )}
        </div>

        <div className="field">
          <label htmlFor="attachment">Attachment (optional)</label>
          <input id="attachment" type="file" disabled aria-describedby="attachment-note" />
          <p id="attachment-note" className="muted small">
            File uploads are not available yet. If photos help (for example a print defect), mention it above and our
            team will ask you to share them.
          </p>
        </div>

        <div className="form-actions">
          <button type="submit" className="button" disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit ticket'}
          </button>
          <Link className="button button-secondary" to="/author/tickets">
            Cancel
          </Link>
        </div>
      </form>
    </>
  )
}
