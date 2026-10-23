import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, setUnauthorizedHandler, tokenStore } from '../../lib/api.ts'
import type { UserSummary } from '../../types/api.ts'
import { AuthContext, type AuthState } from './authContext.ts'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserSummary | null>(null)
  const [status, setStatus] = useState<AuthState['status']>(() => (tokenStore.get() ? 'checking' : 'ready'))
  const [sessionExpired, setSessionExpired] = useState(false)

  // If the server rejects the saved token at any point, sign the user out and say why.
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null)
      setSessionExpired(true)
    })
    return () => setUnauthorizedHandler(null)
  }, [])

  // On first load, check a saved session with the server; the token alone is never trusted.
  useEffect(() => {
    if (!tokenStore.get()) return
    const controller = new AbortController()
    api
      .me(controller.signal)
      .then(setUser)
      .catch(() => {
        // An expired or invalid token is already cleared by the API client on a 401. Any other
        // failure (server down, request aborted when the effect re-runs) must not discard a good token.
      })
      .finally(() => {
        if (!controller.signal.aborted) setStatus('ready')
      })
    return () => controller.abort()
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const result = await api.login(email.trim(), password)
    tokenStore.set(result.token)
    setSessionExpired(false)
    setUser(result.user)
    return result.user
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.logout()
    } catch {
      /* the token is discarded either way */
    }
    tokenStore.clear()
    setSessionExpired(false)
    setUser(null)
  }, [])

  const value = useMemo<AuthState>(
    () => ({ user, status, sessionExpired, login, logout }),
    [user, status, sessionExpired, login, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
