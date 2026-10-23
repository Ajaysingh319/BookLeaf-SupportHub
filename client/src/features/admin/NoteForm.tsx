import { useState, type FormEvent } from 'react'
import { api, toApiError, type ApiError } from '../../lib/api.ts'
import { useConfig } from '../config/useConfig.ts'

/** Internal notes are for the team only: authors never receive them. */
export function NoteForm({ ticketId, onAdded }: { ticketId: string; onAdded: () => void }) {
  const { validation } = useConfig()
  const [body, setBody] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!body.trim()) return
    setSaving(true)
    setError(null)
    try {
      await api.adminAddNote(ticketId, body.trim())
      setBody('')
      onAdded()
    } catch (caught) {
      setError(toApiError(caught))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="card" aria-labelledby="note-heading">
      <h2 id="note-heading">Internal note</h2>
      <p className="muted small">Only the support team can see notes. The author never does.</p>
      <form onSubmit={handleSubmit} noValidate>
        <div className="field">
          <label htmlFor="note-body" className="visually-hidden">
            Internal note
          </label>
          <textarea
            id="note-body"
            rows={4}
            value={body}
            onChange={(event) => setBody(event.target.value)}
            maxLength={validation.messageMax}
          />
        </div>
        {error && (
          <div className="banner banner-error" role="alert">
            <p>{error.message}</p>
          </div>
        )}
        <button type="submit" className="button button-secondary" disabled={saving || !body.trim()}>
          {saving ? 'Adding…' : 'Add note'}
        </button>
      </form>
    </section>
  )
}
