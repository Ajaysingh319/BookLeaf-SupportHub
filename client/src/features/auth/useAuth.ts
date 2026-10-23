import { useContext } from 'react'
import { AuthContext, type AuthState } from './authContext.ts'

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside <AuthProvider>.')
  return context
}

/** Where each role lands after signing in. */
export function homePathFor(role: 'AUTHOR' | 'ADMIN'): string {
  return role === 'ADMIN' ? '/admin/tickets' : '/author'
}
