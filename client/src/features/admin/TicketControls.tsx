import { useState } from 'react'
import { api, toApiError, type ApiError } from '../../lib/api.ts'
import { labelFor } from '../../lib/format.ts'
import type { AdminTicketDetail, AdminTicketPatch } from '../../types/api.ts'
import { useAuth } from '../auth/useAuth.ts'
import { useConfig } from '../config/useConfig.ts'

/**
 * Category, priority and status overrides plus assignment. The parent re-creates this component
 * (via `key`) whenever the saved values change, so the form always starts from what the server holds.
 */
export function TicketControls({ detail, onChanged }: { detail: AdminTicketDetail; onChanged: () => void }) {
  const { user } = useAuth()
  const { options } = useConfig()
  const summary = detail.summary

  const [category, setCategory] = useState(summary.category)
  const [priority, setPriority] = useState(summary.priority)
  const [status, setStatus] = useState<string>(summary.status)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)
  const [saved, setSaved] = useState(false)

  // Only moves the server allows are offered (the same table the API enforces).
  const allowedNext = options.statuses.find((option) => option.value === summary.status)?.allowedNext ?? []
  const statusChoices = [summary.status as string, ...allowedNext]
  const changed = category !== summary.category || priority !== summary.priority || status !== summary.status
  const assignedToMe = user !== null && summary.assignedAdminId === user.id

  const send = async (patch: AdminTicketPatch) => {
    setSaving(true)
    setError(null)
    setSaved(false)
    try {
      await api.adminUpdateTicket(summary.id, patch)
      setSaved(true)
      onChanged()
    } catch (caught) {
      setError(toApiError(caught))
    } finally {
      setSaving(false)
    }
  }

  const save = () => {
    const patch: AdminTicketPatch = {}
    if (category !== summary.category) patch.category = category
    if (priority !== summary.priority) patch.priority = priority
    if (status !== summary.status) patch.status = status
    void send(patch)
  }

  return (
    <section className="card" aria-labelledby="controls-heading">
      <h2 id="controls-heading">Manage ticket</h2>

      <div className="field">
        <label htmlFor="c-category">Category</label>
        <select id="c-category" value={category} onChange={(event) => setCategory(event.target.value)}>
          {options.categories.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="c-priority">Priority</label>
        <select id="c-priority" value={priority} onChange={(event) => setPriority(event.target.value)}>
          {options.priorities.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      <div className="field">
        <label htmlFor="c-status">Status</label>
        <select id="c-status" value={status} onChange={(event) => setStatus(event.target.value)}>
          {statusChoices.map((value) => (
            <option key={value} value={value}>
              {labelFor(options.statuses, value)}
              {value === summary.status ? ' (current)' : ''}
            </option>
          ))}
        </select>
      </div>

      {error && (
        <div className="banner banner-error" role="alert">
          <p>{error.message}</p>
          {error.details.map((detailItem) => (
            <p key={detailItem.field + detailItem.message} className="small">
              {detailItem.message}
            </p>
          ))}
        </div>
      )}
      {saved && !changed && (
        <p className="muted small" role="status">
          Saved.
        </p>
      )}

      <div className="form-actions">
        <button type="button" className="button" onClick={save} disabled={!changed || saving}>
          {saving ? 'Saving…' : 'Save changes'}
        </button>
      </div>

      <h3>Assignment</h3>
      <p>
        {summary.assignedAdminName ? (
          <>
            Assigned to <strong>{summary.assignedAdminName}</strong>
            {assignedToMe ? ' (you)' : ''}
          </>
        ) : (
          <span className="muted">Not assigned yet</span>
        )}
      </p>
      {user && !assignedToMe && (
        <button type="button" className="button button-secondary" onClick={() => void send({ assignedAdminId: user.id })} disabled={saving}>
          Assign to me
        </button>
      )}
    </section>
  )
}
