import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { useInfiniteQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { Loader2, LogOut, Mail, MailOpen, Reply, ShieldCheck } from 'lucide-react'
import { ApiError } from '../../lib/api'
import { adminApi, adminToken, setAdminToken, type Admin as AdminUser, type AdminSession, type Feedback, type FeedbackPage } from '../../lib/admin'
import { relativeTime } from '../../lib/format'

function errorText(err: unknown) {
  return err instanceof ApiError ? err.message.charAt(0).toUpperCase() + err.message.slice(1) : 'Something went wrong'
}

function ErrorBox({ msg }: { msg?: string }) {
  if (!msg) return null
  return (
    <div className="rounded-xl bg-danger-soft p-3 text-sm text-danger" role="alert">
      {msg}
    </div>
  )
}

function Panel({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  return (
    <div className="grid min-h-[calc(100dvh-4rem)] place-items-center px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center text-center">
          <ShieldCheck className="size-10 text-brand" />
          <h1 className="mt-4 text-2xl font-bold">{title}</h1>
          {subtitle && <p className="mt-2 text-sm text-muted">{subtitle}</p>}
        </div>
        {children}
      </div>
    </div>
  )
}

function Login({ onSession }: { onSession: (s: AdminSession) => void }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      onSession(await adminApi<AdminSession>('/login', { json: { email, password } }))
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Panel title="Admin sign in">
      <form onSubmit={submit} className="space-y-4">
        <ErrorBox msg={error} />
        <div>
          <label className="label" htmlFor="admin-email">Email</label>
          <input id="admin-email" className="input" type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="admin-password">Password</label>
          <input id="admin-password" className="input" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <button className="btn-primary w-full py-3" disabled={busy}>
          {busy && <Loader2 className="size-4 animate-spin" />} Sign in
        </button>
      </form>
    </Panel>
  )
}

function ChangePassword({ forced, onSession, onCancel }: { forced: boolean; onSession: (s: AdminSession) => void; onCancel: () => void }) {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (next !== confirm) {
      setError('The new passwords do not match')
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      onSession(await adminApi<AdminSession>('/password', { json: { current_password: current, new_password: next } }))
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Panel
      title={forced ? 'Choose a new password' : 'Change password'}
      subtitle={forced ? 'You signed in with the initial password. Set your own before continuing.' : 'Other admin sessions will be signed out.'}
    >
      <form onSubmit={submit} className="space-y-4">
        <ErrorBox msg={error} />
        <div>
          <label className="label" htmlFor="current">Current password</label>
          <input id="current" className="input" type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="new">New password</label>
          <input id="new" className="input" type="password" autoComplete="new-password" minLength={12} required value={next} onChange={(e) => setNext(e.target.value)} />
          <p className="mt-1 text-xs text-muted">At least 12 characters.</p>
        </div>
        <div>
          <label className="label" htmlFor="confirm">Confirm new password</label>
          <input id="confirm" className="input" type="password" autoComplete="new-password" required value={confirm} onChange={(e) => setConfirm(e.target.value)} />
        </div>
        <button className="btn-primary w-full py-3" disabled={busy}>
          {busy && <Loader2 className="size-4 animate-spin" />} Save password
        </button>
        <button type="button" className="btn-ghost w-full" onClick={onCancel}>
          {forced ? 'Sign out' : 'Cancel'}
        </button>
      </form>
    </Panel>
  )
}

const topicLabel: Record<Feedback['topic'], string> = { feedback: 'Feedback', bug: 'Bug', question: 'Question', other: 'Other' }

function FeedbackCard({ item, onToggle }: { item: Feedback; onToggle: (read: boolean) => Promise<void> }) {
  const [busy, setBusy] = useState(false)
  const unread = !item.read_at
  const toggle = async () => {
    setBusy(true)
    try {
      await onToggle(unread)
    } finally {
      setBusy(false)
    }
  }
  return (
    <article className={`card p-5 ${unread ? 'border-l-4 border-l-brand' : ''}`}>
      <header className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
        <span className="font-semibold">{item.name || 'No name'}</span>
        <span className="text-muted">{item.email}</span>
        <span className="rounded-full bg-brand-soft px-2 py-0.5 text-xs font-medium text-brand">{topicLabel[item.topic]}</span>
        {item.client && <span className="text-xs text-muted">{item.client}</span>}
        {item.user_id && <span className="text-xs text-muted">· signed in</span>}
        <time className="ml-auto text-xs text-muted" dateTime={item.created_at} title={new Date(item.created_at).toLocaleString()}>
          {relativeTime(item.created_at)}
        </time>
      </header>
      <p className="mt-3 whitespace-pre-wrap break-words text-sm">{item.message}</p>
      <div className="mt-4 flex flex-wrap gap-2">
        <a className="btn-secondary py-1.5 text-sm" href={`mailto:${item.email}?subject=${encodeURIComponent('Re: your message to PDF Utils')}`}>
          <Reply className="size-4" /> Reply
        </a>
        <button className="btn-ghost py-1.5 text-sm" onClick={toggle} disabled={busy}>
          {busy ? <Loader2 className="size-4 animate-spin" /> : unread ? <MailOpen className="size-4" /> : <Mail className="size-4" />}
          {unread ? 'Mark read' : 'Mark unread'}
        </button>
      </div>
    </article>
  )
}

function Inbox({ admin, onSignOut, onChangePassword }: { admin: AdminUser; onSignOut: () => void; onChangePassword: () => void }) {
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [actionError, setActionError] = useState<string>()
  const queryClient = useQueryClient()
  const queryKey = ['admin-feedback', unreadOnly]
  const query = useInfiniteQuery({
    queryKey,
    initialPageParam: null as string | null,
    getNextPageParam: (page: FeedbackPage) => page.next_cursor,
    retry: false,
    queryFn: async ({ pageParam }) => {
      const q = new URLSearchParams({ limit: '30' })
      if (unreadOnly) q.set('unread', '1')
      if (pageParam) q.set('cursor', pageParam)
      try {
        return await adminApi<FeedbackPage>(`/feedback?${q}`)
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) onSignOut()
        throw err
      }
    },
  })
  const items = query.data?.pages.flatMap((p) => p.feedback) ?? []
  const unread = query.data?.pages[0]?.unread ?? 0
  const loading = query.isPending || query.isFetchingNextPage
  const error = actionError ?? (query.error ? errorText(query.error) : undefined)

  const toggle = async (id: string, read: boolean) => {
    setActionError(undefined)
    try {
      const updated = await adminApi<Feedback>(`/feedback/${id}`, { method: 'PATCH', json: { read } })
      queryClient.setQueryData<InfiniteData<FeedbackPage>>(queryKey, (data) =>
        data && {
          ...data,
          pages: data.pages.map((p) => ({
            ...p,
            unread: Math.max(0, p.unread + (read ? -1 : 1)),
            feedback: p.feedback.map((f) => (f.id === id ? updated : f)),
          })),
        },
      )
      // The other filter's cached pages are stale now.
      void queryClient.invalidateQueries({ queryKey: ['admin-feedback', !unreadOnly] })
    } catch (err) {
      setActionError(errorText(err))
    }
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6 px-4 py-10">
      <div className="flex flex-wrap items-center gap-3">
        <div className="flex-1">
          <h1 className="text-3xl font-extrabold tracking-tight">Admin</h1>
          <p className="text-sm text-muted">Signed in as {admin.email}</p>
        </div>
        <button className="btn-ghost text-sm" onClick={onChangePassword}>
          Change password
        </button>
        <button className="btn-secondary text-sm" onClick={onSignOut}>
          <LogOut className="size-4" /> Sign out
        </button>
      </div>

      <section className="space-y-4">
        <div className="flex flex-wrap items-center gap-3">
          <h2 className="flex-1 text-xl font-bold">
            Feedback{' '}
            {unread > 0 && <span className="ml-1 rounded-full bg-brand px-2 py-0.5 align-middle text-xs font-semibold text-white">{unread} unread</span>}
          </h2>
          <div className="inline-flex rounded-xl border border-line p-1 text-sm" role="tablist">
            {[
              { label: 'All', value: false },
              { label: 'Unread', value: true },
            ].map((f) => (
              <button
                key={f.label}
                role="tab"
                aria-selected={unreadOnly === f.value}
                className={`rounded-lg px-3 py-1 ${unreadOnly === f.value ? 'bg-brand-soft font-semibold text-brand' : 'text-muted hover:text-fg'}`}
                onClick={() => setUnreadOnly(f.value)}
              >
                {f.label}
              </button>
            ))}
          </div>
        </div>
        <ErrorBox msg={error} />
        {items.map((f) => (
          <FeedbackCard key={f.id} item={f} onToggle={(read) => toggle(f.id, read)} />
        ))}
        {!loading && items.length === 0 && !error && (
          <p className="card p-10 text-center text-sm text-muted">{unreadOnly ? 'No unread messages.' : 'No messages yet.'}</p>
        )}
        {loading && (
          <div className="grid place-items-center py-6">
            <Loader2 className="size-6 animate-spin text-brand" />
          </div>
        )}
        {!loading && query.hasNextPage && (
          <button className="btn-secondary w-full" onClick={() => query.fetchNextPage()}>
            Load more
          </button>
        )}
      </section>
    </div>
  )
}

