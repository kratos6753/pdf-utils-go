// Thin client for the PDF Utils API (/v1). The access token lives in memory;
// the refresh token is an httpOnly cookie scoped to /v1/auth.

export interface FileMeta {
  id: string
  name: string
  size: number
  mime: string
  kind: 'input' | 'output'
  job_id?: string
  created_at: string
  expires_at: string | null
}

export interface Job {
  id: string
  tool: string
  status: 'queued' | 'processing' | 'done' | 'failed'
  progress: number
  error?: { code: string; message: string }
  input_ids: string[]
  output?: FileMeta
  created_at: string
  finished_at: string | null
}

export interface User {
  id: string
  email: string
  name: string
  avatar_url: string
  plan: string
  email_verified: boolean
  has_password: boolean
  has_google: boolean
  created_at: string
}

export interface Limits {
  tasks_per_hour: number
  max_file_mb: number
  max_files_per_task: number
  storage_mb: number
}

export interface Me {
  user: User | null
  plan: string
  limits: Limits
  tasks?: { remaining: number; limit: number; reset_at: string }
  storage?: { used_bytes: number; limit_bytes: number }
}

export interface Session {
  access_token: string
  expires_at: string
  user: User
}

export class ApiError extends Error {
  status: number
  code: string
  details?: Record<string, unknown>
  constructor(status: number, code: string, message: string, details?: Record<string, unknown>) {
    super(message)
    this.status = status
    this.code = code
    this.details = details
  }
}

const DEVICE_KEY = 'pdfutils.device'

/** A random per-browser id so anonymous work stays tied to this device. */
export function deviceId(): string {
  try {
    let id = localStorage.getItem(DEVICE_KEY)
    if (!id) {
      id = crypto.randomUUID().replaceAll('-', '')
      localStorage.setItem(DEVICE_KEY, id)
    }
    return id
  } catch {
    return 'nostorage' + Math.random().toString(36).slice(2).padEnd(16, '0')
  }
}

let accessToken: string | null = null
let onSessionChange: (s: Session | null) => void = () => {}
let refreshing: Promise<Session | null> | null = null

export function setSessionListener(fn: (s: Session | null) => void) {
  onSessionChange = fn
}

const SESSION_HINT = 'pdfutils.session'

export function setSession(s: Session | null) {
  accessToken = s?.access_token ?? null
  try {
    if (s) localStorage.setItem(SESSION_HINT, '1')
    else localStorage.removeItem(SESSION_HINT)
  } catch {
    /* storage unavailable */
  }
  onSessionChange(s)
}

/** Whether this browser probably has a refresh cookie worth trying. */
export function hasSessionHint(): boolean {
  try {
    return localStorage.getItem(SESSION_HINT) === '1'
  } catch {
    return true
  }
}

function baseHeaders(): Record<string, string> {
  const h: Record<string, string> = { 'X-Device-Id': deviceId(), 'X-Client-Type': 'web' }
  if (accessToken) h.Authorization = `Bearer ${accessToken}`
  return h
}

async function toError(res: Response): Promise<ApiError> {
  try {
    const body = await res.json()
    const e = body?.error ?? {}
    return new ApiError(res.status, e.code ?? 'error', e.message ?? res.statusText, e.details)
  } catch {
    return new ApiError(res.status, 'error', res.statusText || 'Request failed')
  }
}

/** Exchanges the refresh cookie for a new access token. Concurrent callers share one request. */
export function refreshSession(): Promise<Session | null> {
  refreshing ??= (async () => {
    try {
      const res = await fetch('/v1/auth/refresh', {
        method: 'POST',
        headers: { 'X-Device-Id': deviceId(), 'X-Client-Type': 'web', 'Content-Type': 'application/json' },
        body: '{}',
        credentials: 'same-origin',
      })
      const s = res.ok ? ((await res.json()) as Session) : null
      setSession(s)
      return s
    } catch {
      return null
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

type Opts = { method?: string; json?: unknown; signal?: AbortSignal; headers?: Record<string, string> }

export async function api<T>(path: string, opts: Opts = {}, retried = false): Promise<T> {
  const headers = { ...baseHeaders(), ...opts.headers }
  if (opts.json !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(`/v1${path}`, {
    method: opts.method ?? (opts.json !== undefined ? 'POST' : 'GET'),
    headers,
    body: opts.json !== undefined ? JSON.stringify(opts.json) : undefined,
    signal: opts.signal,
    credentials: 'same-origin',
  })
  if (res.status === 401 && !retried && accessToken && !path.startsWith('/auth/')) {
    // Access token expired: refresh once and retry.
    if (await refreshSession()) return api<T>(path, opts, true)
  }
  if (!res.ok) throw await toError(res)
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** Uploads files with progress reporting (fetch has no upload progress). */
export function uploadFiles(files: File[], onProgress: (fraction: number) => void, signal?: AbortSignal): Promise<FileMeta[]> {
  const send = () =>
    new Promise<FileMeta[]>((resolve, reject) => {
      const xhr = new XMLHttpRequest()
      const form = new FormData()
      for (const f of files) form.append('file', f, f.name)
      xhr.open('POST', '/v1/uploads')
      for (const [k, v] of Object.entries(baseHeaders())) xhr.setRequestHeader(k, v)
      xhr.upload.onprogress = (e) => e.lengthComputable && onProgress(e.loaded / e.total)
      xhr.onload = () => {
        let body: { files?: FileMeta[]; error?: { code: string; message: string; details?: Record<string, unknown> } } = {}
        try {
          body = JSON.parse(xhr.responseText)
        } catch {
          /* non-JSON error */
        }
        if (xhr.status >= 200 && xhr.status < 300 && body.files) resolve(body.files)
        else reject(new ApiError(xhr.status, body.error?.code ?? 'upload_failed', body.error?.message ?? 'Upload failed', body.error?.details))
      }
      xhr.onerror = () => reject(new ApiError(0, 'network', 'Network error while uploading'))
      xhr.onabort = () => reject(new ApiError(0, 'aborted', 'Upload cancelled'))
      signal?.addEventListener('abort', () => xhr.abort())
      xhr.send(form)
    })
  return send().catch(async (e) => {
    if (e instanceof ApiError && e.status === 401 && accessToken && (await refreshSession())) return send()
    throw e
  })
}

/** Gets a short-lived link and starts a browser download. */
export async function downloadFile(id: string) {
  const { url } = await api<{ url: string }>(`/files/${id}/link`)
  const a = document.createElement('a')
  a.href = url
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/** Fetches a stored file's bytes (e.g. to preview an output chained into another tool). */
export async function fetchFileBlob(id: string): Promise<Blob> {
  const { url } = await api<{ url: string }>(`/files/${id}/link`)
  const res = await fetch(url)
  if (!res.ok) throw new ApiError(res.status, 'download_failed', 'Could not load file')
  return res.blob()
}
