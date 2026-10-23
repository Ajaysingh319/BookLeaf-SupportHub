import { Navigate, Route, Routes } from 'react-router-dom'
import { Layout } from './components/Layout.tsx'
import { NotFoundPage } from './components/NotFoundPage.tsx'
import { Spinner } from './components/feedback.tsx'
import { AdminQueuePage } from './features/admin/AdminQueuePage.tsx'
import { AdminTicketPage } from './features/admin/AdminTicketPage.tsx'
import { LoginPage } from './features/auth/LoginPage.tsx'
import { RequireRole } from './features/auth/RequireRole.tsx'
import { homePathFor, useAuth } from './features/auth/useAuth.ts'
import { MyBooksPage } from './features/books/MyBooksPage.tsx'
import { AuthorHomePage } from './features/home/AuthorHomePage.tsx'
import { MyTicketsPage } from './features/tickets/MyTicketsPage.tsx'
import { NewTicketPage } from './features/tickets/NewTicketPage.tsx'
import { TicketDetailPage } from './features/tickets/TicketDetailPage.tsx'

/** "/" sends people to their own area, or to the login page. */
function RootRedirect() {
  const { user, status } = useAuth()
  if (status === 'checking') return <Spinner label="Checking your session…" />
  return <Navigate to={user ? homePathFor(user.role) : '/login'} replace />
}

// Routes follow TDD section 10. Route guards only steer people; the API enforces access.
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<RootRedirect />} />
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireRole role="AUTHOR" />}>
        <Route element={<Layout />}>
          <Route path="/author" element={<AuthorHomePage />} />
          <Route path="/author/books" element={<MyBooksPage />} />
          <Route path="/author/tickets" element={<MyTicketsPage />} />
          <Route path="/author/tickets/new" element={<NewTicketPage />} />
          <Route path="/author/tickets/:id" element={<TicketDetailPage />} />
        </Route>
      </Route>

      <Route element={<RequireRole role="ADMIN" />}>
        <Route element={<Layout />}>
          <Route path="/admin/tickets" element={<AdminQueuePage />} />
          <Route path="/admin/tickets/:id" element={<AdminTicketPage />} />
        </Route>
      </Route>

      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  )
}
