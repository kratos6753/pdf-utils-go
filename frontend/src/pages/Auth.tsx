import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { CheckCircle2, Loader2, MailCheck } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../lib/api'
import { useAuth } from '../lib/auth'
import { Logo } from '../components/Layout'

function Shell({ title, subtitle, children }: { title: string; subtitle?: ReactNode; children: ReactNode }) {
  useEffect(() => {
    document.title = `${title} – PDF Utils`
  }, [title])
  return (
    <div className="grid min-h-[calc(100dvh-4rem)] place-items-center px-4 py-12">
      <div className="w-full max-w-sm">
        <div className="mb-8 flex flex-col items-center text-center">
          <Logo />
          <h1 className="mt-6 text-2xl font-bold">{title}</h1>
          {subtitle && <p className="mt-2 text-sm text-muted">{subtitle}</p>}
        </div>
        {children}
      </div>
    </div>
  )
}

function useGoogleEnabled() {
  const { data } = useQuery({ queryKey: ['config'], queryFn: () => api<{ google_auth: boolean }>('/config'), staleTime: Infinity })
  return data?.google_auth ?? false
}

function GoogleButton() {
  if (!useGoogleEnabled()) return null
  return (
    <a href="/v1/auth/google/start" className="btn-secondary w-full py-3">
      <svg viewBox="0 0 24 24" className="size-5" aria-hidden>
        <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.27-4.74 3.27-8.1z" />
        <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84A11 11 0 0 0 12 23z" />
        <path fill="#FBBC05" d="M5.84 14.1A6.6 6.6 0 0 1 5.5 12c0-.73.13-1.44.34-2.1V7.06H2.18A11 11 0 0 0 1 12c0 1.77.43 3.45 1.18 4.94l3.66-2.84z" />
        <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1A11 11 0 0 0 2.18 7.06l3.66 2.84C6.71 7.3 9.14 5.38 12 5.38z" />
      </svg>
      Continue with Google
    </a>
  )
}

function Divider() {
  if (!useGoogleEnabled()) return null
  return (
    <div className="my-6 flex items-center gap-3 text-xs text-muted">
      <span className="h-px flex-1 bg-line" /> or <span className="h-px flex-1 bg-line" />
    </div>
  )
}

function ErrorBox({ msg }: { msg?: string }) {
  if (!msg) return null
  return (
    <div className="rounded-xl bg-danger-soft p-3 text-sm text-danger" role="alert">
      {msg}
    </div>
  )
}

function useSubmit(fn: () => Promise<void>) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(undefined)
    try {
      await fn()
    } catch (err) {
      setError(err instanceof ApiError ? err.message.charAt(0).toUpperCase() + err.message.slice(1) : 'Something went wrong')
    } finally {
      setBusy(false)
    }
  }
  return { busy, error, submit }
}

export function Login() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const { busy, error, submit } = useSubmit(async () => {
    await login(email, password)
    const next = params.get('next') ?? '/'
    navigate(next.startsWith('/') && !next.startsWith('//') ? next : '/')
  })
  return (
    <Shell title="Welcome back" subtitle={<>New here? <Link to="/signup" className="font-semibold text-brand hover:underline">Create an account</Link></>}>
      {params.get('error') === 'google' && <ErrorBox msg="Google sign-in did not complete. Please try again." />}
      <div className="mt-4">
        <GoogleButton />
      </div>
      <Divider />
      <form onSubmit={submit} className="space-y-4">
        <ErrorBox msg={error} />
        <div>
          <label className="label" htmlFor="email">Email</label>
          <input id="email" className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div>
          <div className="flex items-center justify-between">
            <label className="label" htmlFor="password">Password</label>
            <Link to="/forgot-password" className="mb-1.5 text-xs font-semibold text-brand hover:underline">Forgot password?</Link>
          </div>
          <input id="password" className="input" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <button className="btn-primary w-full py-3" disabled={busy}>
          {busy && <Loader2 className="size-4 animate-spin" />} Log in
        </button>
      </form>
    </Shell>
  )
}

