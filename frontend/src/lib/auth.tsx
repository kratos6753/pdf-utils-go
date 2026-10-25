import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api, hasSessionHint, refreshSession, setSession, setSessionListener, type Session, type User } from './api'

interface AuthState {
  user: User | null
  ready: boolean // initial session restore finished
  login: (email: string, password: string) => Promise<void>
  signup: (email: string, password: string, name: string) => Promise<void>
  logout: () => Promise<void>
  setUser: (u: User) => void
  /** Restores the session from the refresh cookie (e.g. after Google sign-in). */
  restore: () => Promise<boolean>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  // Without a session hint there is nothing to restore, so we are ready at once.
  const [ready, setReady] = useState(() => !hasSessionHint())
  const qc = useQueryClient()

  useEffect(() => {
    setSessionListener((s) => setUser(s?.user ?? null))
    if (hasSessionHint()) void refreshSession().finally(() => setReady(true))
  }, [])

  // Refresh shortly before the access token expires.
  useEffect(() => {
    if (!user) return
    const t = setInterval(() => void refreshSession(), 12 * 60 * 1000)
    return () => clearInterval(t)
  }, [user])

  const start = useCallback(
    (s: Session) => {
      setSession(s)
      qc.invalidateQueries()
    },
    [qc],
  )

  const value = useMemo<AuthState>(
    () => ({
      user,
      ready,
      login: async (email, password) => start(await api<Session>('/auth/login', { json: { email, password } })),
      signup: async (email, password, name) => start(await api<Session>('/auth/signup', { json: { email, password, name } })),
      logout: async () => {
        await api('/auth/logout', { json: {} }).catch(() => {})
        setSession(null)
        qc.clear()
      },
      setUser,
      restore: async () => {
        const s = await refreshSession()
        qc.invalidateQueries()
        return !!s
      },
    }),
    [user, ready, start, qc],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
