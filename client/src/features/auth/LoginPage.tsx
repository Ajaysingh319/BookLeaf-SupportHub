import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { Spinner } from '../../components/feedback.tsx'
import { type ApiError, toApiError } from '../../lib/api.ts'
import { useConfig } from '../config/useConfig.ts'
import { homePathFor, useAuth } from './useAuth.ts'

interface LocationState {
  from?: string
}

export function LoginPage() {
  const { user, status, sessionExpired, login } = useAuth()
  const { brandName } = useConfig()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [error, setError] = useState<ApiError | null>(null)
  const [submitting, setSubmitting] = useState(false)

  if (status === 'checking') return <Spinner label="Checking your session…" />
  if (user) return <Navigate to={homePathFor(user.role)} replace />

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const problems: Record<string, string> = {}
    if (!email.trim()) problems.email = 'Enter your email address.'
    if (!password) problems.password = 'Enter your password.'
    setFieldErrors(problems)
    setError(null)
    if (Object.keys(problems).length > 0) return

    setSubmitting(true)
    try {
      const signedIn = await login(email, password)
      // Return to the page they were trying to open, but only if it belongs to their role.
      const from = (location.state as LocationState | null)?.from
      const home = homePathFor(signedIn.role)
      const area = signedIn.role === 'ADMIN' ? '/admin' : '/author'
      navigate(from && from.startsWith(area) ? from : home, { replace: true })
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
    <main className="login-page">
      <div className="card login-card">
        <h1>{brandName}</h1>
        <p className="muted">Sign in to see your books, royalties and support tickets.</p>

        {sessionExpired && !error && (
          <div className="banner banner-info" role="status">
            Your session has ended. Please sign in again.
          </div>
        )}
        {error && Object.keys(fieldErrors).length === 0 && (
          <div className="banner banner-error" role="alert">
            <p>{error.message}</p>
            {error.requestId && error.status >= 500 && <p className="muted small">Reference: {error.requestId}</p>}
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <div className="field">
            <label htmlFor="email">Email address</label>
            <input
              id="email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              aria-invalid={Boolean(fieldErrors.email)}
              aria-describedby={fieldErrors.email ? 'email-error' : undefined}
              maxLength={254}
              required
            />
            {fieldErrors.email && (
              <p id="email-error" className="field-error">
                {fieldErrors.email}
              </p>
            )}
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-invalid={Boolean(fieldErrors.password)}
              aria-describedby={fieldErrors.password ? 'password-error' : undefined}
              maxLength={128}
              required
            />
            {fieldErrors.password && (
              <p id="password-error" className="field-error">
                {fieldErrors.password}
              </p>
            )}
          </div>
          <button type="submit" className="button button-block" disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </div>
    </main>
  )
}
