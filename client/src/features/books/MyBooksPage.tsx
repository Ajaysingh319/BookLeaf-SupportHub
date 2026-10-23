import { useCallback } from 'react'
import { Link } from 'react-router-dom'
import { EmptyState, ErrorMessage, Spinner } from '../../components/feedback.tsx'
import { api } from '../../lib/api.ts'
import { isInProduction } from '../../lib/format.ts'
import { useResource } from '../../lib/useResource.ts'
import type { Book } from '../../types/api.ts'
import { useConfig, useFormatters } from '../config/useConfig.ts'

function BookCard({ book }: { book: Book }) {
  const { inProductionStatusPrefix } = useConfig()
  const { money, count, date } = useFormatters()
  const inProduction = isInProduction(book.status, inProductionStatusPrefix)
  return (
    <article className="card book-card" aria-labelledby={`book-${book.id}`}>
      <header className="book-head">
        <div>
          <h2 id={`book-${book.id}`}>{book.title}</h2>
          <p className="muted">{book.genre}</p>
        </div>
        <span className={`chip ${inProduction ? 'chip-production' : 'chip-live'}`}>{book.status}</span>
      </header>

      <dl className="facts">
        <div>
          <dt>ISBN</dt>
          <dd>{book.isbn}</dd>
        </div>
        <div>
          <dt>Published</dt>
          <dd>{book.publicationDate ? date(book.publicationDate) : 'Not yet published'}</dd>
        </div>
        <div>
          <dt>MRP</dt>
          <dd>{book.mrp === null ? 'Not set yet' : money(book.mrp)}</dd>
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
          <dd>{book.lastRoyaltyPayoutDate ? date(book.lastRoyaltyPayoutDate) : 'No payout yet'}</dd>
        </div>
        <div>
          <dt>Print partner</dt>
          <dd>{book.printPartner ?? '—'}</dd>
        </div>
        <div>
          <dt>Available on</dt>
          <dd>{book.availableOn.length > 0 ? book.availableOn.join(', ') : 'Not listed yet'}</dd>
        </div>
      </dl>

      {inProduction && (
        <p className="muted small">
          This book is still in production, so sales and royalties start once it is published and live.
        </p>
      )}
      <Link className="button button-secondary" to={`/author/tickets/new?book=${book.id}`}>
        Raise a query about this book
      </Link>
    </article>
  )
}

export function MyBooksPage() {
  const { policy } = useConfig()
  const { money } = useFormatters()
  const load = useCallback((signal: AbortSignal) => api.listBooks(signal), [])
  const books = useResource(load)

  const totals = (books.data ?? []).reduce(
    (sum, book) => ({
      earned: sum.earned + (book.totalRoyaltyEarned ?? 0),
      paid: sum.paid + (book.royaltyPaid ?? 0),
      pending: sum.pending + (book.royaltyPending ?? 0),
    }),
    { earned: 0, paid: 0, pending: 0 },
  )

  return (
    <>
      <h1>My Books</h1>
      <p className="muted">
        Royalties are calculated quarterly and paid within {policy.royaltyPayoutDays} days of the quarter ending.
        Payouts below {money(policy.minimumPayout)} roll over to the next quarter.
      </p>

      {books.loading && !books.data && <Spinner label="Loading your books…" />}
      {books.error && <ErrorMessage error={books.error} onRetry={books.reload} />}
      {books.data && books.data.length === 0 && (
        <EmptyState title="No books yet">
          Your books will appear here once they are in production with us.
        </EmptyState>
      )}

      {books.data && books.data.length > 0 && (
        <>
          <section className="summary-grid" aria-label="Royalty totals across all your books">
            <div className="card summary-card">
              <span className="summary-label">Total earned</span>
              <span className="summary-value">{money(totals.earned)}</span>
            </div>
            <div className="card summary-card">
              <span className="summary-label">Total paid</span>
              <span className="summary-value">{money(totals.paid)}</span>
            </div>
            <div className="card summary-card">
              <span className="summary-label">Total pending</span>
              <span className="summary-value">{money(totals.pending)}</span>
            </div>
          </section>
          <div className="book-list">
            {books.data.map((book) => (
              <BookCard key={book.id} book={book} />
            ))}
          </div>
        </>
      )}
    </>
  )
}
