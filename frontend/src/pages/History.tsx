import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { Download, FileArchive, FileImage, FileText, Loader2, Trash2 } from 'lucide-react'
import clsx from 'clsx'
import { api, ApiError, downloadFile, type FileMeta } from '../lib/api'
import { formatBytes, formatDate } from '../lib/format'
import { tools } from '../lib/tools'
import { useMe } from '../lib/useMe'

function FileIcon({ mime }: { mime: string }) {
  const Icon = mime === 'application/zip' ? FileArchive : mime.startsWith('image/') ? FileImage : FileText
  return (
    <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-surface-2 text-muted">
      <Icon className="size-5" />
    </span>
  )
}

type Page = { files: FileMeta[]; next_cursor: string | null }

export default function History() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const { data: me } = useMe()
  const [kind, setKind] = useState<'output' | 'input' | ''>('output')
  const [busy, setBusy] = useState<string>()
  const [error, setError] = useState<string>()
  useEffect(() => {
    document.title = 'File history – PDF Utils'
  }, [])

  const q = useInfiniteQuery({
    queryKey: ['files', kind],
    queryFn: ({ pageParam }) => api<Page>(`/me/files?limit=30&kind=${kind}${pageParam ? `&cursor=${pageParam}` : ''}`),
    initialPageParam: '',
    getNextPageParam: (p) => p.next_cursor ?? undefined,
  })
  const files = q.data?.pages.flatMap((p) => p.files) ?? []

  const act = async (id: string, fn: () => Promise<unknown>) => {
    setBusy(id)
    setError(undefined)
    try {
      await fn()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Something went wrong')
    } finally {
      setBusy(undefined)
    }
  }
  const remove = (f: FileMeta) =>
    act(f.id, async () => {
      await api(`/files/${f.id}`, { method: 'DELETE' })
      qc.invalidateQueries({ queryKey: ['files'] })
      qc.invalidateQueries({ queryKey: ['me'] })
    })

  const used = me?.storage?.used_bytes ?? 0
  const limit = me?.storage?.limit_bytes ?? 1
  const pct = Math.min(100, (used / limit) * 100)

  return (
    <div className="mx-auto max-w-5xl px-4 py-10 sm:px-6">
      <div className="flex flex-wrap items-end justify-between gap-6">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight">File history</h1>
          <p className="mt-1 text-muted">Everything you've processed while signed in. The oldest files are removed when storage is full.</p>
        </div>
        {me?.storage && (
          <div className="w-full sm:w-72">
            <div className="mb-1.5 flex justify-between text-sm">
              <span className="font-semibold">Storage</span>
              <span className="text-muted">
                {formatBytes(used)} of {formatBytes(limit)}
              </span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-line">
              <div className={clsx('h-full rounded-full', pct > 90 ? 'bg-danger' : 'bg-brand')} style={{ width: `${pct}%` }} />
            </div>
          </div>
        )}
      </div>

      <div className="mt-8 flex gap-2">
        {(
          [
            ['output', 'Results'],
            ['input', 'Uploads'],
            ['', 'All files'],
          ] as const
        ).map(([k, label]) => (
          <button key={k} onClick={() => setKind(k)} className={clsx('rounded-full border px-4 py-1.5 text-sm font-semibold', kind === k ? 'border-fg bg-fg text-bg' : 'border-line bg-surface')}>
            {label}
          </button>
        ))}
      </div>

      {error && <div className="mt-4 rounded-xl bg-danger-soft p-3 text-sm text-danger">{error}</div>}

      <div className="card mt-4 divide-y divide-line">
        {q.isPending && (
          <div className="grid place-items-center p-12">
            <Loader2 className="size-6 animate-spin text-brand" />
          </div>
        )}
        {q.isError && <div className="p-8 text-center text-sm text-danger">Could not load your files.</div>}
        {q.isSuccess && files.length === 0 && (
          <div className="p-12 text-center">
            <p className="text-muted">No files yet.</p>
            <Link to="/" className="btn-primary mt-4">
              Try a tool
            </Link>
          </div>
        )}
        {files.map((f) => (
          <div key={f.id} className="flex items-center gap-4 px-4 py-3 sm:px-5">
            <FileIcon mime={f.mime} />
            <div className="min-w-0 flex-1">
              <div className="truncate text-sm font-semibold">{f.name}</div>
              <div className="text-xs text-muted">
                {formatBytes(f.size)} · {formatDate(f.created_at)}
              </div>
            </div>
            {f.mime === 'application/pdf' && (
              <select
                className="hidden rounded-lg border border-line bg-surface px-2 py-1.5 text-xs md:block"
                value=""
                onChange={(e) => e.target.value && navigate(`/${e.target.value}?files=${f.id}`)}
                aria-label="Open with tool"
              >
                <option value="">Open with…</option>
                {tools
                  .filter((t) => t.accepts === 'pdf')
                  .map((t) => (
                    <option key={t.slug} value={t.slug}>
                      {t.title}
                    </option>
                  ))}
              </select>
            )}
            <button className="btn-ghost px-2.5" onClick={() => act(f.id, () => downloadFile(f.id))} disabled={busy === f.id} aria-label={`Download ${f.name}`}>
              {busy === f.id ? <Loader2 className="size-4 animate-spin" /> : <Download className="size-4" />}
            </button>
            <button className="btn-ghost px-2.5 hover:text-danger" onClick={() => remove(f)} disabled={busy === f.id} aria-label={`Delete ${f.name}`}>
              <Trash2 className="size-4" />
            </button>
          </div>
        ))}
      </div>
      {q.hasNextPage && (
        <div className="mt-4 text-center">
          <button className="btn-secondary" onClick={() => q.fetchNextPage()} disabled={q.isFetchingNextPage}>
            {q.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </div>
  )
}
