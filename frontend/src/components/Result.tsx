import { Link } from 'react-router-dom'
import { ArrowLeft, CheckCircle2, Download, History, Loader2, Sparkles } from 'lucide-react'
import { useState } from 'react'
import { ApiError, downloadFile, type Job } from '../lib/api'
import { useAuth } from '../lib/auth'
import { formatBytes, formatDate } from '../lib/format'
import { nextTools, type Tool } from '../lib/tools'
import { ToolIcon } from './ToolCard'

export function Processing({ tool, job }: { tool: Tool; job: Job | null }) {
  const pct = job?.progress ?? 5
  return (
    <div className="mx-auto grid max-w-lg place-items-center px-4 py-24 text-center">
      <Loader2 className="size-14 animate-spin text-brand" />
      <h2 className="mt-6 text-2xl font-bold">{job?.status === 'queued' ? 'Waiting in line…' : `${tool.title}: working on it…`}</h2>
      <p className="mt-2 text-muted">This usually takes a few seconds. Please keep this page open.</p>
      <div className="mt-8 h-2 w-full overflow-hidden rounded-full bg-line">
        <div className="h-full rounded-full bg-brand transition-[width] duration-500" style={{ width: `${Math.max(5, pct)}%` }} />
      </div>
    </div>
  )
}

export function Done({ tool, job, inputBytes, onRestart }: { tool: Tool; job: Job; inputBytes: number; onRestart: () => void }) {
  const { user } = useAuth()
  const out = job.output!
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string>()
  const saved = tool.api === 'compress' && inputBytes > 0 ? Math.round((1 - out.size / inputBytes) * 100) : null
  const next = nextTools(tool, out.mime)

  const download = async () => {
    setBusy(true)
    setErr(undefined)
    try {
      await downloadFile(out.id)
    } catch (e) {
      setErr(e instanceof ApiError ? e.message : 'Download failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto max-w-2xl px-4 py-16 text-center">
      <CheckCircle2 className="mx-auto size-16 text-ok" />
      <h1 className="mt-5 text-3xl font-extrabold tracking-tight sm:text-4xl">Your file is ready!</h1>
      {saved !== null && (
        <div className="mx-auto mt-6 flex max-w-sm items-center justify-center gap-4 rounded-2xl bg-ok-soft px-6 py-4">
          <div className="text-4xl font-extrabold text-ok">{saved > 0 ? `${saved}%` : '0%'}</div>
          <div className="text-left text-sm">
            <div className="font-semibold">{saved > 0 ? 'smaller' : 'already optimized'}</div>
            <div className="text-muted">
              {formatBytes(inputBytes)} → {formatBytes(out.size)}
            </div>
          </div>
        </div>
      )}
      <div className="mt-8 flex flex-col items-center gap-3">
        <button className="btn-primary btn-lg min-w-72 text-lg" onClick={download} disabled={busy}>
          {busy ? <Loader2 className="size-5 animate-spin" /> : <Download className="size-5" />}
          Download {out.mime === 'application/zip' ? 'ZIP' : out.mime === 'application/pdf' ? 'PDF' : 'file'}
        </button>
        <div className="text-sm text-muted">
          {out.name} · {formatBytes(out.size)}
        </div>
        {err && <div className="text-sm text-danger">{err}</div>}
      </div>

      <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
        <button className="btn-ghost" onClick={onRestart}>
          <ArrowLeft className="size-4" /> Start over
        </button>
        {user && (
          <Link to="/history" className="btn-ghost">
            <History className="size-4" /> View in history
          </Link>
        )}
      </div>

      {!user && out.expires_at && (
        <div className="mx-auto mt-8 flex max-w-lg items-start gap-3 rounded-2xl border border-line bg-surface p-4 text-left text-sm">
          <Sparkles className="mt-0.5 size-5 shrink-0 text-brand" />
          <div>
            Files are deleted automatically at {formatDate(out.expires_at)}.{' '}
            <Link to="/signup" className="font-semibold text-brand hover:underline">
              Create a free account
            </Link>{' '}
            to keep them in your history and get higher limits.
          </div>
        </div>
      )}

      {next.length > 0 && (
        <section className="mt-14" aria-labelledby="continue-heading">
          <h2 id="continue-heading" className="text-sm font-bold uppercase tracking-wider text-muted">Continue to</h2>
          <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-3">
            {next.map((t) => (
              <Link key={t.slug} to={`/${t.slug}?files=${out.id}`} className="card flex items-center gap-3 p-3 text-left text-sm font-semibold transition hover:shadow-[var(--shadow-lift)]">
                <ToolIcon tool={t} size="sm" />
                {t.title}
              </Link>
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

/** Shown when a plan limit stops the user, nudging toward an account. */
export function LimitNotice({ error, onDismiss }: { error: ApiError; onDismiss: () => void }) {
  const { user } = useAuth()
  const reset = error.details?.reset_at as string | undefined
  const title = error.code === 'limit_reached' ? "You've reached the free limit" : error.code === 'file_too_large' ? 'That file is too large' : 'Limit reached'
  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4" role="dialog" aria-modal="true">
      <div className="card w-full max-w-md p-7 text-center shadow-[var(--shadow-lift)]">
        <Sparkles className="mx-auto size-10 text-brand" />
        <h2 className="mt-4 text-xl font-bold">{title}</h2>
        <p className="mt-2 text-sm text-muted">{error.message}.</p>
        {reset && <p className="mt-1 text-sm text-muted">Your allowance resets at {new Date(reset).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}.</p>}
        <div className="mt-6 flex flex-col gap-2">
          {!user ? (
            <>
              <Link to="/signup" className="btn-primary">
                Sign up free for higher limits
              </Link>
              <Link to="/login" className="btn-secondary">
                I already have an account
              </Link>
            </>
          ) : null}
          <button className="btn-ghost" onClick={onDismiss}>
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
