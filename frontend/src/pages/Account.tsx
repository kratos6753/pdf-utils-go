import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { CheckCircle2, Loader2, MailWarning } from 'lucide-react'
import { api, ApiError, setSession, type User } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatBytes } from '../lib/format'
import { useMe } from '../lib/useMe'

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="card p-6">
      <h2 className="text-lg font-bold">{title}</h2>
      {description && <p className="mt-1 text-sm text-muted">{description}</p>}
      <div className="mt-5">{children}</div>
    </section>
  )
}

function Status({ ok, err }: { ok?: string; err?: string }) {
  if (err) return <p className="text-sm text-danger">{err}</p>
  if (ok)
    return (
      <p className="flex items-center gap-1.5 text-sm text-ok">
        <CheckCircle2 className="size-4" /> {ok}
      </p>
    )
  return null
}

function useAction() {
  const [busy, setBusy] = useState(false)
  const [ok, setOk] = useState<string>()
  const [err, setErr] = useState<string>()
  const run = async (fn: () => Promise<string>) => {
    setBusy(true)
    setOk(undefined)
    setErr(undefined)
    try {
      setOk(await fn())
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }
  return { busy, ok, err, run }
}

export default function Account() {
  const { user, setUser } = useAuth()
  const { data: me } = useMe()
  const navigate = useNavigate()
  const [name, setName] = useState(user?.name ?? '')
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirmDelete, setConfirmDelete] = useState('')
  const profile = useAction()
  const password = useAction()
  const verify = useAction()
  const del = useAction()
  useEffect(() => {
    document.title = 'Account – PDF Utils'
  }, [])
  if (!user) return null

  const saveProfile = (e: FormEvent) => {
    e.preventDefault()
    profile.run(async () => {
      setUser(await api<User>('/me', { method: 'PATCH', json: { name } }))
      return 'Saved'
    })
  }
  const savePassword = (e: FormEvent) => {
    e.preventDefault()
    password.run(async () => {
      await api('/me/password', { json: { current_password: current, new_password: next } })
      setCurrent('')
      setNext('')
      return 'Password updated'
    })
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6 px-4 py-10">
      <h1 className="text-3xl font-extrabold tracking-tight">Account settings</h1>

      {!user.email_verified && (
        <div className="flex flex-wrap items-center gap-3 rounded-2xl bg-warn-soft p-4 text-sm">
          <MailWarning className="size-5 text-warn" />
          <span className="flex-1">Please confirm your email address ({user.email}).</span>
          <button className="btn-secondary py-1.5" disabled={verify.busy} onClick={() => verify.run(async () => (await api('/me/resend-verification', { json: {} }), 'Email sent'))}>
            Resend email
          </button>
          <Status ok={verify.ok} err={verify.err} />
        </div>
      )}

      <Section title="Profile">
        <form onSubmit={saveProfile} className="space-y-4">
          <div>
            <label className="label" htmlFor="email">Email</label>
            <input id="email" className="input opacity-70" value={user.email} disabled />
          </div>
          <div>
            <label className="label" htmlFor="name">Name</label>
            <input id="name" className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
          </div>
          <div className="flex items-center gap-3">
            <button className="btn-primary" disabled={profile.busy}>Save</button>
            <Status ok={profile.ok} err={profile.err} />
          </div>
        </form>
      </Section>

      <Section title="Plan & usage">
        <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
          {[
            ['Plan', me?.plan === 'free' ? 'Free' : (me?.plan ?? '…')],
            ['Tasks per hour', me?.limits.tasks_per_hour ?? '…'],
            ['Max file size', me ? `${me.limits.max_file_mb} MB` : '…'],
            ['Storage', me?.storage ? `${formatBytes(me.storage.used_bytes)} / ${formatBytes(me.storage.limit_bytes)}` : '…'],
          ].map(([k, v]) => (
            <div key={String(k)} className="rounded-xl bg-surface-2 p-3">
              <dt className="text-xs text-muted">{k}</dt>
              <dd className="mt-0.5 font-semibold">{v}</dd>
            </div>
          ))}
        </dl>
      </Section>

      <Section title={user.has_password ? 'Change password' : 'Set a password'} description={user.has_password ? undefined : 'You sign in with Google. Add a password to also sign in with your email.'}>
        <form onSubmit={savePassword} className="space-y-4">
          {user.has_password && (
            <div>
              <label className="label" htmlFor="cur">Current password</label>
              <input id="cur" className="input" type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
            </div>
          )}
          <div>
            <label className="label" htmlFor="new">New password</label>
            <input id="new" className="input" type="password" autoComplete="new-password" minLength={8} required value={next} onChange={(e) => setNext(e.target.value)} />
          </div>
          <div className="flex items-center gap-3">
            <button className="btn-primary" disabled={password.busy}>Update password</button>
            <Status ok={password.ok} err={password.err} />
          </div>
        </form>
      </Section>

      <Section title="Delete account" description="Permanently deletes your account and every file in your history. This cannot be undone.">
        <label className="label" htmlFor="del">
          Type <b>DELETE</b> to confirm
        </label>
        <div className="flex flex-wrap gap-3">
          <input id="del" className="input max-w-48" value={confirmDelete} onChange={(e) => setConfirmDelete(e.target.value)} />
          <button
            className="btn rounded-xl bg-danger text-white hover:opacity-90"
            disabled={confirmDelete !== 'DELETE' || del.busy}
            onClick={() =>
              del.run(async () => {
                await api('/me', { method: 'DELETE' })
                setSession(null)
                navigate('/')
                return 'Deleted'
              })
            }
          >
            {del.busy && <Loader2 className="size-4 animate-spin" />} Delete my account
          </button>
        </div>
        <div className="mt-2">
          <Status err={del.err} />
        </div>
      </Section>
    </div>
  )
}
