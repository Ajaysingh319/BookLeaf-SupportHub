import { createContext } from 'react'
import type { UserSummary } from '../../types/api.ts'

export interface AuthState {
  user: UserSummary | null
  /** 'checking' while a saved session is verified on first load; the app shows a spinner until then. */
  status: 'checking' | 'ready'
  /** True when a signed-in session was rejected by the server (expired or invalid token). */
  sessionExpired: boolean
  login: (email: string, password: string) => Promise<UserSummary>
  logout: () => Promise<void>
}

export const AuthContext = createContext<AuthState | null>(null)
