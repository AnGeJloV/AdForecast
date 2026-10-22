import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { api, getToken, setToken, setUnauthorizedHandler } from '../api/client'
import type { CurrentUser, RegisterRequest } from '../api/types'

interface AuthContextValue {
  user: CurrentUser | null
  loading: boolean
  isAdmin: boolean
  login: (login: string, password: string) => Promise<CurrentUser>
  register: (data: RegisterRequest) => Promise<CurrentUser>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<CurrentUser | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setToken(null)
      setUser(null)
    })
    if (!getToken()) {
      setLoading(false)
      return
    }
    api.me()
      .then(setUser)
      .catch(() => {
        setToken(null)
        setUser(null)
      })
      .finally(() => setLoading(false))
  }, [])

  const login = useCallback(async (loginName: string, password: string) => {
    const response = await api.login(loginName, password)
    setToken(response.accessToken)
    const me = await api.me()
    setUser(me)
    return me
  }, [])

  const register = useCallback(async (data: RegisterRequest) => {
    const response = await api.register(data)
    setToken(response.accessToken)
    const me = await api.me()
    setUser(me)
    return me
  }, [])

  const logout = useCallback(() => {
    api.logout().catch(() => undefined)
    setToken(null)
    setUser(null)
  }, [])

  return (
    <AuthContext.Provider value={{ user, loading, isAdmin: user?.role === 'администратор', login, register, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth вне AuthProvider')
  }
  return context
}