export function Signup() {
  const { signup } = useAuth()
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const { busy, error, submit } = useSubmit(async () => {
    await signup(email, password, name)
    navigate('/')
  })
  return (
    <Shell title="Create your free account" subtitle={<>Already have one? <Link to="/login" className="font-semibold text-brand hover:underline">Log in</Link></>}>
      <ul className="mb-6 space-y-1.5 text-sm text-muted">
        {['Higher limits and bigger files', 'File history, up to 1 GB', 'Your recent work is kept when you sign up'].map((t) => (
          <li key={t} className="flex items-center gap-2">
            <CheckCircle2 className="size-4 text-ok" /> {t}
          </li>
        ))}
      </ul>
      <GoogleButton />
      <Divider />
      <form onSubmit={submit} className="space-y-4">
        <ErrorBox msg={error} />
        <div>
          <label className="label" htmlFor="name">Name</label>
          <input id="name" className="input" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="email">Email</label>
          <input id="email" className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="password">Password</label>
          <input id="password" className="input" type="password" autoComplete="new-password" required minLength={8} value={password} onChange={(e) => setPassword(e.target.value)} />
          <p className="mt-1.5 text-xs text-muted">At least 8 characters.</p>
        </div>
        <button className="btn-primary w-full py-3" disabled={busy}>
          {busy && <Loader2 className="size-4 animate-spin" />} Create account
        </button>
      </form>
    </Shell>
  )
}

export function ForgotPassword() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const { busy, error, submit } = useSubmit(async () => {
    await api('/auth/forgot-password', { json: { email } })
    setSent(true)
  })
  if (sent)
    return (
      <Shell title="Check your email">
        <div className="card p-6 text-center text-sm text-muted">
          <MailCheck className="mx-auto mb-3 size-10 text-ok" />
          If an account exists for <b className="text-fg">{email}</b>, we've sent a link to reset your password.
        </div>
      </Shell>
    )
  return (
    <Shell title="Reset your password" subtitle="We'll email you a link to set a new one.">
      <form onSubmit={submit} className="space-y-4">
        <ErrorBox msg={error} />
        <div>
          <label className="label" htmlFor="email">Email</label>
          <input id="email" className="input" type="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </div>
        <button className="btn-primary w-full py-3" disabled={busy}>Send reset link</button>
        <Link to="/login" className="block text-center text-sm font-semibold text-brand hover:underline">Back to log in</Link>
      </form>
    </Shell>
  )
}

export function ResetPassword() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const { busy, error, submit } = useSubmit(async () => {
    await api('/auth/reset-password', { json: { token: params.get('token') ?? '', password } })
    navigate('/login')
  })
  return (
    <Shell title="Choose a new password">
      <form onSubmit={submit} className="space-y-4">
        <ErrorBox msg={error} />
        <div>
          <label className="label" htmlFor="password">New password</label>
          <input id="password" className="input" type="password" autoComplete="new-password" minLength={8} required value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        <button className="btn-primary w-full py-3" disabled={busy}>Save password</button>
      </form>
    </Shell>
  )
}

export function VerifyEmail() {
  const [params] = useSearchParams()
  const [state, setState] = useState<'loading' | 'ok' | 'error'>('loading')
  useEffect(() => {
    api('/auth/verify-email', { json: { token: params.get('token') ?? '' } })
      .then(() => setState('ok'))
      .catch(() => setState('error'))
  }, [params])
  return (
    <Shell title={state === 'ok' ? 'Email confirmed' : state === 'error' ? 'Link expired' : 'Confirming…'}>
      <div className="card p-6 text-center text-sm text-muted">
        {state === 'loading' && <Loader2 className="mx-auto size-8 animate-spin text-brand" />}
        {state === 'ok' && <>Thanks! Your email address is confirmed. <Link to="/" className="font-semibold text-brand">Go to tools</Link></>}
        {state === 'error' && <>This confirmation link is invalid or has expired. You can request a new one from your account settings.</>}
      </div>
    </Shell>
  )
}

/** Landing page after Google sign-in: the API has set the refresh cookie. */
export function AuthCallback() {
  const { restore } = useAuth()
  const navigate = useNavigate()
  const started = useRef(false)
  useEffect(() => {
    if (started.current) return
    started.current = true
    restore().then((ok) => navigate(ok ? '/' : '/login?error=google', { replace: true }))
  }, [restore, navigate])
  return (
    <div className="grid min-h-[60vh] place-items-center">
      <Loader2 className="size-8 animate-spin text-brand" />
    </div>
  )
}
