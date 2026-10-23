import { useCallback } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { EmptyState, ErrorMessage, PriorityChip, Spinner, StatusChip } from '../../components/feedback.tsx'
import { api } from '../../lib/api.ts'
import { formatAge, labelFor } from '../../lib/format.ts'
import { useResource } from '../../lib/useResource.ts'
import type { AdminTicketSummary, QueueFilters } from '../../types/api.ts'
import { useConfig, useFormatters } from '../config/useConfig.ts'

const FILTER_KEYS = ['status', 'category', 'priority', 'from', 'to'] as const

/** "Unresolved" tickets are the ones an admin still needs to act on. */
function isUnresolved(ticket: AdminTicketSummary): boolean {
  return ticket.status === 'OPEN' || ticket.status === 'IN_PROGRESS'
}

export function AdminQueuePage() {
  const { options, defaultPageSize, listPollMs, staleTicketHours } = useConfig()
  const { dateTime, time } = useFormatters()
  // Filters and the page live in the URL, so a filtered queue can be bookmarked, shared and survives the Back button.
  const [params, setParams] = useSearchParams()
  const filterKey = FILTER_KEYS.map((key) => params.get(key) ?? '').join('|')
  const page = Math.max(Number(params.get('page') ?? '0') || 0, 0)

  const loadStats = useCallback((signal: AbortSignal) => api.adminStats(signal), [])
  const stats = useResource(loadStats, listPollMs)

  const loadTickets = useCallback(
    (signal: AbortSignal) => {
      const filters: QueueFilters = {}
      FILTER_KEYS.forEach((key) => {
        const value = params.get(key)
        if (value) filters[key] = value
      })
      return api.adminListTickets(filters, page, defaultPageSize, signal)
    },
    // filterKey stands in for `params`, so the callback only changes when a filter or the page does.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [filterKey, page, defaultPageSize],
  )
  const tickets = useResource(loadTickets, listPollMs)

  const setFilter = (key: (typeof FILTER_KEYS)[number], value: string) => {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value)
    else next.delete(key)
    next.delete('page')
    setParams(next, { replace: true })
  }
  const setPage = (value: number) => {
    const next = new URLSearchParams(params)
    if (value > 0) next.set('page', String(value))
    else next.delete('page')
    setParams(next)
  }
  const clearFilters = () => setParams(new URLSearchParams(), { replace: true })
  const hasFilters = FILTER_KEYS.some((key) => params.get(key))

  const unresolvedTotal = stats.data
    ? (stats.data.byStatus.OPEN ?? 0) + (stats.data.byStatus.IN_PROGRESS ?? 0)
    : null
  const pagination = tickets.data?.pagination

  return (
    <>
      <div className="section-head">
        <h1>Ticket Queue</h1>
        <button type="button" className="button button-secondary" onClick={() => { stats.reload(); tickets.reload() }}>
          Refresh
        </button>
      </div>
      <p className="muted">
        Unresolved tickets come first, most urgent priority first, then oldest first.
      </p>

      <section className="summary-grid" aria-label="Queue summary">
        <div className="card summary-card">
          <span className="summary-label">Unresolved</span>
          <span className="summary-value">{unresolvedTotal ?? '—'}</span>
          <span className="muted small">
            {stats.data ? `${stats.data.unassignedUnresolved} unassigned` : 'Open and in progress'}
          </span>
        </div>
        {options.priorities.map((priority) => (
          <div className="card summary-card" key={priority.value}>
            <span className="summary-label">{priority.label} priority</span>
            <span className="summary-value">{stats.data ? (stats.data.unresolvedByPriority[priority.value] ?? 0) : '—'}</span>
            <span className="muted small">unresolved</span>
          </div>
        ))}
      </section>
      {stats.error && !stats.data && <ErrorMessage error={stats.error} onRetry={stats.reload} />}

      <form className="card filters" aria-label="Filter tickets" onSubmit={(event) => event.preventDefault()}>
        <div className="field">
          <label htmlFor="f-status">Status</label>
          <select id="f-status" value={params.get('status') ?? ''} onChange={(e) => setFilter('status', e.target.value)}>
            <option value="">All statuses</option>
            {options.statuses.map((status) => (
              <option key={status.value} value={status.value}>
                {status.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-category">Category</label>
          <select id="f-category" value={params.get('category') ?? ''} onChange={(e) => setFilter('category', e.target.value)}>
            <option value="">All categories</option>
            {options.categories.map((category) => (
              <option key={category.value} value={category.value}>
                {category.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-priority">Priority</label>
          <select id="f-priority" value={params.get('priority') ?? ''} onChange={(e) => setFilter('priority', e.target.value)}>
            <option value="">All priorities</option>
            {options.priorities.map((priority) => (
              <option key={priority.value} value={priority.value}>
                {priority.label}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label htmlFor="f-from">Raised from</label>
          <input id="f-from" type="date" value={params.get('from') ?? ''} onChange={(e) => setFilter('from', e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="f-to">Raised to</label>
          <input id="f-to" type="date" value={params.get('to') ?? ''} onChange={(e) => setFilter('to', e.target.value)} />
        </div>
        <div className="field filter-reset">
          <button type="button" className="button button-secondary" onClick={clearFilters} disabled={!hasFilters}>
            Clear filters
          </button>
        </div>
      </form>

      {tickets.loading && !tickets.data && <Spinner label="Loading tickets…" />}
      {tickets.error && <ErrorMessage error={tickets.error} onRetry={tickets.reload} />}

      {tickets.data && tickets.data.data.length === 0 && (
        <EmptyState title={hasFilters ? 'No tickets match these filters' : 'No tickets yet'}>
          {hasFilters ? 'Try removing a filter to see more tickets.' : 'New tickets from authors will appear here.'}
        </EmptyState>
      )}

      {tickets.data && tickets.data.data.length > 0 && (
        <>
          <div className="table-wrap">
            <table className="queue-table">
              <thead>
                <tr>
                  <th scope="col">Priority</th>
                  <th scope="col">Ticket</th>
                  <th scope="col">Author</th>
                  <th scope="col">Category</th>
                  <th scope="col">Status</th>
                  <th scope="col">Assigned to</th>
                  <th scope="col">Waiting</th>
                </tr>
              </thead>
              <tbody>
                {tickets.data.data.map((ticket) => {
                  const stale = isUnresolved(ticket) && ticket.ageHours >= staleTicketHours
                  return (
                    <tr key={ticket.id} className={stale ? 'row-stale' : undefined}>
                      <td data-label="Priority">
                        <PriorityChip priority={ticket.priority} />
                      </td>
                      <td data-label="Ticket">
                        <Link to={`/admin/tickets/${ticket.id}`} className="row-title">
                          {ticket.subject}
                        </Link>
                        <p className="muted small">
                          {ticket.bookTitle ?? 'General / Account Level'}
                          {ticket.needsManualReview && <span className="chip chip-review">Needs manual review</span>}
                        </p>
                      </td>
                      <td data-label="Author">{ticket.authorName}</td>
                      <td data-label="Category">{labelFor(options.categories, ticket.category)}</td>
                      <td data-label="Status">
                        <StatusChip status={ticket.status} />
                      </td>
                      <td data-label="Assigned to">{ticket.assignedAdminName ?? <span className="muted">Unassigned</span>}</td>
                      <td data-label="Waiting">
                        <span title={`Raised ${dateTime(ticket.createdAt)}`}>{formatAge(ticket.ageHours)}</span>
                        {stale && <span className="chip chip-stale">Overdue</span>}
                        <p className="muted small">{dateTime(ticket.createdAt)}</p>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>

          {pagination && pagination.totalPages > 1 && (
            <nav className="pager" aria-label="Queue pages">
              <button type="button" className="button button-secondary" onClick={() => setPage(page - 1)} disabled={page === 0}>
                Previous
              </button>
              <span className="muted">
                Page {pagination.page + 1} of {pagination.totalPages} · {pagination.totalItems} tickets
              </span>
              <button
                type="button"
                className="button button-secondary"
                onClick={() => setPage(page + 1)}
                disabled={page + 1 >= pagination.totalPages}
              >
                Next
              </button>
            </nav>
          )}
          {tickets.lastUpdated && (
            <p className="muted small">The queue refreshes automatically. Last checked {time(tickets.lastUpdated)}.</p>
          )}
        </>
      )}
    </>
  )
}
