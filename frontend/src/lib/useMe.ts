import { useQuery } from '@tanstack/react-query'
import { api, type Me } from './api'
import { useAuth } from './auth'

/** The caller's plan, limits and usage (works for anonymous visitors too). */
export function useMe() {
  const { ready, user } = useAuth()
  return useQuery({ queryKey: ['me', user?.id ?? 'anon'], queryFn: () => api<Me>('/me'), enabled: ready, staleTime: 30_000 })
}
