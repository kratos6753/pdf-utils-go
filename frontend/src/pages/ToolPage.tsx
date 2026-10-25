import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { AlertCircle, ArrowRight, Plus, RefreshCw, ShieldCheck } from 'lucide-react'
import { api, ApiError, fetchFileBlob, uploadFiles, type FileMeta, type Job } from '../lib/api'
import { useMe } from '../lib/useMe'
import { defaultValues, validateFields, type Tool, type Values } from '../lib/tools'
import { FileGrid, WindowDrop, type Item } from '../components/Files'
import { acceptAttr, usePicker } from '../lib/picker'
import { Options } from '../components/Options'
import { PageEditor } from '../components/PageEditor'
import { Done, LimitNotice, Processing } from '../components/Result'
import { ToolIcon } from '../components/ToolCard'

const imageRe = /\.(jpe?g|png|webp|tiff?)$/i

function accepts(tool: Tool, f: File) {
  if (tool.accepts === 'pdf') return f.type === 'application/pdf' || /\.pdf$/i.test(f.name)
  return /^image\/(jpeg|png|webp|tiff)$/.test(f.type) || imageRe.test(f.name)
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export default function ToolPage({ tool }: { tool: Tool }) {
  const qc = useQueryClient()
  const { data: me } = useMe()
  const [params, setParams] = useSearchParams()

  const [items, setItems] = useState<Item[]>([])
  const [values, setValues] = useState<Values>(() => defaultValues(tool))
  const [confirm, setConfirm] = useState<Record<string, string>>({})
  const [pageOrder, setPageOrder] = useState<number[]>()
  const [removed, setRemoved] = useState<number[]>()
  const [pageCount, setPageCount] = useState<number>()
  const [phase, setPhase] = useState<'edit' | 'processing' | 'done'>('edit')
  const [job, setJob] = useState<Job | null>(null)
  const [error, setError] = useState<string>()
  const [limitError, setLimitError] = useState<ApiError>()
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  useEffect(() => {
    document.title = `${tool.title} – PDF Utils`
  }, [tool])

  const update = useCallback((key: string, patch: Partial<Item>) => setItems((xs) => xs.map((x) => (x.key === key ? { ...x, ...patch } : x))), [])

  const maxFiles = Math.min(me?.limits.max_files_per_task ?? 20, tool.multiple ? 200 : 1)
  const maxMB = me?.limits.max_file_mb ?? 50

  const addFiles = useCallback(
    (files: File[]) => {
      setError(undefined)
      const ok = files.filter((f) => accepts(tool, f))
      if (ok.length < files.length) setError(tool.accepts === 'pdf' ? 'Only PDF files can be used with this tool.' : 'Only JPG, PNG, WEBP or TIFF images are supported.')
      const tooBig = ok.find((f) => f.size > maxMB * 1024 * 1024)
      if (tooBig) {
        setLimitError(new ApiError(413, 'file_too_large', `${tooBig.name} is larger than ${maxMB} MB`))
        return
      }
      if (!ok.length) return
      const fresh: Item[] = ok.map((f) => ({ key: crypto.randomUUID(), name: f.name, size: f.size, blob: f, status: 'uploading', progress: 0 }))
      setItems((xs) => {
        const next = tool.multiple ? [...xs, ...fresh] : fresh.slice(0, 1)
        if (next.length > maxFiles) setError(`You can use up to ${maxFiles} files at once.`)
        return next.slice(0, maxFiles)
      })
      for (const it of tool.multiple ? fresh : fresh.slice(0, 1)) {
        uploadFiles([it.blob as File], (p) => update(it.key, { progress: p }))
          .then(([meta]) => update(it.key, { status: 'ready', meta, progress: 1 }))
          .catch((e: ApiError) => {
            update(it.key, { status: 'error', error: e.message })
            if (e.status === 429 || e.code === 'file_too_large' || e.code === 'quota_exceeded') setLimitError(e)
          })
      }
    },
    [tool, maxFiles, maxMB, update],
  )

  // Files chained from another tool's result: /compress-pdf?files=<id>
  useEffect(() => {
    const ids = params.get('files')?.split(',').filter(Boolean) ?? []
    if (!ids.length) return
    setParams({}, { replace: true })
    for (const id of ids.slice(0, maxFiles)) {
      const key = crypto.randomUUID()
      setItems((xs) => [...(tool.multiple ? xs : []), { key, name: 'Loading…', size: 0, status: 'uploading', progress: 0.5 }])
      Promise.all([api<FileMeta>(`/files/${id}`), fetchFileBlob(id)])
        .then(([meta, blob]) => update(key, { name: meta.name, size: meta.size, meta, blob, status: 'ready', progress: 1 }))
        .catch(() => update(key, { status: 'error', name: 'File unavailable', error: 'This file has expired' }))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const picker = usePicker(acceptAttr[tool.accepts], tool.multiple, addFiles)
  const ready = items.length > 0 && items.every((i) => i.status === 'ready')
  const uploading = items.some((i) => i.status === 'uploading')
  const inputBytes = useMemo(() => items.reduce((n, i) => n + i.size, 0), [items])

  const restart = () => {
    setItems([])
    setJob(null)
    setPhase('edit')
    setError(undefined)
    setConfirm({})
    setValues(defaultValues(tool))
  }

  const process = async () => {
    setError(undefined)
    if (items.length < tool.minFiles) return setError(`Add at least ${tool.minFiles} files.`)
    if (items.some((i) => i.status === 'error')) return setError('Remove the files that failed to upload first.')
    const ctx = { pageOrder, removed, pageCount }
    const msg = validateFields(tool, values, confirm) ?? tool.validate?.(values, ctx) ?? null
    if (msg) return setError(msg)

    const options = tool.toOptions ? tool.toOptions(values, ctx) : values
    try {
      let j = await api<Job>(`/tasks/${tool.api}`, {
        json: { file_ids: items.map((i) => i.meta!.id), options },
        headers: { 'Idempotency-Key': crypto.randomUUID() },
      })
      setJob(j)
      setPhase('processing')
      for (let n = 0; alive.current && (j.status === 'queued' || j.status === 'processing'); n++) {
        await sleep(n < 10 ? 600 : 1500)
        j = await api<Job>(`/jobs/${j.id}`)
        if (alive.current) setJob(j)
      }
      if (!alive.current) return
      qc.invalidateQueries({ queryKey: ['me'] })
      qc.invalidateQueries({ queryKey: ['files'] })
      if (j.status === 'done' && j.output) {
        setPhase('done')
      } else {
        setPhase('edit')
        setError(j.error?.message ?? 'Something went wrong. Please try again.')
      }
    } catch (e) {
      setPhase('edit')
      if (e instanceof ApiError && e.status === 429) setLimitError(e)
      else setError(e instanceof ApiError ? e.message : 'Something went wrong. Please try again.')
    }
  }

  const drop = <WindowDrop onFiles={addFiles} label={tool.accepts === 'pdf' ? 'Drop PDF files here' : 'Drop images here'} />
  const notice = limitError && <LimitNotice error={limitError} onDismiss={() => setLimitError(undefined)} />

  if (phase === 'processing') return <Processing tool={tool} job={job} />
  if (phase === 'done' && job) return <Done tool={tool} job={job} inputBytes={inputBytes} onRestart={restart} />

  if (!items.length)
    return (
      <div className="relative overflow-hidden">
        {drop}
        {notice}
        {picker.input}
        <div className="mx-auto flex max-w-3xl flex-col items-center px-4 pb-16 pt-16 text-center sm:pt-24">
          <ToolIcon tool={tool} size="lg" />
          <h1 className="mt-6 text-4xl font-extrabold tracking-tight sm:text-5xl">{tool.title}</h1>
          <p className="mt-4 max-w-xl text-lg text-muted">{tool.description}</p>
          <button className="btn-primary btn-lg mt-10 min-w-72 rounded-2xl py-5 text-lg shadow-[0_8px_24px_-6px_var(--color-brand)]" onClick={picker.open}>
            <Plus className="size-6" /> Select {tool.accepts === 'pdf' ? (tool.multiple ? 'PDF files' : 'PDF file') : 'images'}
          </button>
          <p className="mt-3 text-sm text-muted">or drop {tool.accepts === 'pdf' ? 'PDFs' : 'images'} here</p>
          {error && <p className="mt-4 text-sm text-danger">{error}</p>}
          <div className="mt-12 flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-xs text-muted">
            <span className="flex items-center gap-1.5">
              <ShieldCheck className="size-4 text-ok" /> Encrypted transfer, automatic deletion
            </span>
            <span>Up to {maxMB} MB per file</span>
          </div>
        </div>
      </div>
    )

  const editorBlob = tool.pageEditor ? items[0]?.blob : undefined
  const canAddMore = tool.multiple && items.length < maxFiles

  return (
    <div className="flex min-h-[calc(100dvh-4rem)] flex-col lg:flex-row">
      {drop}
      {notice}
      {picker.input}
      <section className="flex-1 px-4 py-6 sm:px-8 lg:py-10">
        <div className="mb-5 flex items-center justify-between gap-3">
          <h2 className="text-sm font-semibold text-muted">
            {tool.pageEditor ? items[0].name : `${items.length} file${items.length > 1 ? 's' : ''}`}
            {tool.multiple && items.length > 1 && ' · drag to reorder'}
          </h2>
          {!tool.multiple && (
            <button className="btn-secondary py-1.5" onClick={picker.open}>
              <RefreshCw className="size-4" /> Replace file
            </button>
          )}
        </div>
        {editorBlob ? (
          <PageEditor blob={editorBlob} mode={tool.pageEditor!} onOrder={setPageOrder} onRemoved={setRemoved} onPageCount={setPageCount} />
        ) : (
          <FileGrid items={items} onChange={setItems} onRemove={(k) => setItems((xs) => xs.filter((x) => x.key !== k))} onAdd={canAddMore ? picker.open : undefined} sortable={tool.multiple} />
        )}
      </section>

      <aside className="flex flex-col border-t border-line bg-surface lg:sticky lg:top-16 lg:h-[calc(100dvh-4rem)] lg:w-[380px] lg:border-l lg:border-t-0">
        <div className="flex items-center gap-3 border-b border-line px-6 py-5">
          <ToolIcon tool={tool} size="sm" />
          <h1 className="text-lg font-bold">{tool.title}</h1>
        </div>
        <div className="flex-1 overflow-y-auto px-6 py-6">
          {tool.fields.length > 0 ? (
            <Options fields={tool.fields} values={values} onChange={setValues} confirm={confirm} onConfirm={setConfirm} />
          ) : (
            <p className="text-sm text-muted">
              {tool.api === 'merge'
                ? items.length < 2
                  ? 'Add at least one more PDF to merge.'
                  : 'Files will be merged in the order shown. Drag them to change the order.'
                : tool.pageEditor === 'organize'
                  ? 'Drag pages to reorder them, or remove pages you do not need.'
                  : 'Select the pages you want to remove.'}
            </p>
          )}
        </div>
        <div className="sticky bottom-0 border-t border-line bg-surface px-6 py-5">
          {error && (
            <div className="mb-3 flex items-start gap-2 rounded-xl bg-danger-soft p-3 text-sm text-danger" role="alert">
              <AlertCircle className="mt-0.5 size-4 shrink-0" />
              {error}
            </div>
          )}
          <button className="btn-primary btn-lg w-full text-lg" onClick={process} disabled={!ready || items.length < tool.minFiles}>
            {uploading ? 'Uploading…' : tool.verb}
            {!uploading && <ArrowRight className="size-5" />}
          </button>
          {me?.tasks && me.tasks.remaining <= 3 && (
            <p className="mt-2 text-center text-xs text-muted">
              {me.tasks.remaining} task{me.tasks.remaining === 1 ? '' : 's'} left this hour
            </p>
          )}
        </div>
      </aside>
    </div>
  )
}