/** /admin: operator portal. Not linked from the site. */
export default function Admin() {
  const queryClient = useQueryClient()
  const [admin, setAdmin] = useState<AdminUser | null>(null)
  const [checking, setChecking] = useState(() => adminToken() !== null)
  const [changing, setChanging] = useState(false)

  useEffect(() => {
    document.title = 'Admin – PDF Utils'
    const meta = document.createElement('meta')
    meta.name = 'robots'
    meta.content = 'noindex, nofollow'
    document.head.appendChild(meta)
    return () => meta.remove()
  }, [])

  // Resume a session from this tab.
  useEffect(() => {
    if (!adminToken()) return
    adminApi<{ admin: AdminUser }>('/me')
      .then((r) => setAdmin(r.admin))
      .catch(() => setAdminToken(null))
      .finally(() => setChecking(false))
  }, [])

  const onSession = (s: AdminSession) => {
    setAdminToken(s.access_token)
    setAdmin(s.admin)
    setChanging(false)
  }
  const signOut = useCallback(() => {
    queryClient.removeQueries({ queryKey: ['admin-feedback'] })
    setAdminToken(null)
    setAdmin(null)
    setChanging(false)
  }, [queryClient])

  if (checking) {
    return (
      <div className="grid min-h-[50vh] place-items-center">
        <Loader2 className="size-8 animate-spin text-brand" />
      </div>
    )
  }
  if (!admin) return <Login onSession={onSession} />
  if (admin.must_change_password || changing) {
    return <ChangePassword forced={admin.must_change_password} onSession={onSession} onCancel={admin.must_change_password ? signOut : () => setChanging(false)} />
  }
  return <Inbox admin={admin} onSignOut={signOut} onChangePassword={() => setChanging(true)} />
}
