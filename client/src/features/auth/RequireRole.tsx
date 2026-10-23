import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { Spinner } from '../../components/feedback.tsx'
import type { Role } from '../../types/api.ts'
import { homePathFor, useAuth } from './useAuth.ts'

/**
 * Keeps people on the pages for their role. This is a convenience only: the API enforces the
 * same rules on every request, so hiding a page here is never the security boundary.
 */
export function RequireRole({ role }: { role: Role }) {
  const { user, status } = useAuth()
  const location = useLocation()

  if (status === 'checking') return <Spinner label="Checking your session…" />
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  if (user.role !== role) return <Navigate to={homePathFor(user.role)} replace />
  return <Outlet />
}
