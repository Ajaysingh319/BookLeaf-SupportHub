import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, setCsrfToken, setUnauthorizedHandler } from '../../lib/api.ts'
import type { UserSummary } from '../../types/api.ts'
import { AuthContext, type AuthState } from './authContext.ts'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null)
  const [status, setStatus] = useState<AuthState['status']>('checking')
  const [sessionExpired, setSessionExpired] = useState(false)

  // If the server rejects the session at any point, sign the user out and say why.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null)
      setSessionExpired(true)
    })
    return () => setUnauthorizedHandler(null)
  }, [])

  // On first load, ask the server who is signed in: the session cookie is invisible to scripts.
  useEffect(() => {
    const controller = new AbortController()
    api
      .session(controller.signal)
      .then((session) => {
        setCsrfToken(session.csrfToken)
        setUser(session.user)
      })
      .catch(() => {
        // Server down or request aborted when the effect re-runs: show the login page, nothing to discard.
      })
      .finally(() => {
        if (!controller.signal.aborted) setStatus('ready')
      })
    return () => controller.abort()
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const result = await api.login(email.trim(), password)
    setCsrfToken(result.csrfToken)
    setSessionExpired(false)
    setUser(result.user)
    return result.user
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.logout()
    } catch {
      /* the session is forgotten here either way */
    }
    setCsrfToken(null)
    setSessionExpired(false)
    setUser(null)
  }, [])

  const value = useMemo<AuthState>(
    () => ({ user, status, sessionExpired, login, logout }),
    [user, status, sessionExpired, login, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
