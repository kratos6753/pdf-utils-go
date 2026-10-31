// Client for the admin API (/v1/admin). Admin tokens are separate from app
// sessions: kept in sessionStorage, valid for 8 hours, with no refresh.
import { toError } from './api'

export interface Admin {
  email: string
  must_change_password: boolean
  last_login_at: string | null
}

export interface AdminSession {
  access_token: string
  expires_at: string
  admin: Admin
}

export interface Feedback {
  id: string
  user_id: string | null
  name: string
  email: string
  topic: 'feedback' | 'bug' | 'question' | 'other'
  message: string
  client: string
  created_at: string
  read_at: string | null
}

export interface FeedbackPage {
  feedback: Feedback[]
  next_cursor: string | null
  unread: number
}

const KEY = 'pdfutils.admin'

export function adminToken(): string | null {
  try {
    return sessionStorage.getItem(KEY)
  } catch {
    return null
  }
}

export function setAdminToken(token: string | null) {
  try {
    if (token) sessionStorage.setItem(KEY, token)
    else sessionStorage.removeItem(KEY)
  } catch {
    /* storage unavailable: the session lasts until reload */
  }
}

export async function adminApi<T>(path: string, opts: { method?: string; json?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = {}
  const token = adminToken()
  if (token) headers.Authorization = `Bearer ${token}`
  if (opts.json !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(`/v1/admin${path}`, {
    method: opts.method ?? (opts.json !== undefined ? 'POST' : 'GET'),
    headers,
    body: opts.json !== undefined ? JSON.stringify(opts.json) : undefined,
  })
  if (!res.ok) throw await toError(res)
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}
