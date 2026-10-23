import { Link } from 'react-router-dom'
import { EmptyState } from './feedback.tsx'

export function NotFoundPage() {
  return (
    <main className="container main">
      <EmptyState title="Page not found">
        We could not find that page. <Link to="/">Go to the start</Link>.
      </EmptyState>
    </main>
  )
}
